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

## Data model (Flyway V1–V7)

- `dossier`: one row per SITG dossier (see "SITG import"); `dossier_geog_gist` on `(geom::geography)`.
- `sync_state(source PK, last_run, bootstrapped)`: one row, `SIT_AUTOR_DOSSIER`.
- `device(id UUID PK, push_token, platform ios|android|null, consecutive_failures, created_at,
  updated_at)` (V6): one app installation. `consecutive_failures` counts runs whose push failed with an
  invalid token (see "Notifications").
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
- **Payload** (`PushMessages`, French, never any names; only SITG's work description):
  - title `"{label} à {distance} m"`: label per the vocabulary below (M/MPA → Démolition); when it is
    "Demande", the first 40 chars of the description (trimmed, first letter capitalised, "…" when
    cut). Distance rounded to the metre, from the nearest matching subscription point. Then
    `" · {places}"` (≤ 40 chars) naming the matched subscriptions, nearest first (`placeName`): the
    label; else, when the device has ≥ 2 subscriptions, the address without postcode and locality; else
    nothing (a single unlabelled subscription, i.e. every legacy one, gets the original title).
  - body `"{event text}. {description, max 90 chars + …}"` (just `"{event text}."` without
    description). Event text by `new_groupe`: DEPOSE "Nouvelle demande déposée — délai d'opposition en
    cours" · AUTORISE "Autorisation délivrée" · CHANTIER "Les travaux commencent" · RECOURS "Un recours a
    été déposé" · REFUSE "Le projet ne se fera pas".
  - data `{id_dossier, kind, new_groupe, subscription_id}` (nearest match). Summary: title "Périmètre",
    body "N nouveaux changements autour de chez vous" (N = events), or with named places
    "N nouveaux changements (Domicile : 3, Travail : 2)", data `{kind: SUMMARY, count}`. iOS gets
    `aps.sound = default`.
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
  radius. The way to check FCM on prod without waiting for 03:15.
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
  `operation` and `description` are SITG's raw texts. `operation` is "Non renseigné (valeur par
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
lib/push/push_service.dart         PushService interface + provider (overridden in main)
lib/push/init_push.dart            Firebase if configured, else StubPushService
lib/push/firebase_push_service.dart   FCM: permission, token, refresh, tapped pushes
lib/push/stub_push_service.dart    fake "dev-token-<uuid>" (kept in prefs), no pushes
lib/features/home/home_shell.dart  NavigationBar + tapped-push handling
lib/features/explore/...           Explorer tab: providers, screen, shared widgets (address field, radius
                                   slider, map, list sheet, DossierRow, details sheet)
lib/features/subscription/providers.dart   MySubscriptions (device id in prefs, token refresh → PUT), recentEvents
lib/features/subscription/subscription_screen.dart   Mes périmètres tab (list, add/edit page, delete, feed)
integration_test/subscription_flow_test.dart   simulator E2E against the local backend
integration_test/screenshots_test.dart + test_driver/screenshots.dart   README screenshots (docs/screenshots)
integration_test/map_tiles_test.dart + test_driver/map_screenshots.dart   base map check (build/map-screenshots)
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
- **SITG attribution** ("Source : SITG – Système d'information du territoire à Genève") ends the details
  sheet: the SITG open-data terms make the source mandatory. Keep it.
- **Mes périmètres:** without a subscription, the tab is the form: optional name (`ChoiceChip`s
  Domicile / Travail fill it, free text ≤ 40), address field + suggestions, radius slider, five category
  chips, "Activer les notifications" (the explore address/radius pre-fill it). With subscriptions: one
  card each (name = label, else the street part of the address; address; radius and categories; tap or
  ⋮ → "Modifier" / "Supprimer" with a confirm), "Ajouter un périmètre" (full-screen form page,
  "Nouveau périmètre" / "Modifier le périmètre"), and the "Récent" feed from `/devices/{id}/events`
  (`DossierRow` with event text + date as the leading line, plus the matched perimeter names when there
  are several; marker in the event's colour), pull to refresh. Saving asks for notification
  permission, gets the token, PUTs it to the device (or POSTs `/devices` when there is none or the
  backend forgot it) and stores the device id in shared_preferences (`device_id`). A 409 shows the
  backend's French message. A 404 on load forgets the stored device id (the backend deletes devices
  after repeated invalid tokens). Deleting the last perimeter keeps the device. A yellow banner shows in
  stub mode.
- **Upgrade from the single-subscription build:** a stored `subscription_id` (legacy key) becomes the
  `device_id` (the backend gave that subscription a device with the same id), then the key is removed.
- **Tapped push:** switches to Mes périmètres and refreshes the feed; with `data.id_dossier`, looks the
  dossier up via `/dossiers` around `data.subscription_id`'s point (else the first subscription) and
  opens its details sheet, falling back to the device feed (`/dossiers` is capped at 200 and hides
  TERMINE), else a SnackBar "Ce dossier n'est plus disponible.". Summary pushes only open the tab.
- **Token refresh:** `onTokenRefresh` → `PUT /devices/{id}` with the new token.
- **iOS push setup in the repo:** `Runner/Runner.entitlements` (`aps-environment = development`, Debug
  and Profile) and `Runner/RunnerRelease.entitlements` (`production`, Release), via `CODE_SIGN_ENTITLEMENTS`;
  `UIBackgroundModes` `remote-notification` in `Info.plist`.
- **Release / TestFlight** (checklist and store texts: `docs/app-store.md`): version from `pubspec.yaml`
  (`1.0.0+1`; bump the build number for every upload), iPhone only, `CFBundleDevelopmentRegion fr`,
  `ITSAppUsesNonExemptEncryption NO`, `Runner/PrivacyInfo.xcprivacy` (precise location, other user content,
  device ID: app functionality, linked, no tracking; UserDefaults CA92.1). It must stay in line with the App
  Store privacy answers and `deploy/site/privacy.html`. No usage strings (no device location; iOS has
  none for notifications). Icon and launch image are a placeholder from `app/tool/make_icon.swift`.
  `flutter build ipa --release` archives and exports, signed Apple Distribution (verified 2026-10-26;
  Xcode created the managed store profile). Nothing uploaded yet.

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
  device naming both perimeters + device feed. `ImportJobRunTest` (SitgImporter and the pinger
  mocked): `job_run` row on success and FAILED on exception, STALE_SOURCE + `/fail` ping on a source
  older than 4 days or an empty fetch, `/admin/job-runs` newest first with `max_date_maj_sitg`.
  `SourceFreshnessTest` (unit): the 4-day boundary (a 3-day stamp gap is not stale), newest date of a run.
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
- App: `flutter analyze`; `integration_test/subscription_flow_test.dart` drives the real app on a
  simulator against the local backend (two perimeters, simulated push tap → details sheet, unknown
  dossier message, delete both; adoption of a legacy subscription id):
  `cd app && flutter test integration_test/subscription_flow_test.dart -d <simulator id>`.
  Android is not built locally (no Android SDK on this Mac); firebase_messaging 17 needs minSdk 24,
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
  `deploy/ship.sh` syncs `deploy/` to the app directory, saves the backend log, pulls the image,
  `up -d`, waits for health. `.env.prod` (template `deploy/.env.prod.example`) and `secrets/` (Firebase
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
- **Images:** after the tests, every push to main builds `backend/Dockerfile` and pushes
  `registry.gitlab.com/daniellaera1/perimetre/backend:<short-sha>` and `:latest`, labelled with
  `org.opencontainers.image.revision`. CI never deploys.
- **Deploy:** tests green → merge to main → pipeline green → `deploy/ship.sh` (or `deploy/ship.sh
  <short-sha>` to pin or roll back; `--no-pull` uses the image already on the server). A migration
  that rewrites data (e.g. V6): run the backup script on the server first; rolling back past it means
  restoring that dump.
- **Backups:** `deploy/backup.sh`, nightly from cron: `pg_dump -Fc` inside the db container, checked with
  `pg_restore --list`, 14 days kept. Restore:
  `docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < backups/<file>.dump`.
- **Never** `docker compose down -v` on the server (deletes the database and Caddy's certificates),
  never copy `.env.prod` off the server or overwrite it from elsewhere, never print the values in it or
  in `secrets/`.
