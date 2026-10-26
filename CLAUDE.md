# Périmètre

Geneva civic app: users register an address + radius and get a push notification when a
building-permit dossier (SITG open data layer `SIT_AUTOR_DOSSIER`) appears or changes status
within that radius.

- `backend/`: Spring Boot 4, Java 21, Gradle (Groovy DSL), base package `ch.perimetre`
- `app/`: Flutter, iOS first (Android must compile), two tabs: Explorer and Mon périmètre (see "App" below)

## Status / next steps (2026-10-26)

Prod is live (see "Production"): image `caea82fb`, 20,902 dossiers, FCM verified. Leave prod as is
until the validation nights are done: no deploys.

1. **3 validation nights in progress: 2026-10-27, -28, -29 at 03:15** (Sunday 11-01 adds the 03:45
   full run). Each morning check:
   - `curl -s -H "Authorization: Bearer $(ssh perimetre-prod "grep '^ADMIN_TOKEN=' /opt/perimetre/.env.prod | cut -d= -f2-")" 'https://api.perimetre.ch/admin/job-runs?limit=5'`:
     a SCHEDULED `import-incremental` row per night, `status` SUCCESS, `fcm_errors` 0; `changed` and
     `pushes` say whether anything was notified.
   - healthchecks.io: the check got `/start` + success around 03:15 (Daniel's account).
   - Pushes reached the test subscription `33333333-3333-4333-8333-333333333333` (Rue du Rhône 1,
     500 m, the densest zone: ~160 changes / 30 days): `notification` rows SENT with an `fcm_message_id`.
     It lives on the iPhone 17 Pro simulator (`BE879EBC-…`), shut down but **never uninstall the app or
     erase that simulator**: its FCM token is the subscription's. Boot it to see delivered pushes.
2. **Manual items (Daniel):** GitLab registry deploy token + `docker login` on the box (until then
   deploys go via `docker save | ssh … docker load` + `ship.sh --no-pull`); restic to Swiss Backup;
   `www.perimetre.ch` A record (then `docker restart perimetre-caddy-1`). Steps in "Production".
3. **Then:** a proper map tile provider (OSM public tiles are dev only), TestFlight build (release
   builds already default to `https://api.perimetre.ch`), public GitHub repo (check no secret is in the
   history first), announcement to SITG.

## Local environment

- `docker compose up -d` starts PostGIS 17 (`perimetre-db`, perimetre/perimetre@localhost:5433,
  db `perimetre`) and Redis 7 (`perimetre-redis`, localhost:6380).
- `cd backend && ./gradlew bootRun` (port 8090); health at `GET http://localhost:8090/actuator/health`.
  With the dev endpoint: `./gradlew bootRun --args='--spring.profiles.active=dev'`.
- Reset the DB: `docker compose down -v && docker compose up -d`.

## Local ports

Périmètre uses **8090** (backend), **5433** (PostGIS) and **6380** (Redis), because other local
projects share this machine: online-shop (8080–8083, 8888) and DealJE.

Standing rule: if a port is already in use when starting something, assume another local project
of Daniel's is running. Do not kill or stop that process and do not ask him. If the busy port
belongs to Périmètre, pick the next free port in the same range, use it for the current session
only via an override (env var, `--server.port`, or a `docker-compose.override.yml` that stays
untracked; it is in `.gitignore`), say which port you used, and do not change committed config.
Docker containers from other projects (names not starting with `perimetre-`) are never to be
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
  `yyyy-MM-dd HH:mm:ss` in UTC. The 3-day overlap is deliberate: `date_maj_2` is date-only and lags
  publication by ~1 day, so a 1-day overlap can miss dossiers stamped "yesterday" that are published
  after a run. Re-fetching ~1–3k rows per run is cheap and the upsert is idempotent.
- **Query (mode `FULL`):** same import with `where = date_maj_2 >= TIMESTAMP '2025-01-01 00:00:00' AND
  date_depot IS NOT NULL` (~21k rows, ~7 s). Reason: `date_maj_2` does not reliably move when the
  `statut` changes (observed: statut changed on 21.09, `date_maj_2` stayed 27.08), so the incremental
  cursor can miss groupe changes; the weekly full pass catches them. Events are detected the same way.
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
- **Scheduling** (`ImportJob`, `@EnableScheduling`): incremental daily at 03:15, full on Sundays at
  03:45, both `zone = "Europe/Zurich"`. An in-process `ReentrantLock.tryLock` serialises runs: a
  scheduled run that finds it held logs a WARN and skips; `POST /admin/import` answers 409. Each run =
  import, then `NotificationDispatcher.dispatch(eventIds of that run)`. A dispatch failure is logged
  but does not fail the run (the import is committed; its notifications stay PENDING).
  **Every run** (scheduled or `/admin/import`) inserts a RUNNING `job_run` row, then sets SUCCESS,
  FAILED (import threw: nothing written, counters null, `error_message`) or DISPATCH_FAILED (import
  committed, dispatch threw), and logs one INFO line `Job run <id> <job_name> (<trigger>) <status>:
  fetched= changed= notified= pushes= fcm_errors= durationMs=`. `changed` = events detected (NEW +
  GROUPE_CHANGE), not upserted rows (every re-fetched row is "updated"). RUNNING rows found at startup
  (a restart mid-run) are marked FAILED. **Dead-man's switch** (`HealthcheckPinger`): with env
  `HEALTHCHECK_PING_URL` (healthchecks.io), scheduled runs only POST `<url>/start`, then `<url>` on
  SUCCESS or `<url>/fail` (summary as body); manual runs never ping. Unset/blank = no-op; a failed ping
  is a WARN (without the URL) and never fails the run; 10 s timeouts.
  At startup `ScheduleReport` logs one `Scheduled … cron "…", next run …` line per registered cron task
  (read from the scheduler, next run in Europe/Zurich).
- **HTTP:** `RestClient`, 60 s connect/read timeout, one retry on HTTP 5xx (SITG answers bad queries
  with a 500 HTML maintenance page). ArcGIS `{"error":…}` bodies with HTTP 200 fail the import.
  One INFO log line per page.
- **Data facts (2026-10):** ~20.8k dossiers modified since 2025-01-01, ~1k/day. `date_maj_2` is
  date-only (midnight UTC) and lags about one day behind publication.

## Data model (Flyway V1–V6)

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
  finished_at, status RUNNING|SUCCESS|FAILED|DISPATCH_FAILED, fetched, changed, notified, pushes,
  fcm_errors, error_message)` (V5): one row per import run, see "Scheduling". `trigger` and `pushes` are
  not in the original spec. Counters are null on FAILED.

## Notifications (`ch.perimetre.notification`)

- **Matching** (`NotificationDispatcher`): for the events of a run, insert PENDING rows for every
  subscription with `ST_DWithin(sub.geom::geography, dossier.geom::geography, 1000) AND
  ST_DWithin(…, sub.radius_m) AND new_groupe = ANY(categories)`, `ON CONFLICT (subscription_id,
  event_id) DO NOTHING`; then send the PENDING rows of those events, grouped by **device**. The constant
  1000 m (max radius) predicate is what makes the planner probe `subscription_geog_gist`: `radius_m`
  belongs to the indexed table, so the exact check alone is no index condition (measured 2026-10-26,
  10k subscriptions × 200 events: 1185 ms seq-scan nested loop → 155 ms index scan).
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
  snake_case, plus `duration_ms`.
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
flutter_map with OSM tiles, url_launcher, firebase_core + firebase_messaging, shared_preferences.
iOS bundle id `ch.perimetre.app` (Firebase iOS app, APNs). The Android `applicationId`/namespace and the
OSM `userAgentPackageName` are still `ch.perimetre.perimetre`. French strings hardcoded, no i18n.

```
lib/main.dart                      loads SharedPreferences + initPush(), ProviderScope overrides (retry disabled)
lib/app.dart                       MaterialApp.router + GoRouter (shell with two branches)
lib/firebase_options.dart          generated by flutterfire (iOS only, project perimetre-901ec; see "Firebase")
lib/api/models.dart                AddressSuggestion, Dossier, Groupe, Subscription(Input), Device, DossierEvent + vocabularies
lib/api/geo_admin.dart             geo.admin.ch SearchServer address search
lib/api/perimetre_api.dart         /dossiers, /devices; API_BASE_URL dart-define (release default: prod)
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
```

- **Simulators:** dev work uses **`Perimetre Dev (iPhone 17 Pro)`, `56220C87-FCAD-458A-B65B-6E8E8025A7D3`**
  (created 2026-10-26). Always pass that **id** to `-d`: the name "iPhone 17 Pro" also matches
  `BE879EBC-…`, the simulator holding the prod test subscription, which must never get a dev build, an
  uninstall or an erase (see "Status").
- **Run on the iOS simulator** against the local backend (start the DB and `./gradlew bootRun` first):
  `cd app && flutter run -d 56220C87-FCAD-458A-B65B-6E8E8025A7D3 --dart-define=API_BASE_URL=http://localhost:8090`
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
- OSM's public tile servers are fine for development only (tile usage policy). Switch to a proper
  tile provider before release.
