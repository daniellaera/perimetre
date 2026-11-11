# Périmètre

Geneva civic app: users register an address + radius and get a push notification when a
building-permit dossier (SITG open data layer `SIT_AUTOR_DOSSIER`) appears or changes status
within that radius.

- `backend/`: Spring Boot 4, Java 21, Gradle (Groovy DSL), base package `ch.perimetre`
- `README.md` (English, public), `LICENSE` (AGPL-3.0, Copyright Daniel Laera), `docs/` (map-tile decision,
  App Store checklist, screenshots). The repository is meant to be published: no secret, host or
  personal detail in tracked files (operator notes: `CLAUDE.local.md`).
- `app/`: Flutter, iOS first (Android must compile), two tabs: Explorer and Mes périmètres (see "App" below)

## Operator notes

Status, next steps, the production host and its runbooks live in **`CLAUDE.local.md`** (git-ignored, on the
operator's machine only). Read it first when it exists.

## Local environment

- `docker compose up -d` starts PostGIS 17 (`perimetre-db`, perimetre/perimetre@localhost:5433,
  db `perimetre`) and Redis 7 (`perimetre-redis`, localhost:6380).
- `cd backend && ./gradlew bootRun` (port 8090); health at `GET http://localhost:8090/actuator/health`.
  With the dev endpoint: `./gradlew bootRun --args='--spring.profiles.active=dev'`.
- Reset the DB: `docker compose down -v && docker compose up -d`.

## Local ports

Périmètre uses **8090** (backend), **5433** (PostGIS) and **6380** (Redis), not the defaults, so it can
share a machine with other projects. If a port is already in use, do not stop the process holding it:
pick the next free port for the current session only via an override (env var, `--server.port`, or an
untracked `docker-compose.override.yml`, which is in `.gitignore`), say which port you used, and do not
change committed config. Docker containers whose names do not start with `perimetre-` are never to be
touched.

## Backend conventions

- **No JPA/Hibernate.** Hand-written SQL via `JdbcClient` (PostGIS). `JdbcClient` has no batch
  API, so batched writes use `JdbcTemplate.batchUpdate` (see `DossierRepository.upsert`).
- Schema changes go through Flyway (`src/main/resources/db/migration`). Never edit an applied
  migration; add `V<n>__*.sql`.
- Boot 4 is modular: use the `spring-boot-starter-*` module starters (e.g. `starter-webmvc`,
  `starter-flyway`); Jackson is 3.x (`tools.jackson.*`, annotations still `com.fasterxml.jackson.annotation`).
- The Gradle toolchain is pinned to **Temurin 21** (foojay resolver auto-provisions it). Oracle JDK's
  truststore lacks the WISeKey root CA used by `vector.sitg.ge.ch`, so HTTPS to SITG fails on it.
  Any runtime image must likewise use an OpenJDK build that ships that root (e.g. eclipse-temurin).

## SITG import (`ch.perimetre.sitg`)

Source: `https://vector.sitg.ge.ch/arcgis/rest/services/Hosted/SIT_AUTOR_DOSSIER/FeatureServer/0/query`
with `outFields=*`, `outSR=4326`, `f=geojson`, `orderByFields=objectid ASC`,
`resultRecordCount=1000`, `resultOffset` paging. Continue while a page returns 1000 features or
`properties.exceededTransferLimit` is true; stop on a short page or an empty one.

- **Query (mode `INCREMENTAL`):** `where = date_maj_2 >= TIMESTAMP '<cursor>' AND date_depot IS NOT NULL`,
  with `cursor = sync_state.last_run - 3 days` (source `SIT_AUTOR_DOSSIER`), formatted
  `yyyy-MM-dd HH:mm:ss` in UTC. The 3-day overlap is deliberate: `date_maj_2` is date-only and the batch
  stamped day D is only published the next morning (see "Data facts"), so a short overlap can miss rows
  published after a run. It also absorbs a publication up to 2 days late (the batch of D is fetched as long
  as it appears before the run of D+3; `LatePublicationTest`); later than that only the weekly full catches
  it. Re-fetching a few hundred rows per run is cheap and the upsert is idempotent.
- **Query (mode `FULL`):** same import with `where = date_maj_2 >= TIMESTAMP '2025-01-01 00:00:00' AND
  date_depot IS NOT NULL` (~21k rows, ~7 s). A safety net: a statut change does move `date_maj_2` (see
  "Data facts") and a full pass right after an incremental found nothing more (2026-10-27), but one earlier
  observation (statut changed on 21.09, `date_maj_2` stayed 27.08) says it may not always; the weekly full
  pass catches any such miss. Events are detected the same way.
- **Field mapping:** `id_dossier` (business key, PK) · `type_dossier` · `type_operation` · `operation` ·
  `description` · `statut` → `statut_sitg` · `date_depot` (epoch ms, UTC date) · `date_maj_2` →
  `date_maj_sitg` (epoch ms) · `lien_sad` · Point lon/lat → `geom` (`ST_SetSRID(ST_MakePoint(lon,lat),4326)`).
  Features missing id, type, `date_maj_2` or geometry are skipped with a WARN.
- **`statut` → `groupe`:**
  | groupe   | statut values |
  |----------|---------------|
  | DEPOSE   | ENREGISTREMENT, ENREGISTRE, INSTRUCTION |
  | AUTORISE | ACCEPTE, DECIDE |
  | CHANTIER | CHANTIER |
  | RECOURS  | RECOURS |
  | REFUSE   | REFUSE, REFUS ENTREE, ABANDONNE, CADUQUE, AUTORISATION ANNULEE |
  | TERMINE  | TERMINE, ARCHIVE, ARCHANT, CLOTURE ADMIN |
  | IGNORE   | everything else (EN SUSPENS, RENVOYE, PROCEDURE AMS, ORMIG, `-`, null, unknown) |
- **`flags`** from the lower-cased (NFC-normalised, null-safe) description, in this order:
  `arbres` (abattage, élagage) · `logements` (logement) · `parking` (parking, stationnement) ·
  `piscine` (piscine) · `antenne` (télécommunication mobile, antenne) · `surelevation`
  (surélévation) · `plq` (plq).
- **Write:** incoming features are de-duplicated by `id_dossier` (offset paging can repeat one; the
  last wins), then upserted by `id_dossier`, JDBC batches of 500. On conflict update all mutable
  columns and `last_seen = now()`; `first_seen` is never touched.
- **Transaction:** all pages are fetched first. Then, in one transaction: load the stored
  `(id_dossier, groupe)` of every incoming id (one `= ANY(array)` query), detect events, upsert,
  insert the events, and set `sync_state.last_run = now(), bootstrapped = true` (both modes). On any
  failure nothing is written, the cursor stays untouched, and the error propagates (HTTP 500 + ERROR log).
- **Change detection** (`ChangeDetector`, pure function, unit-tested):
  - stored row absent AND new groupe = DEPOSE → event `NEW` (`old_groupe` null)
  - stored groupe ≠ new groupe AND new groupe NOT IN (IGNORE, TERMINE) → event `GROUPE_CHANGE (old, new)`
  - anything else (new dossier in another groupe, unchanged, change to IGNORE/TERMINE) → silent update
  - `sync_state.bootstrapped = false` → no events at all, then set to true. That covers the first
    import and any `docker compose down -v` reset (and the first import after V3 on an existing DB).
- **Scheduling** (`ImportJob`, `@EnableScheduling`): crons in `application.yml`, both with
  `zone = "Europe/Zurich"` fixed in the annotation: `perimetre.import.incremental-cron` (env
  `IMPORT_INCREMENTAL_CRON`, default daily 10:15) and `perimetre.import.full-cron` (env
  `IMPORT_FULL_CRON`, default Sundays 10:45), i.e. after SITG's morning publication. Until 2026-10
  they ran at 03:15 / 03:45, before it, which delayed every notification by a day. `LatePublicationTest`
  checks the incremental runs daily and the full weekly, ≥ 10 min after that day's incremental. An in-process `ReentrantLock.tryLock` serialises runs: a
  scheduled run that finds it held logs a WARN and skips; `POST /admin/import` answers 409. Each run =
  import, then `NotificationDispatcher.dispatch(eventIds of that run)`. A dispatch failure is logged
  but does not fail the run (the import is committed; its notifications stay PENDING).
  **Every run** (scheduled or `/admin/import`) inserts a RUNNING `job_run` row, then sets SUCCESS,
  FAILED (import threw: nothing written, counters null, `error_message`), DISPATCH_FAILED (import
  committed, dispatch threw) or STALE_SOURCE (see below), and logs one line `Job run <id> <job_name>
  (<trigger>) <status>: fetched= changed= notified= pushes= fcm_errors= max_date_maj= durationMs=` (WARN
  for STALE_SOURCE, else INFO). **Source freshness:** `max_date_maj_sitg` = the newest `date_maj_2` the
  run fetched (V7). When it is more than 4 days (`ImportJob.MAX_SOURCE_AGE`) before the run's start, or
  the run fetched nothing, a run that otherwise succeeded ends STALE_SOURCE (import and dispatch done as
  usual, `error_message` says why) and a scheduled one pings `/fail`. DISPATCH_FAILED wins over it. On
  SITG's history (2025-01 to 2026-10) a 10:15 run would have been STALE_SOURCE only during the one real
  outage (5 days without stamps, 2025-05-16 → 21: the runs of 05-20 and 05-21). 4 days rather than 3
  because stamps have 3-day gaps a few times a year (e.g. 2026-10-10 → 13), which the 3-day cursor
  still covers; 3 days would have alerted on 8 mornings. Over time the column also shows whether 10:15 gets each day's batch
  (normally `max_date_maj` = the day before the run). `changed` = events detected (NEW +
  GROUPE_CHANGE), not upserted rows (every re-fetched row is "updated"). RUNNING rows found at startup
  (a restart mid-run) are marked FAILED. **Dead-man's switch** (`HealthcheckPinger`): with env
  `HEALTHCHECK_PING_URL` (healthchecks.io), scheduled runs only POST `<url>/start`, then `<url>` on
  SUCCESS or `<url>/fail` (summary as body); manual runs never ping. Unset/blank = no-op; a failed ping
  is a WARN (without the URL) and never fails the run; 10 s timeouts. The healthchecks.io check's
  schedule must match `perimetre.import.incremental-cron`.
  At startup `ScheduleReport` logs one `Scheduled … cron "…", next run …` line per registered cron task
  (read from the scheduler, next run in Europe/Zurich).
- **HTTP:** `RestClient` (JDK client), 60 s connect/read timeout. Per page, with a 2 s then 4 s backoff:
  up to 3 attempts on an I/O error (connection reset, which SITG does now and then; timeout; truncated
  body), one retry on HTTP 5xx (SITG answers bad queries with a 500 HTML maintenance page). ArcGIS
  `{"error":…}` bodies with HTTP 200 fail the import without retry. One INFO log line per page.
- **Data facts (2026-10):** ~20.9k dossiers modified since 2025-01-01 (~198k in the layer), 20–340
  stamped per day (fewest on Sundays and Mondays). SITG reloads the whole layer daily (object ids are
  always 1..n). `date_maj_2` is date-only (midnight UTC): the day of the dossier's last change, a statut
  change included (all 19 statut changes seen over 46 h on 2026-10-25/27 moved it, even from months
  back). The batch stamped D is published on the morning of D+1, before 09:16 Zurich (observed: the
  10-26 batch was absent at 03:15 on 10-27 and present at 10:25; the 10-25 batch was there at 09:16 on
  10-26), hence the 10:15 incremental.

## Parcels (`ch.perimetre.parcelle`)

Land-registry context for each dossier, from **open data only**. **Hard rule: never store, scrape or display
owner names or any non-open registry data; never scrape the RF portal.** The source's `TYPE_PROPRI` and
`PROPRI_PUBLIQUE` columns were deliberately not requested at first. Since V10, `TYPE_PROPRI` is imported **as a
category only** (8 values: privé, DP communal, dépendance, DP cantonal, chemin vicinal, droits spéciaux, privé+dépendance,
DP communal (hors indice); it names nobody). `PROPRI_PUBLIQUE` (the public owner: SIG, Commune, CFF, Etat de Genève...) and
`CODE_PROPRI_PUBLIQUE_RF` are never requested (`ParcelleClient.OUT_FIELDS`). Tests check the requested fields, the
tables and the responses for owner-like names.

- **Source:** SITG layer **`CAD_PARCELLE_MENSU`** ("parcelle en vigueur", official survey parcels):
  `https://vector.sitg.ge.ch/arcgis/rest/services/CAD_PARCELLE_MENSU/FeatureServer/0/query`, same ArcGIS REST
  conventions as the dossiers (`outSR=4326`, `f=geojson`, `orderByFields=OBJECTID ASC`, `resultOffset` paging; the
  retry policy is shared, `ArcgisRetry`). 73,156 parcels (2026-11), all `VALIDITE = 'en vigueur'`, 48 communes.
  **`CAD_PLANRF` is NOT the parcel layer** (it is the 1,632 RF *plan sheets*, no parcel numbers). Fields kept: `IDEDDP` (`<no_comm>:<no_parcelle>`, business key) · `EGRID` · `COMMUNE`
  · `NO_COMM` · `NO_PARCELLE` · `SURFACE` (m²) · `DATEDT` (last mutation) · `LIEN_WWW` → `lien_rf` · `TYPE_PROPRI` → `type_parcelle` ·
  `PLAN_RF` → `plan_rf` · geometry.
- **Size decision (2026-11-09): keep every parcel with full geometry, no simplification, no subset.** The
  table is 70 MB (50 MB heap + 6 MB GIST index + TOAST; whole local DB 129 MB), a fraction of the VPS's 60 GB,
  and a subset ("parcels intersecting a dossier") would need a lazy lookup for every new dossier. Geometry is
  requested with `geometryPrecision=7` (~1 cm, a third less data). Pages are 1000 features and handed to the
  importer one at a time (never accumulated: the full layer is ~160 MB of GeoJSON, more than the 1.1 GB heap
  should hold as JSON trees); the import is still one transaction.
- **Import** (`ParcelleImporter`, `ParcelleJob`; `POST /admin/import/parcelles?mode=incremental|full`):
  - `INCREMENTAL`: `VALIDITE = 'en vigueur' AND DATEDT >= <cursor>`, `cursor = sync_state.last_run (source
    CAD_PARCELLE_MENSU) - 3 days` truncated to midnight UTC (DATEDT is date-only). `DATEDT` really is the last
    mutation (58 parcels in 2026-11, 2,730 since 2025-01). Deletions (merged/split parcels) are invisible to it.
  - `FULL`: every parcel; parcels no longer in the source are deleted; **refused (rolled back) when fewer than
    90% of the stored parcels come back** (`checkNotShrunk`); every link is recomputed. ~30 s. The first run is
    always FULL (`sync_state.bootstrapped = false`), whatever mode was asked, and the `job_run` row says so.
  - Upsert by `id_parcelle`, rewriting a row only when it really differs (`IS DISTINCT FROM` +
    `ST_OrderingEquals`), so `changed` = parcels inserted/modified (+ removed in FULL): a second full run after
    a full run reports `changed=0`.
  - Schedule (`application.yml`, zone Europe/Zurich fixed in the annotation): `perimetre.parcelle.incremental-cron`
    (env `PARCELLE_INCREMENTAL_CRON`, daily **10:30**) and `perimetre.parcelle.full-cron` (env
    `PARCELLE_FULL_CRON`, Sundays **11:15**), after the dossier imports of the same morning. `job_run` rows:
    `parcelle-incremental` / `parcelle-full` (`fetched` = parcels fetched, `changed` as above,
    `max_date_maj_sitg` = newest `DATEDT`, `notified/pushes/fcm_errors` = 0; no STALE_SOURCE: parcels are not
    stamped daily). Optional **own** healthchecks.io check, env `PARCELLE_HEALTHCHECK_PING_URL` (schedule
    `30 10 * * *`; the Sunday full run pings it too); unset = no-op. The dossier check stays untouched.
- **Link dossier ↔ parcel** (`dossier_parcelle`, computed at import time and stored): the dossier's point in the
  parcel with a **~5 cm tolerance** (`ST_DWithin(parcel.geom, dossier.geom, 5e-7°)`, uses `parcelle_geom_idx`),
  not a strict `ST_Intersects`. Why: SITG places ~21% of dossier points **within 1 cm of a parcel edge** (the point
  is on a boundary) and the outlines are rounded to ~1 cm, so a strict test picks one neighbour at random (found
  by spot-checking against SITG's own point-in-polygon query). Such a dossier gets all touching parcels:
  2026-11 local data, 21,098 dossiers: 16,311 with one parcel, 4,780 with two, 6 with three, 1 with none (a
  point in the public domain). The dossier layer has no parcel field, so a spatial join is the only route.
  - After each **dossier** import (`SitgImporter.linkParcels` → `DossierLinker`, own transaction after the
    import's, failure = WARN only, never undoes the import): the fetched dossiers are relinked.
  - After each **parcel** import: FULL relinks everything; INCREMENTAL relinks the dossiers linked to, or now
    inside, a changed parcel, plus any dossier that has no link yet and lies in a parcel (self-heal of a failed
    link step).
  - Relinks take a transaction-scoped advisory lock (`ParcelleRepository.LINK_LOCK`): dossier and parcel
    imports never rewrite links at once.
- **API:** `DossierView` gains `parcelles` (array, `[]` when none: `egrid, commune, numero, surface_m2,
  lien_rf`, ordered by commune number then parcel number) and `lien_registre_foncier`. `/dossiers` and every
  event feed (`/devices/{id}/events`, `.../subscriptions/{sid}/events`, legacy) carry them (one extra query for
  the whole list, ~20 ms for 200 dossiers); all pre-existing fields are unchanged.
  - `lien_rf`: SITG's own official ge.ch "extrait foncier" link for the parcel
    (`https://ge.ch/terextraitfoncier/rapport.aspx?commune=<no_comm>&parcelle=<no_parcelle>`), stored as published.
    `lien_registre_foncier` = **`https://www.ge.ch/consulter-registre-foncier`** (constant
    `DossierView.REGISTRE_FONCIER_URL`), the state's official page on consulting the land registry. We only
    *link*: ge.ch resets non-browser clients, the pages were not fetched (and must not be scraped).
    **Whether the extrait foncier page shows non-open data is up to ge.ch: the app opens it in the user's
    browser, it must not read it.** The RDPPF PDF link (`EXTRAIT_RDPPF_PDF`, public-law restrictions by EGRID)
    exists in the layer and is not imported (easy to add).
  - Until the first parcel import has run on a database, `parcelles` is `[]` everywhere.
  - **Production (2026-11-09):** deployed with V9 and bootstrapped by one manual full import: 73,156 parcels (70 MB),
    26,119 links; 16,437 dossiers with one parcel, 4,832 with two, 6 with three, 1 with none. See PROGRESS.md.

## Parcel context (`ch.perimetre.parcelle`, V10)

What else is **open** about a parcel, shown natively in the app (the owner and the official extract stay behind the
"Voir au registre foncier" link). Every source is a SITG ArcGIS layer, access class **A "Accès libre"** (SITG's
classification at https://sitg.ge.ch/ressources/conditions-utilisation-donnees: private and commercial use allowed,
**the source must be named**, here "Source : SITG"; checked on each layer's catalogue page on 2026-11-09, and for the
parcel layer also on opendata.swiss: `terms_open`). Anything whose terms are unclear is **not** imported (PROGRESS.md,
"needs a decision").

| layer | what | rows (2026-11) | kept |
|-------|------|----------------|------|
| `CAD_PARCELLE_MENSU` | parcel category, RF plan number | 73,156 | `TYPE_PROPRI` (category), `PLAN_RF` |
| `CAD_BATIMENT_HORSOL` | buildings above ground | 83,088 | `EGID` (key), `NO_BATIMENT`, `DESTINATION`, `NOMENCLATURE`, `SURFACE`, `ANNEE_CONSTRUCTION`, `NIVEAUX_HORSOL`, `DATEDT`, `EGRID_LISTE` (the parcels it stands on), geometry |
| `CAD_ADRESSE` | building addresses | 54,719 | `IDPADR`, `EGID`, `ADRESSE`, `NO_POSTAL`, `NOM_NPA`, `POSTALE` (no geometry) |
| `RDPPF_ZONES_PRIM` + `RDPPF_ZONES_DEV` | land-use zones (affectation; développement) | 2,479 + 756 | `ABRV_ZONE`, `NOM_ZONE`, `DESC_ZONE`, `COMMUNE`, `STATUT_JUR` (all "En vigueur"), geometry; the legal-document links are not stored |
| `CAD_NOMS_LOCAUX` | local names (lieux-dits) | 2,038 | `TEXTE`, geometry |

Not imported, on purpose: `NOMBAT` (institution names), `NO_AUTOR` (permit numbers), `PROPRI_PUBLIQUE` (owner),
`EXTRAIT_RDPPF_PDF` and the zones' `LIEN_*` (links into ge.ch documents; we do not link what we have not checked),
`CAD_BATIMENT_SOUSOL` (8,146 underground buildings: clutter), `CAD_DDP`, `CAD_PPE`, `CAD_NATURE_SOL`, and the other
`RDPPF_*` layers (noise, forest distance, polluted sites, water protection...: open, easy to add into `zone_affectation`
with a new `source`, not needed yet).

- **Link parcel ↔ building:** `parcelle_batiment`, from the building's official `EGRID_LISTE`; the footprint inside
  each parcel (`surface_sur_parcelle_m2`) is measured on the geometries (geography m², null when it rounds to 0).
  5 buildings have no `EGRID_LISTE` and no link. Addresses reach a building through its `EGID` at query time.
- **Link parcel ↔ zone:** `parcelle_zone`, a zone counts when it covers **≥ 1 m² and ≥ 1%** of the parcel (smaller overlaps
  are edge slivers; a zone that only touches the edge is dropped). `part_pct` = share of the parcel. The parcel
  outline is made valid first (one SITG parcel is self-intersecting).
- **Local name:** `parcelle.lieu_dit` = the polygon holding the parcel's inner point (`ST_PointOnSurface`).
- **Import** (`ContexteJob`, `LayerImporter` base, `ArcgisLayerClient` generic client with the shared `ArcgisRetry`):
  - buildings: `INCREMENTAL` on `DATEDT >= last_run - 3 days`, `FULL` upserts, deletes what vanished and is **refused
    below 90%** of the stored rows. A second full run reports `changed=0`.
  - addresses: always FULL (no date), same guard. Zones and local names: always FULL, replaced whole (small layers, no
    stable key), same guard. The first run of every layer is FULL.
  - After a **parcel** import the buildings, zones and local name of the inserted/modified parcels are relinked.
  - Schedule (`application.yml`, zone Europe/Zurich fixed in the annotation): `perimetre.contexte.incremental-cron` (env
    `CONTEXTE_INCREMENTAL_CRON`, daily **10:40**: buildings only) and `perimetre.contexte.full-cron` (env
    `CONTEXTE_FULL_CRON`, Sundays **11:30**: all four, in that order), after the parcel imports.
  - `job_run` rows: `batiment-incremental|full`, `adresse-full`, `zone-full`, `lieu-dit-full` (`fetched`; `changed` =
    inserted + modified + removed; zones and names are replaced whole, so `changed` = their row count every week).
    Optional own healthchecks.io check, env `CONTEXTE_HEALTHCHECK_PING_URL` (schedule `40 10 * * *`; the Sunday run
    pings it too, `/fail` when any layer fails); unset = no-op.
  - `POST /admin/import/contexte?source=batiments|adresses|zones|lieux-dits|all&mode=incremental|full` (default all /
    incremental; only buildings have an incremental mode, the rest runs FULL and the `job_run` row says so); 409 when
    one is running, 400 for an unknown source or mode.
  - **First deploy:** V10 marks the parcel source as not bootstrapped, so the next parcel run is a FULL one (fills
    `type_parcelle` and `plan_rf`). Rewriting every parcel row **bloats the table** (74 MB → 197 MB locally until
    `VACUUM FULL parcelle`, which locks it for a few seconds): run it once after the first full import, or leave it to
    autovacuum (space is reused, never returned).
- **Sizes (local, 2026-11-09, after `VACUUM FULL`):** `parcelle` 74 MB (with the 3 new columns), `batiment` 40 MB,
  `parcelle_batiment` 11 MB (86,285 links), `parcelle_zone` 10 MB (96,330 links), `adresse` 9 MB, `zone_affectation`
  6 MB, `lieu_dit` 3 MB; whole database 215 MB (was 129 MB). First load: parcels 31 s, buildings ~5 min (links
  included), addresses 9 s, zones 33 s, local names 5 s.
- **API** (additive; the first five fields of a parcel never change): each item of `parcelles[]` also carries
  `type` (the category), `plan_rf`, `lieu_dit`, `nb_batiments` (real count), `batiments[]` (the 30 largest on the parcel:
  `egid, numero, destination, surface_m2, surface_sur_parcelle_m2, annee_construction, adresses[]`) and
  `restrictions[]` (at most 10 zones, largest first: `type` ZONE | ZONE_DEVELOPPEMENT, `code, nom, description,
  statut, surface_m2, part_pct`; several polygons of one zone are summed). Lists are `[]` when empty, the scalars
  null. 200 dossiers weigh ~400 KB as JSON (Caddy gzips them), the query stays ~60 ms.

## Data model (Flyway V1–V10)

- `dossier`: one row per SITG dossier (see "SITG import"); `dossier_geog_gist` on `(geom::geography)`.
- `sync_state(source PK, last_run, bootstrapped)`: one row per source, `SIT_AUTOR_DOSSIER` and (V9)
  `CAD_PARCELLE_MENSU`.
- `parcelle` (V9, see "Parcels"): `id_parcelle` PK, `egrid`, `commune`, `no_comm`, `no_parcelle`, `surface_m2`,
  `date_mut`, `lien_rf`, `geom geometry(MultiPolygon,4326)` (GIST `parcelle_geom_idx`), `updated_at`.
  `dossier_parcelle(id_dossier → dossier, id_parcelle → parcelle, PK both, ON DELETE CASCADE)`.
  **V10** adds `parcelle.type_parcelle, plan_rf, lieu_dit` and the tables `batiment`, `parcelle_batiment`, `adresse`,
  `zone_affectation`, `parcelle_zone`, `lieu_dit` (see "Parcel context"), plus four `sync_state` rows
  (`CAD_BATIMENT_HORSOL`, `CAD_ADRESSE`, `RDPPF_ZONES`, `CAD_NOMS_LOCAUX`).
- `device(id UUID PK, push_token, platform ios|android|null, consecutive_failures, created_at,
  updated_at, last_test_push_at)` (V6; V8 added `last_test_push_at`): one app installation.
  `consecutive_failures` counts runs whose push failed with an invalid token (see "Notifications");
  `last_test_push_at` rate-limits `POST /devices/{id}/test-push`.
- `subscription(id UUID PK, device_id → device ON DELETE CASCADE, geom Point 4326, radius_m 100–1000,
  categories TEXT[] default all five notifiable groupes, label, address, created_at, updated_at)`;
  `subscription_geog_gist` on `(geom::geography)`, `subscription_device_id_idx`. `label` is the user's
  name for the perimeter (Domicile, Travail, free text), `address` the address it was placed at.
  **V6** moved `push_token`/`platform`/`consecutive_failures` to `device`: every existing subscription got
  a device with the **same id**, token, platform and counter, and its `label` (the single-subscription
  app stored its address there) moved to `address`. Rolling the backend back past V6 needs the
  pre-deploy `pg_dump` (the old columns are gone).
- `event(id BIGSERIAL, id_dossier → dossier, kind NEW|GROUPE_CHANGE, old_groupe, new_groupe, occurred_at)`,
  indexed on `occurred_at` and `id_dossier`.
- `notification(id, subscription_id → subscription ON DELETE CASCADE, event_id → event,
  status PENDING|SENT|FAILED|SKIPPED, sent_at, error, fcm_message_id, UNIQUE(subscription_id, event_id))`.
  `fcm_message_id` (V4) is FCM's answer to a sent push (`projects/<id>/messages/<uuid>`; a summary
  push stamps all its rows with the same id); null for `LogPushSender` and failed pushes.
- `job_run(id, job_name import-incremental|import-full, trigger SCHEDULED|MANUAL, started_at,
  finished_at, status RUNNING|SUCCESS|FAILED|DISPATCH_FAILED|STALE_SOURCE, fetched, changed, notified,
  pushes, fcm_errors, max_date_maj_sitg DATE, error_message)` (V5; V7 added `max_date_maj_sitg` and
  STALE_SOURCE): one row per import run, see "Scheduling". `trigger` and `pushes` are not in the
  original spec. Counters and `max_date_maj_sitg` are null on FAILED.

## Notifications (`ch.perimetre.notification`)

- **Matching** (`NotificationDispatcher`): for the events of a run, insert PENDING rows for every
  subscription with `ST_DWithin(sub.geom::geography, dossier.geom::geography, 1000) AND
  ST_DWithin(…, sub.radius_m) AND new_groupe = ANY(categories)`, `ON CONFLICT (subscription_id,
  event_id) DO NOTHING`; then send the PENDING rows of those events, grouped by **device**. The constant
  max-radius predicate is what makes the planner probe `subscription_geog_gist`: `radius_m` belongs to
  the indexed table, so the exact check alone is no index condition (measured 2026-10-26, 10k
  subscriptions × 200 events: 1185 ms seq-scan nested loop → 155 ms index scan). That constant is
  `SubscriptionInput.MAX_RADIUS_M` (with `MIN_RADIUS_M`, the single source of the radius bounds, also
  used by the request validation), so it can never be smaller than an accepted radius; the V3 CHECK on
  `radius_m` must say the same, which `MatchingRadiusIntegrationTest` asserts. Raising the maximum = the
  constant + a migration changing the CHECK + the app's slider.
- **Per device and run** (`PushPlanner`): notifications are grouped by event first, so an event
  matching several subscriptions of one device is ONE push (all its rows marked by it). ≤ 3 events → one
  push per event; > 3 → ONE summary push and all rows marked SENT together. So at most 3 pushes per
  device per run.
- **Payload** (`PushMessages`, French, never any names; only SITG's work description). The title leads with
  what a phone's lock screen would otherwise cut off:
  - title `"{distance} · {places}"`: distance rounded to the metre ("104 m"; "1,2 km" from 1 km, decimal
    comma), from the nearest matching subscription point. `{places}` (≤ 40 chars) names the matched
    subscriptions, nearest first (`placeName`): the label; else, when the device has ≥ 2 subscriptions, the
    address without postcode and locality. With none to name: `"{distance} de {street address}"` (≤ 30
    chars), else just the distance.
  - body `"{event text} — {description, max 90 chars + …}"` (just `"{event text}"` without description). Event
    text by `new_groupe`: DEPOSE "Nouvelle demande · opposition possible" · AUTORISE "Autorisation délivrée" · CHANTIER "Les travaux
    commencent" · RECOURS "Un recours a été déposé" · REFUSE "Le projet ne se fera pas".
  - data `{id_dossier, kind, new_groupe, subscription_id}` (nearest match). Summary: title "Périmètre",
    body "N nouveaux changements autour de chez vous" (N = events), or with named places
    "N nouveaux changements (Domicile : 3, Travail : 2)", data `{kind: SUMMARY, count}`. Test push
    (`PushMessages.test`, `POST /devices/{id}/test-push`): the same title/body builders on a sample dossier
    (104 m, DEPOSE, "Modification d'une installation de télécommunication mobile existante") with the places of the
    device's oldest subscription, data `{kind: TEST}`. iOS gets `aps.sound = default`.
- **Senders** (`PushSender`, chosen by `PushConfig` at startup): `FcmPushSender` (firebase-admin) when
  the env var `FIREBASE_CREDENTIALS` points to an existing service-account JSON file, otherwise
  `LogPushSender`, which logs `PUSH (log only) token=… title="…" body="…" data={…}` at INFO and
  reports success. The startup log says which one is active.
- **Failures:** FCM `UNREGISTERED`, `INVALID_ARGUMENT` or `SENDER_ID_MISMATCH` = invalid token: that
  push's rows → FAILED with the error, the rest of the device's pushes in that run → SKIPPED,
  `device.consecutive_failures + 1`; at 3 the device is deleted (subscriptions and notifications
  cascade). Any other failure → FAILED, not counted. A successful push resets the counter, and so does a
  device PUT (or a legacy subscription PUT).
- **Registration tokens are deprecated** in firebase-admin 9.11 (`Message.setToken` → `setFid`) and
  firebase_messaging 17 (`getToken`/`onTokenRefresh` → `register()`/`onRegistered`, which yield a
  Firebase Installation ID). Both sides still use tokens (deprecation suppressed and commented);
  migrate `FcmPushSender` and the app's `FirebasePushService` together.
- firebase-admin pulls google-cloud-storage, which brings `jackson-dataformat-xml`; it is excluded in
  `build.gradle` because Spring MVC would otherwise answer browsers' `Accept` headers with XML.

## API

- `POST /admin/import?mode=incremental|full` (default incremental): runs import + dispatch
  synchronously and returns `{mode, fetched, inserted, updated, events, notifications, pushes,
  durationMs}` plus `jobRunId` and `fcmErrors`; 409 when an import is already running, 400 for an
  unknown mode. `mode=full` is also how to trigger the weekly full refresh by hand.
- `POST /admin/test-push?subscription_id=<uuid>` (all profiles, behind `ADMIN_TOKEN`): one real push
  to that subscription's device token through the active `PushSender` (FCM on prod), built by
  `PushMessages.forEvent` for the dossier its `/dossiers` list shows first within its radius and
  categories (as if just reaching its current groupe, so a tap opens the details sheet). Writes nothing
  (no event, no notification row; an invalid token is reported, not counted). Returns `{subscriptionId,
  idDossier, title, body, status, fcmMessageId, error}`; 404 for an unknown subscription or an empty
  radius. The way to check FCM on prod without waiting for the 10:15 import.
- `POST /admin/import/parcelles?mode=incremental|full` (default incremental; the first run is always full):
  parcel import + relink, synchronous, `{jobRunId, mode, fetched, changed, removed, relinked, durationMs}`;
  409 when one is already running, 400 for an unknown mode (see "Parcels").
- `POST /admin/import/contexte?source=…&mode=…`: buildings, addresses, zones, local names (see "Parcel context").
- `GET /admin/job-runs?limit=20` (1–200): `job_run` rows newest first (`started_at DESC, id DESC`),
  snake_case, plus `duration_ms`; `status` and `max_date_maj_sitg` tell whether SITG's data was fresh.
- **`/admin/**` auth** (`ch.perimetre.admin`): a servlet filter on `/admin/*` wants
  `Authorization: Bearer <ADMIN_TOKEN>` (env var), else 401 + `WWW-Authenticate: Bearer`. Both sides are
  SHA-256-hashed, then `MessageDigest.isEqual` (constant time, length included). Profile `prod` refuses
  to start without a token of ≥ 32 chars; elsewhere an unset token leaves `/admin` open with a WARN
  (local runs, context tests). Everything else (`/dossiers`, `/devices`, `/subscriptions`, health) stays open.
- `GET /dossiers?lat=&lng=&radius=`: radius in metres (1–2000). Dossiers within the radius
  (`ST_DWithin`/`ST_Distance` on geography), excluding groupes `IGNORE` and `TERMINE` (both stay in
  the table), ordered by `date_maj_sitg DESC, distance_m ASC, id_dossier ASC` (deterministic, since
  `date_maj_sitg` is day-granular), limit 200. Fields: `id_dossier, type_dossier, type_operation,
  operation, description, groupe, date_depot, date_maj_sitg, flags, lien_sad, lat, lng, distance_m`.
  Also `parcelles` and `lien_registre_foncier` (see "Parcels"). `operation` and `description` are SITG's raw texts. `operation` is "Non renseigné (valeur par
  défaut…)" when `type_operation` is `--`. `description` may have trailing whitespace.
  Served by the expression index `dossier_geog_gist` on `(geom::geography)` (V2). Keep the predicate
  written as `ST_DWithin(geom::geography, …)` or the planner falls back to a seq scan.
- **Devices and subscriptions** (no auth: the app keeps the device's random UUID, the only handle; a
  subscription is reached through its device; snake_case JSON; the push token is never echoed back):
  - `POST /devices {push_token, platform?}` → 201 + `Location`, body `{id, platform, subscription_count,
    max_subscriptions, created_at, updated_at}`; `GET /devices/{id}`; `PUT /devices/{id}` (same body:
    token refresh, resets the failure counter); `DELETE /devices/{id}` → 204 (subscriptions cascade).
  - `GET /devices/{id}/subscriptions` (oldest first); `POST /devices/{id}/subscriptions {lat, lng,
    radius_m, categories?, label?, address?}` → 201 + `Location`, body `{id, device_id, label, address,
    lat, lng, radius_m, categories, created_at, updated_at}`; `GET`/`PUT` (replaces everything)/`DELETE`
    `/devices/{id}/subscriptions/{sid}`. Validation (400): lat 46.12–46.38 and lng 5.94–6.32 (canton of
    Geneva bounding box); `radius_m` 100–1000; `categories` a non-empty subset of DEPOSE, AUTORISE,
    CHANTIER, RECOURS, REFUSE (default: all five, stored de-duplicated in that order); `label` ≤ 40,
    `address` ≤ 200 (blank → null). Unknown device or a subscription of another device → 404.
  - **Cap:** `perimetre.subscriptions.max-per-device` (env `SUBSCRIPTIONS_MAX_PER_DEVICE`, default 25).
    Above it `POST …/subscriptions` → **409** `application/problem+json` `{code:
    "SUBSCRIPTION_LIMIT_REACHED", max, title, detail: "Vous avez atteint le maximum de 25 périmètres sur
    cet appareil. Supprimez-en un pour en ajouter un autre."}`. The device row is locked (`FOR UPDATE`)
    while counting, so concurrent creations cannot overshoot.
  - `GET /devices/{id}/events?limit=50` (1–200): events matching any of the device's subscriptions, ONCE
    each, newest first (`occurred_at DESC, id DESC`); `distance_m` from the nearest matching subscription,
    `subscription_ids` the matching ones nearest first. `GET /devices/{id}/subscriptions/{sid}/events`:
    one subscription's (whatever their date). Each item = the `/dossiers` fields (flattened; `groupe` is
    the dossier's current groupe) + `event_id, kind, old_groupe, new_groupe, occurred_at, subscription_ids`.
  - `POST /devices/{id}/test-push` (`DeviceTestPushController`; the app's "Tester les notifications", also
    the support tool): one generic push to the device's token through the active `PushSender` → 200
    `{status: SENT|INVALID_TOKEN|FAILED, fcm_message_id}`; 404 unknown device; at most one per minute per
    device, else **429** `application/problem+json` `{code: "TEST_PUSH_RATE_LIMITED", detail: "Une
    notification de test vient d'être envoyée. Réessayez dans une minute."}` + `Retry-After: 60`. The slot
    is claimed by one conditional `UPDATE … SET last_test_push_at = now() … RETURNING push_token`, so
    concurrent requests cannot both send. Writes nothing else (an invalid token is reported, not counted).
- **Legacy `/subscriptions`** (the single-subscription app build of 2026-10; keep until every installed
  app uses `/devices`, i.e. until the new version has shipped and old installs have updated). Unchanged
  contract: `POST /subscriptions {push_token, platform?, lat, lng, radius_m, categories?, label?}` → 201,
  body `{id, platform, lat, lng, radius_m, categories, label, created_at, updated_at}`; `GET`, `PUT` (same
  body, used for token refresh), `DELETE /subscriptions/{id}`, `GET /subscriptions/{id}/events`. Mapping
  (`LegacySubscriptionController`, `SubscriptionService`): POST creates a device and its subscription
  with the **same id**; the request `label` (the app sends the address) is stored as `address`, and the
  view's `label` is `coalesce(address, label)`; PUT updates the device's token/platform (resets its
  counter) and the subscription's perimeter + address, leaving `label` alone; DELETE deletes the
  subscription and its device once that holds none. The new app adopts a stored legacy
  `subscription_id` as its `device_id` (same UUID).
- **Dev only (`@Profile("dev & !prod")`, so absent in prod):** `POST /admin/dev/emit?id_dossier=X&new_groupe=Y`
  fabricates an event for an existing dossier (`NEW` if Y = DEPOSE, else `GROUPE_CHANGE` from its current groupe; the
  dossier row is not modified) and runs the dispatcher on it, so the push pipeline can be tested
  without waiting for SITG. Returns `{eventId, kind, oldGroupe, newGroupe, notifications, pushes}`
  (camelCase like `/admin/import`); 404 unknown dossier, 400 for a non-notifiable groupe. Example:
  `curl -X POST -G localhost:8090/admin/dev/emit --data-urlencode 'id_dossier=APA 345567/1' -d new_groupe=CHANTIER`

## Vocabularies (shared by backend and app)

`type_dossier` (official SITG `nom_dossier`): APA Autorisation par procédure accélérée · APAT
Autorisation par annonce de travaux · DD Demande définitive · DP Demande préalable · DR Demande de
renseignements · M Démolition · MPA Démolition par procédure accélérée · LER / LER-PA / LER-PAT
Requête en approbation (loi sur les eaux et les routes). Only APA, DD, M, MPA, DP and APAT occur since 2025.

`type_operation` → short app label (the long text is SITG's `operation`, served by `/dossiers`).
About 95% of dossiers have `--`, so the app's main text is the `description` and the label is only a
secondary chip when it isn't "Demande":
| code | short label | SITG `operation` (abridged) |
|------|-------------|-----------------------------|
| VIL | Nouvelle villa | Construction d'une nouvelle villa ou extension importante de villa |
| NOU | Construction nouvelle | Construction nouvelle importante ou extension importante |
| AGR | Agrandissement | Création de surfaces supplémentaires (combles, surélévation, extension) |
| AGV | Agrandissement de villa | Idem, dans une villa |
| REN | Rénovation | Travaux d'entretien et de rénovation, sans surfaces supplémentaires |
| PI | Petite construction | Construction neuve de peu d'importance, domaine privé |
| EQU | Aménagement public | Infrastructures / aménagements du domaine public |
| AFF | Changement d'affectation | Changement d'affectation important |
| `--`, null, unknown | Demande | "Non renseigné" |

Override: `type_dossier` M or MPA → "Démolition", whatever the `type_operation`.

`groupe` → app wording / marker colour: DEPOSE Déposé (orange) · AUTORISE Autorisé (green) ·
CHANTIER En chantier (blue) · RECOURS Recours (purple) · REFUSE Refusé (grey).

## App (`app/`)

Flutter stable, Riverpod 3 (`flutter_riverpod` + `riverpod_annotation`/`riverpod_generator`),
go_router (`StatefulShellRoute` with two tabs: `/` Explorer, `/perimetre` Mes périmètres), dio,
flutter_map with Mapbox raster tiles, url_launcher, firebase_core + firebase_messaging, shared_preferences.
iOS bundle id `ch.perimetre.app` (Firebase iOS app, APNs). The Android `applicationId`/namespace is still
`ch.perimetre.perimetre`. French strings hardcoded, no i18n. Light and dark themes (system setting).

```
lib/main.dart                      loads SharedPreferences + initPush(), ProviderScope overrides (retry disabled)
lib/app.dart                       MaterialApp.router + GoRouter (shell with two branches)
lib/firebase_options.dart          git-ignored: flutterfire output, or a copy of firebase_options.dart.example (see "Firebase")
lib/api/models.dart                AddressSuggestion, Dossier, Groupe, Subscription(Input), Device, DossierEvent + vocabularies
lib/api/geo_admin.dart             geo.admin.ch SearchServer address search
lib/api/perimetre_api.dart         /dossiers, /devices; API_BASE_URL dart-define (release default: prod)
lib/map/map_tiles.dart             Mapbox token (dart-define), base TileLayer (light/dark), MapAttribution
lib/push/push_service.dart         PushService interface (permission, currentToken, refresh, taps) + provider
lib/push/token_sync.dart           TokenSync: keeps the backend's push token current (see "Push token")
lib/push/init_push.dart            Firebase if configured, else StubPushService
lib/push/firebase_push_service.dart   FCM: permission, token, refresh, tapped pushes
lib/push/stub_push_service.dart    fake "dev-token-<uuid>" (kept in prefs), no pushes
lib/features/home/home_shell.dart  NavigationBar, tapped-push handling, token sync + permission refresh on resume
lib/features/explore/...           Explorer tab: providers, screen, shared widgets (address field, radius
                                   slider, map, list sheet, DossierRow, details sheet)
lib/features/subscription/providers.dart   MySubscriptions (device id in prefs), tokenSync, notificationPermission, recentEvents
lib/features/subscription/notifications.dart   permission primer page, "notifications off" notice, test push
lib/features/subscription/subscription_screen.dart   Mes périmètres tab (list, add/edit page, delete, feed)
test/token_sync_test.dart          unit tests of TokenSync (run by CI's `flutter test`)
test/parcelles_test.dart           parcel parsing (missing/null fields) and the details sheet's parcel block (0, 1, 2 parcels)
integration_test/parcelles_test.dart + test_driver/parcelles_screenshots.dart   details sheet with 1/2/0 parcels from the real local API (build/parcel-screenshots)
integration_test/subscription_flow_test.dart   simulator E2E against the local backend
integration_test/screenshots_test.dart + test_driver/screenshots.dart   README screenshots (docs/screenshots)
integration_test/map_tiles_test.dart + test_driver/map_screenshots.dart   base map check (build/map-screenshots)
integration_test/store_screenshots_test.dart + test_driver/store_screenshots.dart   App Store screenshots (docs/app-store)
config/example.json                template of the git-ignored config/dev.json and config/prod.json (Mapbox tokens)
assets/mapbox-logo.png (+ 2.0x/, 3.0x/)   the Mapbox logo, required on the map
```

- **Simulators:** use a dedicated dev simulator and pass its **id** to `-d` (a name can match several
  devices). Never run dev builds on a simulator or device that holds a production subscription.
- **Run on the iOS simulator** against the local backend (start the DB and `./gradlew bootRun` first):
  `cd app && flutter run -d <simulator id> --dart-define-from-file=config/dev.json --dart-define=API_BASE_URL=http://localhost:8090`
  (without `config/dev.json` the map falls back to OSM's public tiles, see "Map tiles")
  (the default base URL is `http://localhost:8090` in debug and profile builds; the simulator reaches
  the Mac's localhost). **Release builds default to `https://api.perimetre.ch`** (`kReleaseMode`); a
  `--dart-define=API_BASE_URL=…` always wins. Simulators run debug builds only, so to try the simulator
  against prod: `flutter run -d <simulator id> --dart-define=API_BASE_URL=https://api.perimetre.ch`.
  Android emulator: `--dart-define=API_BASE_URL=http://10.0.2.2:8090` (untested).
- After changing a `@riverpod` provider: `dart run build_runner build`. Generated `*.g.dart` files are
  kept in the repo.
- **Geocoding:** `SearchServer?searchText=…&type=locations&origins=address&sr=4326`. The API has no
  canton filter and ignores `bbox` for addresses, so the app asks for `limit=50`, keeps results whose
  `attrs.detail` ends in `" ge"`, and shows the first 5. Labels contain `<b>` HTML, which is stripped.
  Debounced 300 ms, minimum 3 characters.
- **Radius slider:** 100–1000 m, step 50, default 200. It refetches on release, not while dragging.
- iOS `Info.plist` sets `NSAllowsLocalNetworking` for plain-http localhost. Android main manifest
  declares INTERNET and an https VIEW `<queries>` entry for url_launcher.
- **Map tiles** (decided 2026-10-26, `docs/map-tiles.md`; swisstopo rejected, OpenFreeMap is the fallback):
  Mapbox Static Tiles API, `mapbox/light-v11` or `dark-v11` following the theme, 512 px tiles @2x
  (`tileDimension: 512`, `zoomOffset: -1`), in `lib/map/map_tiles.dart`. Token: `MAPBOX_ACCESS_TOKEN`
  dart-define from `--dart-define-from-file=config/dev.json` (debug, simulator) or `config/prod.json`
  (release); both git-ignored, never commit a token (gitleaks rule `mapbox-access-token` in
  `.gitleaks.toml`). The Runner build phase "Check Mapbox token" fails a **Release** build without a `pk.`
  token in `DART_DEFINES`. Debug/profile builds without a token use `tile.openstreetmap.org`
  (development only, OSM tile policy). **Attribution** (`MapAttribution`, required by Mapbox, keep it
  visible): the Mapbox logo top left, "© Mapbox", "© OpenStreetMap", "Improve this map" (feedback URL of
  the current view) top right, because the list sheet covers the bottom. The privacy page names Mapbox
  as the tile provider.
- **Details sheet title:** when the short label is "Demande", the description is the title and the
  "Demande" heading is dropped (the description is then not repeated below).
- **Parcels** (details sheet only, never in rows or feeds; backend side: "Parcels", "Parcel context"): `Dossier.parcelles`
  (`Parcelle`: numero, commune, surfaceM2, egrid, lienRf, and since 1.1 `type`, `planRf`, `lieuDit`, `nbBatiments`,
  `batiments` (`Batiment`: numero, destination, surfaceM2, surfaceSurParcelleM2, anneeConstruction, adresses),
  `restrictions` (`Restriction`: type, nom, description, partPct...)) and `lienRegistreFoncier` are parsed null-safely
  (missing, null, empty or malformed = absent; an older backend sends none of the 1.1 fields; a malformed entry is
  dropped). Code in `lib/features/explore/parcelles_section.dart`. `ParcellesSection` is hidden when there are none;
  else "Parcelle"/"Parcelles" (caption "Le dossier se situe sur ou à côté de ces parcelles." from 2 on) and one
  `ParcelleCard` per parcel: "Parcelle n° N · commune", "surface (`1'234 m²`) · category" (`parcelleTypeLabel`: privé →
  Privé, DP communal → Domaine public communal...), "Plan RF n · Lieu-dit x", the EGRID (tap or long-press copies it),
  then two **collapsed** `ExpansionTile`s shown only when they have data: "Bâtiments (n)" (number · destination, "Sur la
  parcelle : x m² (total y m²)", year, addresses, "et n autres bâtiments" when the backend capped the list) and
  "Zone et restrictions" (name, "Zone d'affectation|de développement · 98 % de la parcelle", SITG's description).
  Last, **"Voir au registre foncier"** with "Site officiel de l'État de Genève : propriétaire et extrait officiel.": it
  opens `lien_rf`, else `lien_registre_foncier`, else the constant `registreFoncierUrl`, with
  `LaunchMode.inAppBrowserView` (SFSafariViewController, the user stays in the app; `openInAppBrowser`). No webview,
  nothing fetched from ge.ch by the app, nothing beyond the API's fields. The sheet scrolls. Real-data check on a dev
  simulator, with the local backend: `SIMULATOR_ID=<id> flutter drive --driver=test_driver/parcelles_screenshots.dart
  --target=integration_test/parcelles_test.dart -d <id>` (screenshots via `simctl` in `build/parcel-screenshots/`, it also
  opens and closes the Safari sheet with `closeInAppWebView()`; **delete the Safari screenshots afterwards**: the ge.ch
  extract page shows private individuals' names).
- **Details sheet navigation** (`showDossierDetails`, `_DetailsHeader` in `widgets.dart`; reached from the Explorer list and map,
  the "Récent" feed and a tapped push): a modal bottom sheet on the **root navigator** (`useRootNavigator`: on the shell's own
  navigator it was clipped above the bottom bar), `useSafeArea` and a height cap of 92% (a strip of scrim stays tappable). A pinned
  header (never scrolls) holds the drag handle, "Détail du dossier" and a **"Fermer"** button (≥ 44 × 48 pt). Leaving: Fermer,
  swipe down on the header, tap on the scrim, Android's back (the route pops). The home-indicator inset is scroll padding. Build 6
  (1.1.0) had only the drag handle, under the status bar on a tall sheet: no visible way out. Regression tests:
  `test/details_navigation_test.dart` (fails on the old sheet) and `integration_test/navigation_test.dart` (real taps in the
  real app, screenshot per step; run it with `test_driver/navigation_screenshots.dart`, see its header; dev simulators only).
  The ge.ch page opened by the registry button is Apple's SFSafariViewController: its own close button (top left) and bar are
  native. **Never screenshot it**: the extract lists owners' names.
- **SITG attribution** ("Source : SITG – Système d'information du territoire à Genève") ends the details
  sheet: the SITG open-data terms make the source mandatory. Keep it.
- **Mes périmètres:** without a subscription, the tab is the form: optional name (`ChoiceChip`s
  Domicile / Travail fill it, free text ≤ 40), address field + suggestions, radius slider, five category
  chips, "Activer les notifications" (the explore address/radius pre-fill it). With subscriptions: one
  card each (name = label, else the street part of the address; address; radius and categories; tap or
  ⋮ → "Modifier" / "Supprimer" with a confirm), "Ajouter un périmètre" (full-screen form page,
  "Nouveau périmètre" / "Modifier le périmètre"), and the "Récent" feed from `/devices/{id}/events`
  (`DossierRow` with event text + date as the leading line, plus the matched perimeter names when there
  are several; marker in the event's colour), pull to refresh, and "Tester les notifications" (below
  "Ajouter un périmètre": syncs the token, then `POST /devices/{id}/test-push`; with notifications off it
  says so instead of sending). Saving waits (≤ 30 s) for a usable token
  (`TokenSync.tokenForRegistration`, else "Les notifications ne sont pas encore prêtes…"), PUTs it to the
  device (or POSTs `/devices` when there is none or the backend forgot it) and stores the device id in
  shared_preferences (`device_id`). A 409 shows the backend's French message. A 404 on load forgets the
  stored device id (the backend deletes devices after repeated invalid tokens). Deleting the last
  perimeter keeps the device. A yellow banner shows in stub mode.
- **Notification permission:** asked right after a new perimeter is saved (the token does not need it),
  only while it is not determined: an explanation page ("Recevoir les alertes", "Continuer"), then the iOS
  prompt; closing the page skips the prompt. While it is denied, every perimeter card says "Notifications
  désactivées : aucune alerte n'arrivera." with "Ouvrir les Réglages" (`app-settings:`); not determined:
  "Autoriser" (page + prompt). Re-read on every resume. SnackBars with an action set `persist: false`
  (otherwise they stay until dismissed and queue every later one).
- **Upgrade from the single-subscription build:** a stored `subscription_id` (legacy key) becomes the
  `device_id` (the backend gave that subscription a device with the same id), then the key is removed.
- **Tapped push:** switches to Mes périmètres and refreshes the feed; with `data.id_dossier`, looks the
  dossier up via `/dossiers` around `data.subscription_id`'s point (else the first subscription) and
  opens its details sheet, falling back to the device feed (`/dossiers` is capped at 200 and hides
  TERMINE), else a SnackBar "Ce dossier n'est plus disponible.". Summary and test pushes only open the tab.
- **Push token** (`TokenSync`, started by the home shell): at start, on every resume and on every
  `onTokenRefresh`, `PUT /devices/{id}` with the current token when it differs from the last one the
  backend acknowledged (`push_token_sent` in shared_preferences); retries after 2, 5, 15, then every 60 s
  while there is no token or the backend is unreachable. A token only comes from
  `PushService.currentToken`, which on iOS returns null until `getAPNSToken()` is set: Firebase iOS SDK 13
  hands out an FCM token before APNs has delivered the device token (it only logs "APNS device token not set
  before retrieving FCM Token"), FCM accepts pushes to that token and never delivers them, and once APNs
  arrives the SDK replaces it, announced only through `onTokenRefresh`. The 1.0.0 (1) build sent such a
  token on a first install (a bounded 10 s APNs wait, then `getToken()` anyway; refreshes heard only
  while the Mes périmètres provider was alive and with a device id; no re-check at start), so a device
  could keep a dead token for good. Refresh events are only a hint: their token is never sent as is.
- **iOS push setup in the repo:** `Runner/Runner.entitlements` (`aps-environment = development`, Debug
  and Profile) and `Runner/RunnerRelease.entitlements` (`production`, Release), via `CODE_SIGN_ENTITLEMENTS`;
  `UIBackgroundModes` `remote-notification` in `Info.plist`.
- **Release / TestFlight** (checklist and store texts: `docs/app-store.md`): version from `pubspec.yaml`
  (`1.1.0+7`; bump the build number for every upload), iPhone only, `CFBundleDevelopmentRegion fr`,
  `ITSAppUsesNonExemptEncryption NO`, `Runner/PrivacyInfo.xcprivacy` (precise location, other user content,
  device ID: app functionality, linked, no tracking; UserDefaults CA92.1). It must stay in line with the App
  Store privacy answers and `deploy/site/privacy.html`. No usage strings (no device location; iOS has
  none for notifications). Icon (dashed orange ring and pin on #161412): source `app/tool/app_icon.svg`; `app/tool/make_icons.sh`
  renders every iOS size (no alpha), the launch image (160 pt, on the storyboard's #161412), the Android
  launcher icons and the site's `apple-touch-icon.png` / `favicon.ico` (`deploy/site/favicon.svg` is the same
  mark with rounded corners, also inline in `index.html`). Needs `rsvg-convert` (librsvg).
  `flutter build ipa --release` archives and exports, signed Apple Distribution (verified 2026-10-26;
  Xcode created the managed store profile). Published on the App Store:
  https://apps.apple.com/ch/app/p%C3%A9rim%C3%A8tre/id6828071722. The site links to it with Apple's French black badge
  (`deploy/site/app-store-badge-fr.svg`, as downloaded from Apple's marketing tools: never edit it) and
  announces it with the Smart App Banner (`<meta name="apple-itunes-app" content="app-id=6828071722">`).

### Firebase

The Firebase client config is per deployment and **git-ignored**: `lib/firebase_options.dart`,
`ios/Runner/GoogleService-Info.plist` and `firebase.json` (each has a committed `*.example`). Without
Firebase, copy the examples: `DefaultFirebaseOptions.currentPlatform` then throws `UnsupportedError`,
`initPush()` catches it and uses `StubPushService` (fake token `dev-token-<uuid>`), which works against a
backend running `LogPushSender`. The Xcode project bundles `GoogleService-Info.plist`, so the file must
exist (the placeholder is enough). If `Firebase.initializeApp` fails, the app also falls back to the stub
(logged). With `flutterfire configure` output, iOS uses `FirebasePushService` and real FCM tokens
(verified on the simulator: a real FCM push from `/admin/dev/emit` was delivered). Android has no
Firebase app yet.

Setup (once per Firebase project; Android still needs steps 1 and 3):
1. Firebase console: create a project; add an iOS app with bundle id `ch.perimetre.app` (later an
   Android app; its `applicationId` is still `ch.perimetre.perimetre`).
2. Apple Developer → Keys: create an APNs key (.p8), note Key ID and Team ID. Firebase console →
   Project settings → Cloud Messaging → Apple app configuration: upload it. Never commit the .p8.
3. `dart pub global activate flutterfire_cli`, then in `app/`: `flutterfire configure
   --project=<project-id> --platforms=ios,android --ios-bundle-id=ch.perimetre.app
   --android-package-name=ch.perimetre.perimetre --overwrite-firebase-options`. The generated files stay
   untracked.
4. Project settings → Service accounts → Generate new private key; save it as
   `backend/.secrets/firebase-service-account.json` (`backend/.secrets/` is git-ignored).
5. Start the backend with `FIREBASE_CREDENTIALS=.secrets/firebase-service-account.json ./gradlew bootRun`
   (relative to `backend/`, or an absolute path). Startup logs "Sending pushes through FCM…".
6. Real pushes need a physical iPhone (or a simulator with APNs support) and the Push Notifications
   capability on the App ID, which automatic signing adds from the entitlements file.

## Tests

- Unit: `SitgMappingTest` (statut → groupe, flags), `ChangeDetectorTest` (event rules incl.
  bootstrap), `SitgClientRetryTest` (reset, timeout, truncated body, 5xx and ArcGIS errors against a raw
  local HTTP server), `LatePublicationTest` (configured crons + cursor: a batch published up to 2 days
  late is still fetched, every day of 2026; full ≥ 10 min after the incremental), `PushPlannerTest` (≤ 3 / > 3 summary rule counted in events, one push per event and
  device), `PushMessagesTest` (payload wording, label vocabulary, truncation, place names, summary per
  place), `AdminTokenFilterTest` (bearer check, prod requires a ≥ 32-char token),
  `HealthcheckPingerTest` (no-op when unset, ping URLs, a failing ping never throws),
  `ScheduledZoneTest` (every cron `@Scheduled` names Europe/Zurich).
- Context tests need the local DB running and apply pending Flyway migrations to it:
  `BackendApplicationTests`, and `PushPipelineIntegrationTest` (profile `dev`,
  `FIREBASE_CREDENTIALS` forced empty, `@Transactional` so everything is rolled back): subscription →
  `/admin/dev/emit` → notification SENT through `LogPushSender` (log captured) → `/events` feed;
  category filtering; validation; all through the legacy `/subscriptions` API. `DeviceApiIntegrationTest`
  (cap lowered to 3): device + subscription CRUD, 409 with code and French message, validation, legacy
  API mapped onto devices (same id, token refresh, label vs address, delete), one push per event and
  device naming both perimeters + device feed, test push (sent, 429 + `Retry-After` within a minute,
  sent again after it, 404). `ImportJobRunTest` (SitgImporter and the pinger
  mocked): `job_run` row on success and FAILED on exception, STALE_SOURCE + `/fail` ping on a source
  older than 4 days or an empty fetch, `/admin/job-runs` newest first with `max_date_maj_sitg`.
  `SourceFreshnessTest` (unit): the 4-day boundary (a 3-day stamp gap is not stale), newest date of a run.
- Parcels: `ParcelleLinkIntegrationTest` (spatial join on two adjacent test parcels in the lake, rolled back:
  inside / on the shared edge / 2 cm / 23 cm / 13 m away, relink replaces stale links, a changed or moved
  parcel relinks its dossiers, self-heal, cascades, served fields), `DossierParcellesApiIntegrationTest`
  (`parcelles` + `lien_registre_foncier` on `/dossiers` and the device feed, `[]` not null, existing fields
  unchanged, no owner-like column or text), `ParcelleClientTest` (local HTTP server: paging, the query sent
  incl. no owner fields, DATEDT cursor, 500 retried), `ParcelleImporterRulesTest` (first run is full, the 10%
  shrink guard, cursor at midnight).
- Parcel context: `ContexteIntegrationTest` (test parcels in the lake, rolled back: a building on two parcels gets its
  footprint in each, only the parcels of its official list, unchanged rows not reported, relink of a changed parcel,
  zone sliver/edge rules, a self-intersecting parcel, the 30-building cap with the real count, empty details),
  `ArcgisLayerClientTest` (fields sent, no geometry when not wanted, paging, 500 retried, ArcGIS error not retried),
  `ContexteMappingTest` (row mapping with missing/placeholder values, no name/owner fields requested, mode and 90% rules),
  and the extended `DossierParcellesApiIntegrationTest` (new fields served, empty lists not nulls, old fields unchanged).
- `MatchingRadiusIntegrationTest`: a subscription at the maximum radius matches an event 1 m inside it (and
  not one 1 m outside); the DB CHECK has the same bounds as `SubscriptionInput`; the API refuses max + 1.
- `V6MigrationTest`: creates a throwaway database `perimetre_v6_migration_test` on the same server,
  migrates it to V5, seeds prod-like rows, migrates to the latest and checks ids, tokens, counters,
  label → address, notifications and cascades (dropped afterwards). A manual rehearsal on a copy of the
  local V5 DB (2026-10-26) matched too.
- **On demand:** `PERIMETRE_PERF=1 ./gradlew test --tests '*DispatchPerformanceTest' -i` seeds 10k
  subscriptions on 4k devices + 200 events (needs imported dossiers), prints the plan of the real
  matching query (`NotificationRepository.MATCHING`; must show `subscription_geog_gist`) and the dispatch
  time, rolls back. 2026-10-26 on the Mac: matching 121–155 ms; dispatch 2.3–2.4 s for ~29.5k
  notifications → ~4.3k log pushes to ~3.3k devices. With real FCM
  the sends are sequential (one HTTP call each), which dominates at that scale.
- Map tiles: `cd app && flutter drive --driver=test_driver/map_screenshots.dart
  --target=integration_test/map_tiles_test.dart --dart-define-from-file=config/dev.json -d <simulator id>`
  (Geneva centre, Annemasse, Ferney-Voltaire at zoom 17, light + dark; fails on a tile error; screenshots
  in `app/build/map-screenshots/`).
- README screenshots (local backend, dev profile; fabricates 4 events): `cd app && flutter drive
  --driver=test_driver/screenshots.dart --target=integration_test/screenshots_test.dart
  --dart-define-from-file=config/dev.json -d <simulator id>`,
  then `sips --resampleWidth 600 ../docs/screenshots/*.png`.
- App Store screenshots (6.9", 1320 × 2868, light, status bar 9:41, no alpha; real events: full import first):
  `cd app && SIMULATOR_ID=<id> flutter drive --driver=test_driver/store_screenshots.dart
  --target=integration_test/store_screenshots_test.dart --dart-define-from-file=config/dev.json -d <id>`
  (the driver serves `localhost:8765/shot` and takes each picture with `simctl io`); see `docs/app-store.md`.
- App: `flutter analyze`; `flutter test` (`test/token_sync_test.dart`: one PUT per token change, nothing
  before APNs, retries, 404, registration wait); `integration_test/subscription_flow_test.dart` drives the real app on a
  simulator against the local backend (two perimeters, simulated push tap → details sheet, unknown
  dossier message, delete both; permission page → refused → notice on the card, test push refused while
  off, then sent and rate-limited after a resume with permission; adoption of a legacy subscription id):
  `cd app && flutter test integration_test/subscription_flow_test.dart -d <simulator id>`.
  Android is not built locally (no Android SDK on the development Mac); firebase_messaging 17 needs minSdk 24,
  which is Flutter 3.47's default (`flutter.minSdkVersion` in `android/app`).

## Deployment (self-hosting)

The reference deployment is one small VPS running `deploy/docker-compose.prod.yml`; the operator's
host, paths, credentials handling and runbooks are in `CLAUDE.local.md` (git-ignored, operator's
machine only). Read it when it exists; never copy its host details back into this file.

- **Stack** (compose project `perimetre`): `caddy` (the only published ports, 80/443; Let's Encrypt
  certificates in the `caddy_data` volume), `backend` (registry image, no host port, `TZ=Europe/Zurich`,
  `mem_limit` 1.5 GB, `-XX:MaxRAMPercentage=75`), `db` (`imresamu/postgis:17-3.5`, volume `db_data`, no
  host port, `TZ` + `log_timezone` Europe/Zurich, session `timezone` UTC, tuned for 4 GB RAM). Only Caddy
  publishes ports: Docker's published ports bypass ufw, so `db` and `backend` must never get `ports:`.
  Every `@Scheduled` cron carries `zone = "Europe/Zurich"`; the JVM's `TZ` only affects log timestamps.
  No Redis in prod. Every image is pinned by tag + digest, every log capped at 5 × 10 MB. Caddy:
  `api.<domain>` → `backend:8090`; the apex → `deploy/site/` (`index.html`, `privacy.html`, also at
  `/privacy`); `www` → apex. The backend trusts `X-Forwarded-*` (`server.forward-headers-strategy:
  framework` in `application-prod.yml`) because only Caddy can reach it.
- **Server layout:** `deploy/bootstrap.sh` (idempotent: firewall, SSH hardening, fail2ban,
  unattended-upgrades, Docker, the app directory, the backup cron) prepares an Ubuntu host;
  `deploy/ship.sh <short-sha>` syncs `deploy/` to the app directory, saves the backend log, pulls that
  image, tags it `backend:deployed` on the box (the compose default, so a hand-run `docker compose up` never
  falls back to `:latest`), `up -d`, waits for health; `deploy/ship.sh --sync` only syncs the files and
  reloads Caddy (site, Caddyfile, backup scripts) and never pulls or touches the backend. `.env.prod` (template `deploy/.env.prod.example`) and `secrets/` (Firebase
  service account) exist only on the server.
- **Profile `prod`** (`application-prod.yml`): datasource from `DB_URL`/`DB_USER`/`DB_PASSWORD` with
  no defaults (unset = startup fails), Hikari pool 5, actuator exposes `health` only with
  `show-details: never` and no discovery page, scheduler on, `/admin/dev/**` absent, `/admin/**`
  behind `ADMIN_TOKEN` (see "API"). Optional `HEALTHCHECK_PING_URL` (see "Scheduling").
- **Secrets scan:** `.gitleaks.toml` = gitleaks' default rules + `mapbox-access-token` (any `pk.`/`sk.`/`tk.`
  token, with or without the word "mapbox" nearby); `.gitleaksignore` lists reviewed false positives.
  `.githooks/pre-commit` runs it on staged changes once enabled per clone (`git config core.hooksPath .githooks`).
- **CI** (`.gitlab-ci.yml`, GitLab shared runners): merge requests and main run `backend-test`
  (`./gradlew test` against a PostGIS service, `DB_URL` pointed at it), `app-analyze` (Flutter 3.47.7
  checked out in the job, the Firebase placeholders copied in, `flutter analyze`, `flutter test` when
  `app/test` exists) and `secrets-scan` (gitleaks over the branch history; reviewed false positives in
  `.gitleaksignore`). Merge only with a green MR pipeline.
- **Remotes:** GitLab (`gitlab.com/daniellaera1/perimetre`, private) is the source of truth: CI, registry,
  MRs, deploys. `github.com/daniellaera/perimetre` is its public mirror (same history, no CI). In the
  operator's clone, `origin` fetches from GitLab and has two push URLs, GitLab first, so a plain
  `git push` (main, tags) updates both. `gitlab` and `github` reach one side each. Set up a clone like this:
  ```bash
  git remote set-url --add --push origin git@gitlab.com:daniellaera1/perimetre.git
  git remote set-url --add --push origin git@github.com:daniellaera/perimetre.git
  git remote add gitlab git@gitlab.com:daniellaera1/perimetre.git
  git remote add github git@github.com:daniellaera/perimetre.git
  ```
  Whatever goes through `origin` is public at once. Feature/MR branches go to GitLab only:
  `git push -u gitlab <branch>` (GitLab deletes them on merge; the mirror only carries main). The pre-commit
  gitleaks hook is the last check before anything reaches GitHub. A history rewrite on main must be
  force-pushed to both.
- **Images:** after the tests, every push to main builds `backend/Dockerfile` and pushes
  `registry.gitlab.com/daniellaera1/perimetre/backend:<short-sha>` and `:latest`, labelled with
  `org.opencontainers.image.revision`. CI never deploys.
- **Deploy:** tests green → merge to main → pipeline green → `deploy/ship.sh <short-sha>`
  (always an explicit CI tag, never `:latest`; also how to roll back; `--no-pull <short-sha>` uses the image
  already on the server). Files only (site, Caddyfile, backup scripts): `deploy/ship.sh --sync`. A migration
  that rewrites data (e.g. V6): run the backup script on the server first; rolling back past it means
  restoring that dump.
- **Backups:** nightly cron runs `deploy/backup-offsite.sh`: `deploy/backup.sh` (`pg_dump -Fc` inside the db
  container, checked with `pg_restore --list`, 14 days kept locally), then restic sends that dump to S3 as
  `/perimetre.dump` (tag `pgdump`), `forget --prune` keeps 7 daily, 4 weekly, 6 monthly, `restic check`; its own
  healthchecks.io check (`BACKUP_PING_URL`: `/start`, success, `/fail` + log tail). Credentials in
  `.restic.env` (600, server only), written interactively by `deploy/set-restic-creds.sh` (prints the
  generated repository password once; a re-run keeps it). `deploy/restic-restore-test.sh` restores a snapshot
  into a throwaway `--network none` PostGIS container and compares row counts with prod. The privacy page
  states the retention (6 months): keep them in line. Restore from the local dump:
  `docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < backups/<file>.dump`.
- **Never** `docker compose down -v` on the server (deletes the database and Caddy's certificates),
  never copy `.env.prod` off the server or overwrite it from elsewhere, never print the values in it or
  in `secrets/`.
