# Périmètre

**Know what is being built around you, in Geneva.**

Périmètre is a small civic app for the canton of Geneva (Switzerland). Pick one or more addresses, a radius
(100–1000 m) and the steps you care about. You get a push notification when a building-permit application
is filed in that perimeter, or when one changes status: authorised, construction starting, appealed,
refused. An *Explorer* tab shows every open dossier around any address on a map.

No account and no ads. The data is the canton's official open data, refreshed daily.

**[Download on the App Store](https://apps.apple.com/ch/app/p%C3%A9rim%C3%A8tre/id6828071722)** (iPhone, in French) ·
website: [perimetre.ch](https://perimetre.ch)

| Explorer | Dossier | My perimeters | New perimeter |
|---|---|---|---|
| <img src="docs/screenshots/explorer.png" width="200" alt="Map of dossiers around an address"> | <img src="docs/screenshots/dossier.png" width="200" alt="Details of a dossier"> | <img src="docs/screenshots/perimetres.png" width="200" alt="Two perimeters and the recent changes in them"> | <img src="docs/screenshots/nouveau-perimetre.png" width="200" alt="Form to add a perimeter"> |

*(Screenshots from a development build against a local database; the "Récent" events are fabricated by
the dev endpoint. The app is in French.)*

## How it works

```
 SITG open data (ArcGIS FeatureServer, SIT_AUTOR_DOSSIER)
        │  daily 10:15 incremental import, Sunday 10:45 full refresh (after SITG's morning publication)
        ▼
 ┌───────────────────────────── backend (Spring Boot 4, Java 21) ─────────────────────────────┐
 │ import ─► upsert dossiers ─► detect events (new dossier, status group change)               │
 │                                   │                                                         │
 │                                   ▼                                                         │
 │ match events to subscriptions (PostGIS ST_DWithin, radius + categories) ─► one push per     │
 │ event and device (≤ 3 per run, else a summary)   ─► Firebase Cloud Messaging ─► APNs        │
 │                                                                                             │
 │ REST API: /dossiers (map search), /devices (installations, their perimeters, event feed)    │
 └──────────────────────────────────────────┬──────────────────────────────────────────────────┘
                                            │ PostgreSQL 17 + PostGIS
 app (Flutter, iOS first) ──── HTTPS ───────┘   address search: geo.admin.ch (Swiss federal geoportal)
```

- `backend/`: Spring Boot 4 (modular starters), Java 21, Gradle. Hand-written SQL through `JdbcClient`, no
  ORM; Flyway migrations; PostGIS geography indexes for radius queries.
- `app/`: Flutter (Riverpod, go_router, flutter_map, dio, firebase_messaging). Two tabs: *Explorer* and
  *Mes périmètres*. iOS is the primary target; Android compiles but has no push configuration yet.
- `deploy/`: the production setup, which is Docker Compose with Caddy, the backend and PostGIS on a single small VPS,
  plus bootstrap, deploy and backup scripts.
- `docs/`: map-tile decision, App Store checklist, screenshots.
- [`CLAUDE.md`](CLAUDE.md): the detailed design notes (import rules, status mapping, data model, API,
  notification rules, conventions). It is the reference for anyone working on the code, human or AI.

## Data source

Building-permit dossiers come from the **SITG, Système d'information du territoire à Genève**, layer
`SIT_AUTOR_DOSSIER` ("Autorisations de construire"), through its public ArcGIS REST service.

- **Attribution:** "Source : SITG – Système d'information du territoire à Genève". The app credits it in
  the dossier details, on the website, and here.
- **Terms:** SITG open geodata is governed by the *Conditions générales d'utilisation des géodonnées du SITG
  en libre accès* (2020). Article 6 allows extracting and using it for private, educational or commercial
  purposes, and citing the source is mandatory ([text on LexFind](https://www.lexfind.ch/tolv/246868/fr)).
  On [opendata.swiss](https://opendata.swiss) SITG datasets are labelled "Open use. Must provide the
  source. Use for commercial purposes requires permission of the data owner." Périmètre is free and
  non-commercial. If you build something commercial on it, check with the SITG first.
- Périmètre is an independent project. It is not published or endorsed by the État de Genève or the SITG.
  The data is shown as published, and SITG's official dossier page (`lien_sad`) is the reference.

Address search uses the [geo.admin.ch](https://www.geo.admin.ch) search API (free, no key). The map uses
[Mapbox](https://www.mapbox.com) raster tiles (light and dark styles, © Mapbox © OpenStreetMap); see
[`docs/map-tiles.md`](docs/map-tiles.md) for why and for the fallback. Without a Mapbox token, debug builds
use OpenStreetMap's public tiles, which are fine for development only.

## Local development

Prerequisites: Docker, a JDK (the Gradle toolchain provisions Temurin 21 itself), Flutter 3.47 and Xcode
for iOS.

```bash
# Database (PostGIS on localhost:5433) and Redis (localhost:6380, not used by the backend yet)
docker compose up -d

# Backend on localhost:8090; the dev profile adds POST /admin/dev/emit to fabricate events
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# First import (~21k dossiers, a few seconds). Without ADMIN_TOKEN, /admin is open locally (with a WARN).
curl -X POST 'http://localhost:8090/admin/import?mode=full'
curl 'http://localhost:8090/dossiers?lat=46.1930&lng=6.1412&radius=300'
```

```bash
# App, without Firebase (pushes are simulated: fake token, nothing delivered)
cd app
cp lib/firebase_options.dart.example lib/firebase_options.dart
cp ios/Runner/GoogleService-Info.plist.example ios/Runner/GoogleService-Info.plist
flutter pub get
flutter run -d <simulator id> --dart-define=API_BASE_URL=http://localhost:8090
```

### Map tokens (Mapbox)

The map needs a Mapbox **public** token (`pk.…`, from your Mapbox account → Tokens; the default public
scopes are enough). Use one token for development and another for release builds. Tokens are passed at
build time from files that git ignores:

```bash
cd app
cp config/example.json config/dev.json     # put the development token in it
cp config/example.json config/prod.json    # put the release token in it
flutter run -d <simulator id> --dart-define-from-file=config/dev.json
flutter build ipa --release --dart-define-from-file=config/prod.json
```

A release iOS build without a token fails on purpose (Xcode build phase "Check Mapbox token"). A debug build
without one falls back to OpenStreetMap's tiles. Never commit a token: CI runs gitleaks with a Mapbox rule
(`.gitleaks.toml`), and `git config core.hooksPath .githooks` adds the same check before every commit.

Debug builds default to `http://localhost:8090`. Release builds default to the production API, and
`--dart-define=API_BASE_URL=…` always wins. To fabricate an event and watch the push pipeline (logged by
`LogPushSender` when Firebase is not configured):
`curl -X POST -G localhost:8090/admin/dev/emit --data-urlencode 'id_dossier=<id>' -d new_groupe=CHANTIER`.

### Push notifications

Real pushes go through Firebase Cloud Messaging (APNs on iOS). You need your own Firebase project:

1. Create a Firebase project with an iOS app (your bundle id) and upload an APNs key (.p8) in its Cloud
   Messaging settings.
2. In `app/`: `flutterfire configure --project=<id> --platforms=ios`. This writes `lib/firebase_options.dart`,
   `ios/Runner/GoogleService-Info.plist` and `firebase.json`, which are git-ignored on purpose.
3. Download a service-account key to `backend/.secrets/firebase-service-account.json` (git-ignored) and
   start the backend with `FIREBASE_CREDENTIALS=.secrets/firebase-service-account.json`.

Never commit any of these: service-account JSON, `.p8`/`.p12` keys, `.env*` files, the Firebase client
config and `app/config/*.json` (Mapbox tokens) are all in `.gitignore`, and CI runs gitleaks on every merge
request.

### Tests

```bash
cd backend && ./gradlew test        # unit + context tests (need the local PostGIS running)
cd app && flutter analyze
cd app && flutter test integration_test/subscription_flow_test.dart -d <simulator id>   # E2E, local backend
cd app && flutter drive --driver=test_driver/map_screenshots.dart --target=integration_test/map_tiles_test.dart \
  --dart-define-from-file=config/dev.json -d <simulator id>    # map tiles in Geneva and France, light + dark
```

`PERIMETRE_PERF=1 ./gradlew test --tests '*DispatchPerformanceTest' -i` seeds 10,000 subscriptions and
prints the matching query plan and timings.

## Self-hosting

The reference deployment runs on one VPS (2 vCPU, 4 GB RAM, Ubuntu 24.04) for the whole canton.

1. **DNS:** A records for your domain, `api.<domain>` and `www.<domain>` pointing to the server. Replace
   `perimetre.ch` in `deploy/Caddyfile` and `deploy/site/`.
2. **Server:** `ssh <host> 'sudo bash -s' < deploy/bootstrap.sh`. This sets up the firewall (22/80/443),
   SSH hardening, fail2ban, unattended upgrades, Docker, the app directory `/opt/perimetre` and the
   nightly backup cron.
3. **Configuration** (on the server only): `/opt/perimetre/.env.prod` from `deploy/.env.prod.example`
   (mode 600: database password, `ADMIN_TOKEN` of at least 32 characters, optional healthchecks.io URL)
   and `/opt/perimetre/secrets/firebase-service-account.json`.
4. **Image:** CI (`.gitlab-ci.yml`) builds `backend/Dockerfile` on every push to main. Point
   `deploy/docker-compose.prod.yml` at your registry, or build the image on the server.
5. **Deploy:** `deploy/ship.sh <short-sha>` (set `TARGET`, the SSH host alias, `DIR` and `IMAGE` at its top).
   It syncs `deploy/`, pulls that image tag, runs it and waits for health; `deploy/ship.sh --sync` only syncs
   the files (site, Caddyfile, backup scripts). Then run the first import:
   `curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" https://api.<domain>/admin/import?mode=full`.
   That run records a baseline and sends no notifications.
6. **Operations:** imports run at 10:15 (incremental) and Sundays 10:45 (full), Europe/Zurich
   (`IMPORT_INCREMENTAL_CRON` / `IMPORT_FULL_CRON` to change them). A run that finds no SITG data newer than
   4 days ends `STALE_SOURCE`.
   `GET /admin/job-runs` lists the runs. The optional `HEALTHCHECK_PING_URL` alerts you when a scheduled run
   is missing or fails. `deploy/backup.sh` keeps 14 days of `pg_dump`; `deploy/backup-offsite.sh` (nightly
   cron) copies each to a restic repository on S3 and keeps 7 daily, 4 weekly and 6 monthly snapshots (bucket
   and credentials: `deploy/set-restic-creds.sh`; restore check: `deploy/restic-restore-test.sh`).
7. **App:** build with `--dart-define=API_BASE_URL=https://api.<domain>`, your bundle id, your Firebase
   config and your own Mapbox token (see "Map tokens"). Publish your own privacy page: the template in
   `deploy/site/privacy.html` describes what is stored.

## Privacy

- **No account.** The app never asks for a name, an e-mail address, a phone number or a password.
- **Anonymous push token.** An installation is known to the server only by a random id generated by the
  server and the push token that Firebase Cloud Messaging gives the app. Neither is linked to a person.
  Once the app is deleted, its token stops working and the server deletes the installation and its
  perimeters after three failed pushes.
- **What is stored:** for each perimeter, the address and its coordinates, the radius, the categories and an
  optional name (e.g. "Domicile"); for each notification, which event was sent to which perimeter.
- **What is not:** the device's location (never read), analytics, crash reporting, advertising identifiers,
  or access logs on the server.
- Address search goes to geo.admin.ch and map tiles come from Mapbox, both called directly by the app.

The full policy (French) is in [`deploy/site/privacy.html`](deploy/site/privacy.html) and at
https://perimetre.ch/privacy.

## Repository

Development, CI (`.gitlab-ci.yml`) and the container registry live in a private GitLab project.
[github.com/daniellaera/perimetre](https://github.com/daniellaera/perimetre) is its public mirror, with the
same history; issues and pull requests are welcome there.

## License

Copyright (C) 2026 Daniel Laera

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU Affero
General Public License, version 3** ([`LICENSE`](LICENSE)). If you run a modified version as a network
service, the AGPL requires you to offer your users its source code.

The SITG data is not covered by this license; it remains under the SITG's terms (see "Data source"). Nor is
the Mapbox logo (`app/assets/mapbox-logo.png`), a Mapbox trademark shown as the attribution Mapbox requires.