- **Details sheet title:** when the short label is "Demande", the description is the title and the
  "Demande" heading is dropped (the description is then not repeated below).
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
- **iOS push setup in the repo:** `Runner/Runner.entitlements` (`aps-environment = development`,
  referenced by `CODE_SIGN_ENTITLEMENTS` in all three Runner configurations) and `UIBackgroundModes`
  `remote-notification` in `Info.plist`.

### Firebase

`lib/firebase_options.dart` is the real FlutterFire-generated config, iOS only: Firebase project
`perimetre-901ec`, bundle id `ch.perimetre.app` (with `ios/Runner/GoogleService-Info.plist` and
`firebase.json`). On iOS the app uses `FirebasePushService` and real FCM tokens. Verified 2026-10-25 on
the iPhone 17 Pro simulator: a real FCM push from `/admin/dev/emit` was delivered. For every other
platform, including Android, `DefaultFirebaseOptions.currentPlatform` still throws `UnsupportedError`,
and `initPush()` catches it and uses `StubPushService` (fake token `dev-token-<uuid>`), which works
against a backend running `LogPushSender`. If `Firebase.initializeApp` fails, the app also falls back to
the stub (logged).

Setup (Daniel, once; done for iOS; Android still needs steps 1 and 3):
1. Firebase console: create a project (e.g. `perimetre`); add an iOS app with bundle id
   `ch.perimetre.app` (later an Android app; its `applicationId` is still `ch.perimetre.perimetre`).
