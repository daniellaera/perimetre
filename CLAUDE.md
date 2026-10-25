# Périmètre

Geneva civic app: users register an address + radius and get a push notification when a
building-permit dossier (SITG open data layer `SIT_AUTOR_DOSSIER`) appears or changes status
within that radius.

- `backend/`: Spring Boot 4, Java 21, Gradle (Groovy DSL), base package `ch.perimetre`
- `app/`: Flutter, iOS first (Android must compile), two tabs: Explorer and Mon périmètre (see "App" below)

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
- **HTTP:** `RestClient`, 60 s connect/read timeout, one retry on HTTP 5xx (SITG answers bad queries
  with a 500 HTML maintenance page). ArcGIS `{"error":…}` bodies with HTTP 200 fail the import.
  One INFO log line per page.
- **Data facts (2026-10):** ~20.8k dossiers modified since 2025-01-01, ~1k/day. `date_maj_2` is
  date-only (midnight UTC) and lags about one day behind publication.

## Data model (Flyway V1–V4)

- `dossier`: one row per SITG dossier (see "SITG import"); `dossier_geog_gist` on `(geom::geography)`.
- `sync_state(source PK, last_run, bootstrapped)`: one row, `SIT_AUTOR_DOSSIER`.
- `subscription(id UUID PK, push_token, platform ios|android|null, geom Point 4326, radius_m 100–1000,
  categories TEXT[] default all five notifiable groupes, label, consecutive_failures, created_at,
  updated_at)`; `subscription_geog_gist` on `(geom::geography)`. `consecutive_failures` is not in the
  original spec: it counts runs whose push failed with an invalid token (see "Notifications").
- `event(id BIGSERIAL, id_dossier → dossier, kind NEW|GROUPE_CHANGE, old_groupe, new_groupe, occurred_at)`,
  indexed on `occurred_at` and `id_dossier`.
- `notification(id, subscription_id → subscription ON DELETE CASCADE, event_id → event,
  status PENDING|SENT|FAILED|SKIPPED, sent_at, error, fcm_message_id, UNIQUE(subscription_id, event_id))`.
  `fcm_message_id` (V4) is FCM's answer to a sent push (`projects/<id>/messages/<uuid>`; a summary
  push stamps all its rows with the same id); null for `LogPushSender` and failed pushes.

## Notifications (`ch.perimetre.notification`)

- **Matching** (`NotificationDispatcher`): for the events of a run, insert PENDING rows for every
  subscription with `ST_DWithin(sub.geom::geography, dossier.geom::geography, sub.radius_m) AND
  new_groupe = ANY(categories)`, `ON CONFLICT (subscription_id, event_id) DO NOTHING`; then send the
  PENDING rows of those events, grouped by subscription.
- **Per subscription and run** (`PushPlanner`): ≤ 3 notifications → one push per event; > 3 → ONE
  summary push and all rows marked SENT together. So at most 3 pushes per subscription per run.
