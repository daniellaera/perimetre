# Périmètre

Geneva civic app: users register an address + radius and get a push notification when a
building-permit dossier (SITG open data layer `SIT_AUTOR_DOSSIER`) appears or changes status
within that radius.

- `backend/`: Spring Boot 4, Java 21, Gradle (Groovy DSL), base package `ch.perimetre`
- `app/`: Flutter, iOS first (Android must compile), one screen for now (see "App" below)

## Local environment

- `docker compose up -d` starts PostGIS 17 (`perimetre-db`, perimetre/perimetre@localhost:5432,
  db `perimetre`) and Redis 7 (`perimetre-redis`, localhost:6379).
- `cd backend && ./gradlew bootRun`; health at `GET /actuator/health`.
- Reset the DB: `docker compose down -v && docker compose up -d`.

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

- **Query:** `where = date_maj_2 >= TIMESTAMP '<cursor>' AND date_depot IS NOT NULL`, with
  `cursor = sync_state.last_run - 3 days` (source `SIT_AUTOR_DOSSIER`), formatted
  `yyyy-MM-dd HH:mm:ss` in UTC. The 3-day overlap is deliberate: `date_maj_2` is date-only and lags
  publication by ~1 day, so a 1-day overlap can miss dossiers stamped "yesterday" that are published
  after a run. Re-fetching ~1–3k rows per run is cheap and the upsert is idempotent.
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
- **Write:** upsert by `id_dossier`, JDBC batches of 500. On conflict update all mutable columns and
  `last_seen = now()`; `first_seen` is never touched.
- **Cursor:** all pages are fetched first, then upsert and `sync_state.last_run = now()` run in one
  transaction. On any failure nothing is written, the cursor stays untouched, and the error
  propagates (HTTP 500 + ERROR log).
- **HTTP:** `RestClient`, 60 s connect/read timeout, one retry on HTTP 5xx (SITG answers bad queries
  with a 500 HTML maintenance page). ArcGIS `{"error":…}` bodies with HTTP 200 fail the import.
  One INFO log line per page.
- **Data facts (2026-10):** ~20.8k dossiers modified since 2025-01-01, ~1k/day. `date_maj_2` is
  date-only (midnight UTC) and lags about one day behind publication.

## API

- `POST /admin/import`: runs the import synchronously and returns
  `{fetched, inserted, updated, durationMs}`. **No auth yet; must be secured before deployment.**
- `GET /dossiers?lat=&lng=&radius=`: radius in metres (1–2000). Dossiers within the radius
  (`ST_DWithin`/`ST_Distance` on geography), excluding groupes `IGNORE` and `TERMINE` (both stay in
  the table), ordered by `date_maj_sitg DESC, distance_m ASC, id_dossier ASC` (deterministic, since
  `date_maj_sitg` is day-granular), limit 200. Fields: `id_dossier, type_dossier, type_operation,
  operation, description, groupe, date_depot, date_maj_sitg, flags, lien_sad, lat, lng, distance_m`.
  `operation` and `description` are SITG's raw texts. `operation` is "Non renseigné (valeur par
  défaut…)" when `type_operation` is `--`. `description` may have trailing whitespace.
  Served by the expression index `dossier_geog_gist` on `(geom::geography)` (V2). Keep the predicate
  written as `ST_DWithin(geom::geography, …)` or the planner falls back to a seq scan.

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
go_router (single route `/`), dio, flutter_map with OSM tiles, url_launcher. Bundle/application
id `ch.perimetre.perimetre` (also the OSM `userAgentPackageName`). French strings hardcoded, no i18n.

```
lib/main.dart                      ProviderScope (automatic provider retry disabled)
lib/app.dart                       MaterialApp.router + GoRouter
lib/api/models.dart                AddressSuggestion, Dossier, Groupe + label vocabularies
lib/api/geo_admin.dart             geo.admin.ch SearchServer address search
lib/api/perimetre_api.dart         GET /dossiers; API_BASE_URL dart-define
lib/features/explore/providers.dart     (+ providers.g.dart) selected address, radius, dossiers
lib/features/explore/explore_screen.dart   the screen layout
lib/features/explore/widgets.dart  address field, radius slider, map, list sheet, details sheet
```

- **Run on the iOS simulator** against the local backend (start the DB and `./gradlew bootRun` first):
  `cd app && flutter run -d "iPhone 17 Pro" --dart-define=API_BASE_URL=http://localhost:8080`
  (the default base URL is `http://localhost:8080` already; the simulator reaches the Mac's localhost).
  Android emulator: `--dart-define=API_BASE_URL=http://10.0.2.2:8080` (untested).
- After changing a `@riverpod` provider: `dart run build_runner build`. Generated `*.g.dart` files are
  kept in the repo.
- **Geocoding:** `SearchServer?searchText=…&type=locations&origins=address&sr=4326`. The API has no
  canton filter and ignores `bbox` for addresses, so the app asks for `limit=50`, keeps results whose
  `attrs.detail` ends in `" ge"`, and shows the first 5. Labels contain `<b>` HTML, which is stripped.
  Debounced 300 ms, minimum 3 characters.
- **Radius slider:** 100–1000 m, step 50, default 200. It refetches on release, not while dragging.
- iOS `Info.plist` sets `NSAllowsLocalNetworking` for plain-http localhost. Android main manifest
  declares INTERNET and an https VIEW `<queries>` entry for url_launcher.
- OSM's public tile servers are fine for development only (tile usage policy). Switch to a proper
  tile provider before release.

## Tests

Unit tests cover only the `statut → groupe` mapping and flag extraction (`SitgMappingTest`).
`BackendApplicationTests` needs the local DB running, and it applies pending Flyway migrations to
that same local DB.