2. Apple Developer → Keys: create an APNs key (.p8), note Key ID and Team ID (`K433J5XKST`). Firebase
   console → Project settings → Cloud Messaging → Apple app configuration: upload it.
3. `dart pub global activate flutterfire_cli`, then in `app/`: `flutterfire configure
   --project=<project-id> --platforms=ios,android --ios-bundle-id=ch.perimetre.app
   --android-package-name=ch.perimetre.perimetre --overwrite-firebase-options` (confirm overwriting
   `lib/firebase_options.dart`). Commit the generated files.
4. Project settings → Service accounts → Generate new private key; save it as
   `backend/.secrets/firebase-service-account.json` (`backend/.secrets/` is git-ignored).
5. Start the backend with `FIREBASE_CREDENTIALS=.secrets/firebase-service-account.json ./gradlew bootRun`
   (relative to `backend/`, or an absolute path). Startup logs "Sending pushes through FCM…".
6. Real pushes need a physical iPhone (or a simulator with APNs support) and the Push Notifications
   capability on the App ID, which automatic signing adds from the entitlements file.

## Tests

- Unit: `SitgMappingTest` (statut → groupe, flags), `ChangeDetectorTest` (event rules incl.
  bootstrap), `PushPlannerTest` (≤ 3 / > 3 summary rule counted in events, one push per event and
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
  device naming both perimeters + device feed. `ImportJobRunTest` (SitgImporter mocked): `job_run` row
  on success and FAILED on exception, `/admin/job-runs` newest first.
- `V6MigrationTest`: creates a throwaway database `perimetre_v6_migration_test` on the same server,
  migrates it to V5, seeds prod-like rows, migrates to the latest and checks ids, tokens, counters,
  label → address, notifications and cascades (dropped afterwards). A manual rehearsal on a copy of the
  local V5 DB (2026-10-26) matched too.
- **On demand:** `PERIMETRE_PERF=1 ./gradlew test --tests '*DispatchPerformanceTest' -i` seeds 10k
  subscriptions on 4k devices + 200 events (needs imported dossiers), prints the matching plan (must
  show `subscription_geog_gist`) and the dispatch time, rolls back. 2026-10-26 on the Mac: matching
  155 ms; dispatch 2.3 s for 29,531 notifications → 4,324 log pushes to 3,320 devices. With real FCM
  the sends are sequential (one HTTP call each), which dominates at that scale.
- App: `flutter analyze`; `integration_test/subscription_flow_test.dart` drives the real app on a
  simulator against the local backend (two perimeters, simulated push tap → details sheet, unknown
  dossier message, delete both; adoption of a legacy subscription id):
  `cd app && flutter test integration_test -d 56220C87-FCAD-458A-B65B-6E8E8025A7D3`.
  Android is not built locally (no Android SDK on this Mac); firebase_messaging 17 needs minSdk 24,
  which is Flutter 3.47's default (`flutter.minSdkVersion` in `android/app`).

## Production

- **Host:** Infomaniak VPS Lite `179.237.124.145` (`ov-05cab3.infomaniak.ch`), Ubuntu 24.04, 2 vCPU,
  4 GB RAM + 2 GB swap (swappiness 10), timezone Europe/Zurich. SSH alias **`perimetre-prod`** (user
  `ubuntu`, `~/.ssh/id_ed25519`). The Infomaniak firewall lets 22/80/443 TCP in, nothing else (so no
  HTTP/3: Caddy is set to h1/h2). Password and root SSH logins are off, fail2ban has an sshd jail,
  unattended-upgrades is on. All of it comes from `deploy/bootstrap.sh` (idempotent; re-run with
  `ssh perimetre-prod 'sudo bash -s' < deploy/bootstrap.sh`).
- **DNS** (Infomaniak, ns11/ns12.infomaniak.ch; domain registered 2026-10-26, delegated ~09:10):
  `perimetre.ch` and `api.perimetre.ch` A → the host. **`www.perimetre.ch` has no record yet**: Caddy
  keeps retrying its certificate (backoff, NXDOMAIN in `docker logs perimetre-caddy-1`); once the A
  record exists, `docker restart perimetre-caddy-1` makes it retry at once. No AAAA (the host has IPv6,
  unpublished).
- **`/opt/perimetre`** (owned by `ubuntu`):
  | path | what |
  |------|------|
  | `docker-compose.prod.yml`, `caddy/Caddyfile`, `site/`, `backup.sh` | synced from `deploy/` by `ship.sh`; never edit on the box |
  | `.env.prod` (600) | the only copy: `SPRING_PROFILES_ACTIVE=prod`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FIREBASE_CREDENTIALS`, `ADMIN_TOKEN` (template: `deploy/.env.prod.example`) |
  | `secrets/` (700) | `firebase-service-account.json` (600), the same `perimetre-901ec` key as local dev; mounted read-only at `/secrets` |
  | `backups/` (700) | nightly `pg_dump -Fc`, 14 days |
  | `logs/` (700) | the backend log saved by `ship.sh` before each recreate (last 30) |
- **Stack** (compose project `perimetre`, `deploy/docker-compose.prod.yml`): `caddy` (the only
  published ports, 80/443; Let's Encrypt certificates in the `caddy_data` volume), `backend` (registry
  image, no host port, `TZ=Europe/Zurich`, `mem_limit` 1.5 GB, `-XX:MaxRAMPercentage=75` → ~1.1 GB
  heap), `db` (`imresamu/postgis:17-3.5`, volume `db_data`, no host port, `TZ` + `log_timezone`
  Europe/Zurich, the session `timezone` stays UTC; `shared_buffers=512MB`, `effective_cache_size=1GB`,
  `work_mem=16MB`, `maintenance_work_mem=128MB`, `shm_size` 256 MB, `mem_limit` 1.25 GB). Only Caddy
  publishes ports: Docker's published ports bypass ufw, so `db` and `backend` must never get `ports:`. Every `@Scheduled` cron carries `zone = "Europe/Zurich"`; the
  JVM's `TZ` only affects log timestamps. No Redis. Every image is pinned by tag +
  digest, every log capped at 5 × 10 MB. Caddy: `api.perimetre.ch` → `backend:8090`;
  `perimetre.ch` → `site/` (`index.html`, `privacy.html`, served at `/privacy` too); `www` → apex.
  The backend trusts `X-Forwarded-*` (`server.forward-headers-strategy: framework` in
  `application-prod.yml`) because only Caddy can reach it.
- **Profile `prod`** (`application-prod.yml`): datasource from `DB_URL`/`DB_USER`/`DB_PASSWORD` with
  no defaults (unset = startup fails), Hikari pool 5, actuator exposes `health` only with
  `show-details: never` and no discovery page, scheduler on, `/admin/dev/**` absent, `/admin/**`
  behind `ADMIN_TOKEN` (see "API").
- **CI** (`.gitlab-ci.yml`, shared runners; the homelab runner is a DealJE project runner): merge
  requests and main run `backend-test` (`./gradlew test` against a PostGIS service, `DB_URL` pointed at
  it) and `app-analyze` (Flutter 3.47.7 checked out in the job, `flutter analyze`, `flutter test` when
  `app/test` exists). Merge only with a green MR pipeline.
- **Images:** after the tests, every push to main builds `backend/Dockerfile` and pushes
  `registry.gitlab.com/daniellaera1/perimetre/backend:<short-sha>` and `:latest`, labelled with
  `org.opencontainers.image.revision`. CI never deploys. The box is meant to pull with a GitLab deploy
  token (`read_registry`), logged in once as `ubuntu` (`~/.docker/config.json`). **Not set up yet
  (manual step, Daniel):** GitLab → perimetre → Settings → Repository → Deploy tokens → `read_registry`,
  then on the box `docker login registry.gitlab.com -u <token user>` (password on stdin). Until then:
  pull the CI image on the Mac, `docker save <image> | gzip | ssh perimetre-prod 'gunzip | docker load'`,
  `docker tag` it `:latest` there, and run `deploy/ship.sh --no-pull`.
- **Deploy:** tests green locally → push main → wait for the pipeline (`glab ci status --live`) →
  **`deploy/ship.sh`** (or `deploy/ship.sh <short-sha>` to pin or roll back, GitLab's 8-char short sha; `--no-pull` uses the image
  already on the box). The `ship` skill (`.claude/skills/ship/SKILL.md`) walks through it.
- **Logs:** `ssh perimetre-prod 'docker logs -f --since 1h perimetre-backend-1'` (same for
  `perimetre-caddy-1`, `perimetre-db-1`); saved pre-deploy logs in `/opt/perimetre/logs/`; backups
  `ssh perimetre-prod 'journalctl -t perimetre-backup'`. Caddy keeps no access log.
- **`HEALTHCHECK_PING_URL`** in `.env.prod`: set 2026-10-26 to Daniel's healthchecks.io check (the
  URL is in `.env.prod` only; check schedule `15 3 * * *` Europe/Zurich). Changing it: edit `.env.prod`,
  then `docker compose … up -d --force-recreate backend`. Sunday's 03:45 full run pings the same check.
- **FCM verified on prod 2026-10-26:** `POST /admin/test-push` to the simulator's subscription →
  `projects/perimetre-901ec/messages/ee42f076-aa9c-40af-bcca-9ecf590463fd`, shown on the iPhone 17 Pro
  simulator.
- **Admin calls:** `curl -fsS -X POST -H "Authorization: Bearer $ADMIN_TOKEN"
  'https://api.perimetre.ch/admin/import?mode=incremental'` (the token is in Daniel's Vaultwarden).
- **Rotate `ADMIN_TOKEN`** (last: 2026-10-26). The new value is generated on the box and never shown:
  it goes from `openssl` into an env var and is written by awk via `ENVIRON` (not argv), the file stays
  600, then the backend is recreated (the old token dies with it):
  ```bash
  ssh perimetre-prod 'cd /opt/perimetre && umask 077 && NEW=$(openssl rand -hex 32) awk '"'"'/^ADMIN_TOKEN=/{print "ADMIN_TOKEN=" ENVIRON["NEW"]; n++; next} {print} END{if(n!=1) exit 3}'"'"' .env.prod > .env.prod.new \
    && chmod 600 .env.prod.new && mv .env.prod.new .env.prod \
    && docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate backend'
  ```
  Then copy it to the clipboard for Vaultwarden without displaying it:
  `ssh perimetre-prod "grep '^ADMIN_TOKEN=' /opt/perimetre/.env.prod | cut -d= -f2- | tr -d '\n'" | pbcopy`.
  `DB_PASSWORD` is not rotated that way: Postgres only reads `POSTGRES_PASSWORD` when the volume is
  created. `ALTER ROLE perimetre PASSWORD '…'` in the db container first, then `.env.prod`, then
  recreate the backend.
- **Backups:** `/opt/perimetre/backup.sh`, cron `/etc/cron.d/perimetre-backup` at 02:30 Europe/Zurich as
  `ubuntu`: `pg_dump -Fc` inside the db container → `backups/perimetre-<timestamp>.dump`, checked with
  `pg_restore --list`, files older than 14 days deleted. Restore (stops nothing; `--clean` drops and
  recreates the objects):
  `docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < backups/<file>.dump`.
- **Off-site backup: manual step, not done yet (Daniel).** The dumps live on the same disk as the
  database. To copy them to Swiss Backup with restic:
  1. Infomaniak Manager → Swiss Backup → add a device of type "Cloud" (S3); note the endpoint, access
     key and secret.
  2. On the box: `sudo apt-get install -y restic`; write `/opt/perimetre/secrets/restic.env` (600) with
     `RESTIC_REPOSITORY=s3:<endpoint>/perimetre`, `RESTIC_PASSWORD=<openssl rand -hex 32>`,
     `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`. Store `RESTIC_PASSWORD` in Vaultwarden: without it
     the repository cannot be read.
  3. `set -a; . /opt/perimetre/secrets/restic.env; restic init`.
  4. A second cron line in `/etc/cron.d/perimetre-backup`, after the dump:
     `0 3 * * * ubuntu set -a; . /opt/perimetre/secrets/restic.env; restic backup -q /opt/perimetre/backups && restic forget -q --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune 2>&1 | logger -t perimetre-backup`
     (put it in `bootstrap.sh` once it works).
  5. Restore one dump from restic to a scratch directory and `pg_restore --list` it.
- **Go-live 2026-10-26** (image `5655948f`): bootstrap `POST /admin/import?mode=full` over HTTPS →
  20,902 dossiers in 13 s, 0 events. A second full refresh right after: 0 events, 0 new ids and 0 rows
  whose content (all columns but `first_seen`/`last_seen`) changed, so the import is idempotent. Restore
  test: the 09:17 dump restored into a throwaway `--network none` PostGIS container matched prod's row
  count on every table. From outside only 22/80/443 answer (5432, 6379, 8090 time out). Idle memory:
  backend ~190 MB, db ~50 MB, caddy ~15 MB.
- **Never** `docker compose down -v` on the box (deletes the database and Caddy's certificates), never
  copy `.env.prod` off the box or overwrite it from elsewhere, never print the values in it or in
  `secrets/`.