- **Payload** (`PushMessages`, French, never any names; only SITG's work description):
  - title `"{label} à {distance} m"`: label per the vocabulary below (M/MPA → Démolition); when it is
    "Demande", the first 40 chars of the description (trimmed, first letter capitalised, "…" when
    cut). Distance rounded to the metre, from the subscription point.
  - body `"{event text}. {description, max 90 chars + …}"` (just `"{event text}."` without
    description). Event text by `new_groupe`: DEPOSE "Nouvelle demande déposée — délai d'opposition en
    cours" · AUTORISE "Autorisation délivrée" · CHANTIER "Les travaux commencent" · RECOURS "Un recours a
    été déposé" · REFUSE "Le projet ne se fera pas".
  - data `{id_dossier, kind, new_groupe}`. Summary: title "Périmètre", body "N nouveaux changements
    autour de chez vous", data `{kind: SUMMARY, count}`. iOS gets `aps.sound = default`.
- **Senders** (`PushSender`, chosen by `PushConfig` at startup): `FcmPushSender` (firebase-admin) when
  the env var `FIREBASE_CREDENTIALS` points to an existing service-account JSON file, otherwise
  `LogPushSender`, which logs `PUSH (log only) token=… title="…" body="…" data={…}` at INFO and
  reports success. The startup log says which one is active.
- **Failures:** FCM `UNREGISTERED`, `INVALID_ARGUMENT` or `SENDER_ID_MISMATCH` = invalid token: that
  push's rows → FAILED with the error, the rest of the subscription's pushes in that run → SKIPPED,
  `consecutive_failures + 1`; at 3 the subscription is deleted (its notifications cascade). Any other
  failure → FAILED, not counted. A successful push resets the counter, and so does a PUT.
- **Registration tokens are deprecated** in firebase-admin 9.11 (`Message.setToken` → `setFid`) and
  firebase_messaging 17 (`getToken`/`onTokenRefresh` → `register()`/`onRegistered`, which yield a
  Firebase Installation ID). Both sides still use tokens (deprecation suppressed and commented);
  migrate `FcmPushSender` and the app's `FirebasePushService` together.
- firebase-admin pulls google-cloud-storage, which brings `jackson-dataformat-xml`; it is excluded in
  `build.gradle` because Spring MVC would otherwise answer browsers' `Accept` headers with XML.

## API

- `POST /admin/import?mode=incremental|full` (default incremental): runs import + dispatch
  synchronously and returns `{mode, fetched, inserted, updated, events, notifications, pushes,
  durationMs}`; 409 when an import is already running, 400 for an unknown mode.
  **No auth yet; must be secured before deployment.**
- `GET /dossiers?lat=&lng=&radius=`: radius in metres (1–2000). Dossiers within the radius
  (`ST_DWithin`/`ST_Distance` on geography), excluding groupes `IGNORE` and `TERMINE` (both stay in
  the table), ordered by `date_maj_sitg DESC, distance_m ASC, id_dossier ASC` (deterministic, since
  `date_maj_sitg` is day-granular), limit 200. Fields: `id_dossier, type_dossier, type_operation,
  operation, description, groupe, date_depot, date_maj_sitg, flags, lien_sad, lat, lng, distance_m`.
  `operation` and `description` are SITG's raw texts. `operation` is "Non renseigné (valeur par
  défaut…)" when `type_operation` is `--`. `description` may have trailing whitespace.
  Served by the expression index `dossier_geog_gist` on `(geom::geography)` (V2). Keep the predicate
  written as `ST_DWithin(geom::geography, …)` or the planner falls back to a seq scan.
- **Subscriptions** (no auth: the device keeps the random UUID, which is the only handle; snake_case JSON):
  - `POST /subscriptions {push_token, platform?, lat, lng, radius_m, categories?, label?}` → 201 +
    `Location`, body `{id, platform, lat, lng, radius_m, categories, label, created_at, updated_at}`
    (the push token is never echoed back). Validation (400): `push_token` required; `platform` ios|android;
    lat 46.12–46.38 and lng 5.94–6.32 (canton of Geneva bounding box); `radius_m` 100–1000;
    `categories` a non-empty subset of DEPOSE, AUTORISE, CHANTIER, RECOURS, REFUSE (default: all five,
    stored de-duplicated in that order); `label` ≤ 200 chars.
  - `GET /subscriptions/{id}`; `PUT /subscriptions/{id}` (same body, replaces everything, used for
    token refresh); `DELETE /subscriptions/{id}` → 204. Unknown id → 404, malformed UUID → 400.
  - `GET /subscriptions/{id}/events?limit=50` (1–200): events whose dossier is within the radius and
    whose `new_groupe` is in the categories (whatever their date), newest first (`occurred_at DESC,
    id DESC`). Each item = the `/dossiers` fields (flattened; `groupe` is the dossier's current groupe,
    `distance_m` from the subscription point) + `event_id, kind, old_groupe, new_groupe, occurred_at`.
