# Map tiles: replacing OSM's public tile servers

Status 2026-10-26: **decision needed**. The app still uses `tile.openstreetmap.org`, which OSM's tile usage
policy does not allow for a published app. Nothing below needs code until a provider is chosen; the swap is
one `TileLayer` (raster) or the map widget (vector) in `app/lib/features/explore/widgets.dart`.

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

## Recommendation

1. **OpenFreeMap `positron` / `dark`** if a vector map is acceptable. No account, no cost, no request
   limit, global OSM coverage, and light + dark styles matching the app. It costs work: flutter_map
   needs `vector_map_tiles` (9.x for flutter_map 8, beta) or a switch to `maplibre_gl` (native
   MapLibre, mature; markers become symbol layers or overlays). The public instance is one person's
   donation-funded project. If it disappears, the same stack can be self-hosted: OpenFreeMap is fully
   open source, and a regional PMTiles extract of Geneva plus the French border is small enough to serve
   from our own Caddy.
2. **Mapbox Static Tiles** (styles `light-v11` / `dark-v11`) if the map should stay raster with no
   rewrite: a one-line `TileLayer` change, free at our volume today, commercial use allowed. It needs a
   Mapbox account and a public token in the app. It carries a cost risk above ~200k tiles/month, so
   enable the tile cache.

Either way, update the attribution chip (`© OpenStreetMap contributors` today) to the provider's wording,
plus the Mapbox logo for option 2. Keep the attribution at the top of the map, because the list sheet
covers the bottom.
