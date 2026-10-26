# Map tiles: replacing OSM's public tile servers

**Decision (2026-10-26): Mapbox raster tiles**, styles `light-v11` / `dark-v11` through the Static Tiles API,
in the existing flutter_map `TileLayer` (no map rewrite). **Fallback: OpenFreeMap** (vector, no account),
possibly self-hosted. Implemented in `app/lib/map/map_tiles.dart`; see "Implementation" at the end. The
evaluation that led there follows.

## Requirements

- Free or cheap at our scale, terms compatible with a free App Store app.
- Coverage on **both sides of the border** at street zoom (the app fits a 100–1000 m radius: zoom ~15–18;
  the default 200 m is ~z17): Annemasse, Saint-Julien, Ferney, Divonne, Gex.
- A light style that keeps the coloured dossier markers readable, and a dark style.
- Attribution shown in the map UI.

## swisstopo: does not pass

Evaluated first (no account, no key, OGD terms allow commercial use with "© swisstopo"; fair use is
1,200 requests/minute on `wmts.geo.admin.ch`).

| | Geneva | French side (Annemasse, St-Julien, Ferney, Divonne, Gex) |
|---|---|---|
| Raster `pixelkarte-farbe` / `-grau`, z12–z16 | map | map (1:25k national map covers the border area) |
| Raster, **z17–z19** | map | **blank white tile** (668 bytes; the 1:10k map is Swiss only) |
| Vector `lightbasemap.vt` (z ≤ 14, overzoomed) | full | **only administrative boundaries** |

Checked by fetching the tiles (2026-10-26). The vector service says data abroad is licensed to the federal
administration only ("Die weltweiten Daten können aus Lizenzgründen nur durch die Bundesverwaltung
genutzt werden"). There is no official dark style. The z17+ Swiss raster (1:10k, black buildings) is also
too heavy behind coloured markers.

Rejected as well: swisstopo on top of IGN's Plan IGN v2 (`data.geopf.fr`, no key, Etalab 2.0, covers
France but 404 over Switzerland). It needs blank-tile detection to stitch, and the two cartographic
styles clash at the border. No dark style either.

## Candidates (all OSM-based, global coverage, light + dark styles)

| | Account / key | Free tier | Terms for this app | Rendering in Flutter | Cost at our scale* |
|---|---|---|---|---|---|
| **OpenFreeMap** (public instance) | **none** | unlimited, no keys, no cookies | free, commercial use OK; attribution "© OpenMapTiles, © OpenStreetMap contributors"; donation-funded, best effort | vector only (MapLibre styles `positron`, `dark`, `liberty`, `bright`, `fiord`) | 0 |
| **Mapbox** | account + public token | Static Tiles API (styled raster) 200k req/month; Maps SDK 25k MAU | commercial OK; Mapbox logo + "© Mapbox © OpenStreetMap" required | raster via `TileLayer` (no rewrite) or the native SDK | 0 up to ~200k tiles/month, then ~$0.50/1k |
| **Stadia Maps** | account + key (or app domain/bundle auth) | 200k credits/month | free plan: **non-commercial only**; Starter $20/month | raster via `TileLayer` (Alidade Smooth / Smooth Dark) | $0 if non-commercial counts, else $20/month |
| **MapTiler** | account + key | 100k requests, 5k sessions/month | free plan: **non-commercial only**, logo required; Flex $30/month | raster via `TileLayer` or vector | $0 or $30/month |

\* Rough volume: 1–3k monthly users × ~5 map sessions × ~30 tiles is about 150k–450k tiles/month,
less with flutter_map 8's built-in tile cache. Prices are from the providers' pricing pages on
2026-10-26: [Mapbox](https://www.mapbox.com/pricing), [Stadia](https://stadiamaps.com/pricing/),
[MapTiler](https://www.maptiler.com/cloud/pricing/), [OpenFreeMap](https://openfreemap.org/). Re-check
them before choosing. Whether a free, ad-free civic app counts as "non-commercial" is not defined by
Stadia or MapTiler; ask them before relying on a free plan.

## Decision

1. **Mapbox Static Tiles, `light-v11` / `dark-v11`** (chosen): the map stays raster with no rewrite, it is free
   at our volume (512 px tiles are a quarter of the requests of 256 px ones: roughly 40k–110k requests/month
   for the volume above, against 200k free), commercial use is allowed, and both styles cover both sides of
   the border at street zoom (OSM data). It needs a Mapbox account and public tokens in the app (one per
   build type), and carries a cost risk above 200k requests/month: watch the Mapbox dashboard.
2. **OpenFreeMap `positron` / `dark`** (fallback): no account, no cost, no request limit, same OSM coverage.
   It costs a map rewrite: flutter_map needs `vector_map_tiles` (9.x for flutter_map 8, beta) or a switch to
   `maplibre_gl` (native MapLibre; markers become symbol layers or overlays). The public instance is one
   person's donation-funded project; the same stack can be self-hosted, since OpenFreeMap is open source and a
   PMTiles extract of Geneva plus the French border is small enough to serve from our own Caddy.

## Implementation

- `baseTileLayer(brightness)` (`app/lib/map/map_tiles.dart`): `https://api.mapbox.com/styles/v1/mapbox/
  {light-v11|dark-v11}/tiles/512/{z}/{x}/{y}@2x?access_token=…`, `tileDimension: 512`, `zoomOffset: -1`,
  following the app theme (the app has a dark theme since this change). flutter_map 8's built-in tile cache
  keeps tiles as long as Mapbox's HTTP headers allow (`max-age` 12 h); nothing is stored beyond that.
- **Attribution** (`MapAttribution`), per [Mapbox's attribution guide](https://docs.mapbox.com/help/dive-deeper/attribution/):
  the Mapbox logo (unmodified, from Mapbox GL JS, `app/assets/mapbox-logo.png`) and the links "© Mapbox"
  (mapbox.com), "© OpenStreetMap" (openstreetmap.org/copyright) and "Improve this map"
  (`apps.mapbox.com/feedback/#/{lng}/{lat}/{zoom}` of the current view), always visible at the top of the
  map, because the list sheet covers its bottom. The guide's telemetry opt-out applies to the Mapbox SDKs,
  which the app does not use (no telemetry is sent). Mapbox's terms allow incorporating its services in an
  app, and its Static Tiles docs list third-party tile clients (Leaflet, OpenLayers…) as supported uses.
- **Tokens:** public `pk.` tokens, one for development, one for release, with only the default public
  scopes. They are passed at build time (`--dart-define-from-file=config/dev.json` / `config/prod.json`,
  git-ignored, template `app/config/example.json`) and end up in the app binary, as any client token does.
  A Release iOS build without a `pk.` token fails (Runner build phase "Check Mapbox token"). Debug builds
  without a token use OSM's public tiles (development only). gitleaks (`.gitleaks.toml`) rejects any
  Mapbox token in the history, in CI and in the optional pre-commit hook (`.githooks/pre-commit`).
- **Check:** `integration_test/map_tiles_test.dart` loads Geneva centre, Annemasse and Ferney-Voltaire at
  zoom 17 in light and dark, fails on any tile error and writes screenshots to `app/build/map-screenshots/`.
- Mapbox URL restrictions only work for web requests, so the tokens cannot be locked to the app. If a token is
  abused, rotate it in the Mapbox account and ship a build with the new one.