- **Dev only (profile `dev`):** `POST /admin/dev/emit?id_dossier=X&new_groupe=Y` fabricates an event
  for an existing dossier (`NEW` if Y = DEPOSE, else `GROUPE_CHANGE` from its current groupe; the
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
go_router (`StatefulShellRoute` with two tabs: `/` Explorer, `/perimetre` Mon périmètre), dio,
flutter_map with OSM tiles, url_launcher, firebase_core + firebase_messaging, shared_preferences.
iOS bundle id `ch.perimetre.app` (Firebase iOS app, APNs). The Android `applicationId`/namespace and the
OSM `userAgentPackageName` are still `ch.perimetre.perimetre`. French strings hardcoded, no i18n.

```
lib/main.dart                      loads SharedPreferences + initPush(), ProviderScope overrides (retry disabled)
lib/app.dart                       MaterialApp.router + GoRouter (shell with two branches)
lib/firebase_options.dart          generated by flutterfire (iOS only, project perimetre-901ec; see "Firebase")
lib/api/models.dart                AddressSuggestion, Dossier, Groupe, Subscription(Input), DossierEvent + vocabularies
lib/api/geo_admin.dart             geo.admin.ch SearchServer address search
lib/api/perimetre_api.dart         /dossiers, /subscriptions; API_BASE_URL dart-define
lib/push/push_service.dart         PushService interface + provider (overridden in main)
lib/push/init_push.dart            Firebase if configured, else StubPushService
lib/push/firebase_push_service.dart   FCM: permission, token, refresh, tapped pushes
lib/push/stub_push_service.dart    fake "dev-token-<uuid>" (kept in prefs), no pushes
lib/features/home/home_shell.dart  NavigationBar + tapped-push handling
lib/features/explore/...           Explorer tab: providers, screen, shared widgets (address field, radius
                                   slider, map, list sheet, DossierRow, details sheet)
lib/features/subscription/providers.dart   MySubscription (id in prefs, token refresh → PUT), recentEvents
lib/features/subscription/subscription_screen.dart   Mon périmètre tab
integration_test/subscription_flow_test.dart   simulator E2E against the local backend
```

- **Run on the iOS simulator** against the local backend (start the DB and `./gradlew bootRun` first):
  `cd app && flutter run -d "iPhone 17 Pro" --dart-define=API_BASE_URL=http://localhost:8090`
  (the default base URL is `http://localhost:8090` already; the simulator reaches the Mac's localhost).
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
- **Mon périmètre:** without a subscription, a form (address field + suggestions, radius slider, five
  category chips, "Activer les notifications"; the explore address/radius pre-fill it). Saving asks
  for notification permission, gets the token, POSTs (or PUTs when editing) `/subscriptions` and
  stores the id in shared_preferences (`subscription_id`). With a subscription: a card with
  "Modifier" / "Désactiver" (confirm, then DELETE) and the "Récent" feed from
  `/subscriptions/{id}/events` (same `DossierRow` as Explorer, event text + date as the leading
  line, marker in the event's colour), pull to refresh. A 404 on load forgets the stored id (the
  backend deletes subscriptions after repeated invalid tokens). A yellow banner shows in stub mode.
- **Tapped push:** switches to Mon périmètre and refreshes the feed; with `data.id_dossier`, looks the
  dossier up via `/dossiers` around the subscription point and opens its details sheet, falling back
  to the event feed (`/dossiers` is capped at 200 and hides TERMINE), else a SnackBar "Ce dossier
  n'est plus disponible.". Summary pushes only open the tab.
- **Token refresh:** `onTokenRefresh` → PUT the subscription with the new token.
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
  bootstrap), `PushPlannerTest` (≤ 3 / > 3 summary rule), `PushMessagesTest` (payload wording, label
  vocabulary, truncation).
- Context tests need the local DB running and apply pending Flyway migrations to it:
  `BackendApplicationTests`, and `PushPipelineIntegrationTest` (profile `dev`,
  `FIREBASE_CREDENTIALS` forced empty, `@Transactional` so everything is rolled back): subscription →
  `/admin/dev/emit` → notification SENT through `LogPushSender` (log captured) → `/events` feed;
  category filtering; validation.
- App: `flutter analyze`; `integration_test/subscription_flow_test.dart` drives the real app on a
  simulator against the local backend (subscribe, simulated push tap → details sheet, unknown
  dossier message, deactivate): `cd app && flutter test integration_test -d "iPhone 17 Pro"`.
  Android is not built locally (no Android SDK on this Mac); firebase_messaging 17 needs minSdk 24,
  which is Flutter 3.47's default (`flutter.minSdkVersion` in `android/app`).
