# Release 1.1.0: open parcel data shown natively (branch work, NOT deployed)

Goal: show every OPEN datum about a parcel in the app instead of sending users to ge.ch's extract page. Hard rules:
no owner names or non-open registry data, never touch ge.ch/terextraitfoncier from code, no webview, old backend must
keep working, anything with unclear terms is not imported. Conventions: CLAUDE.md "Parcel context".

## Phase 1, backend: done (2026-11-09)
- **Discovery** (SITG ArcGIS only; every layer's SITG catalogue page says "A - Accès libre"; opendata.swiss confirmed
  `terms_open` for the parcel layer, the portal then throttled me, see "Anomalies"): imported `TYPE_PROPRI` (category) and
  `PLAN_RF` of `CAD_PARCELLE_MENSU`, buildings `CAD_BATIMENT_HORSOL`, addresses `CAD_ADRESSE`, zones
  `RDPPF_ZONES_PRIM` + `RDPPF_ZONES_DEV`, local names `CAD_NOMS_LOCAUX`. Table of fields, links and exclusions in CLAUDE.md.
- V10 migration, `ArcgisLayerClient` (generic, shared `ArcgisRetry`), `LayerImporter` base + 4 importers, `ContexteJob`
  (crons 10:40 daily buildings / Sunday 11:30 all four, `job_run` rows, optional `CONTEXTE_HEALTHCHECK_PING_URL`),
  `POST /admin/import/contexte`, 90% shrink guard on every layer, parcel import relinks changed parcels.
- API: `parcelles[]` gains `type, plan_rf, lieu_dit, nb_batiments, batiments[], restrictions[]` (additive).
- Tests: 176 backend tests green (0 failures; 1 skipped = the on-demand perf test). New: `ContexteIntegrationTest`,
  `ArcgisLayerClientTest`, `ContexteMappingTest`; extended `DossierParcellesApiIntegrationTest`, `ParcelleClientTest`.

### Verified (local, ports 8090 / 5433 / 6380)
- Row counts = source counts: parcels 73,156; buildings 83,088; addresses 54,719; zones 2,479 + 756 = 3,235; local
  names 2,038. All 73,156 parcels have type and plan; 73,155 a lieu-dit; 73,141 a zone; 46,161 have a building
  (86,285 links); the 5 unlinked buildings are exactly the 5 with a null `EGRID_LISTE`; 97 links have no measurable
  footprint (null).
- Idempotent: second full run of buildings and addresses `changed=0`; a buildings incremental fetched 6, changed 0.
- Spot-check of 10 parcels against live SITG (type, plan, surface, number of buildings by `EGRID_LISTE`, number of
  addresses by `EGID`, zones by SITG's own point-in-polygon): 10/10 agree. (The first run disagreed on the zones of 3
  parcels because the point query in lon/lat is not reliable; repeated in native EPSG:2056 all 3 agree.)
- Sizes: see CLAUDE.md. DB 129 → 215 MB after `VACUUM FULL` (the rewrite of every parcel row alone took the table to
  197 MB).

### Decisions
- `TYPE_PROPRI` imported as the 8-value category (`type`), because it names nobody; `PROPRI_PUBLIQUE` is NOT (it names
  the public owner). Judgement call: say so if you disagree, it is one column and one field.
- Building names (`NOMBAT`), underground buildings, `NO_AUTOR`, `CAD_DDP`, `CAD_PPE`, `CAD_NATURE_SOL`: left out (see
  CLAUDE.md for each reason).
- Zones: shown when ≥ 1 m² and ≥ 1% of the parcel. Buildings: 30 largest per parcel, real count in `nb_batiments`.
- Zone and building documents (`LIEN_PLAN`, `LIEN_LOI`, `EXTRAIT_RDPPF_PDF`) are not exposed.

### Needs a decision (not imported)
- `EXTRAIT_RDPPF_PDF` / `LIEN_PLAN` / `LIEN_LOI`: links to documents on ge.ch hosts. Not checked, not stored.
- Other open `RDPPF_*` layers (noise `RDPPF_DSOPB`, `RDPPF_DISTANCES_FORET_*`, `RDPPF_CADASTRE_SITES_POLLUES`,
  `RDPPF_ZONES_PROTECTION_EAUX`, `RDPPF_PLQ`...): licence class not checked per layer; same table would hold them.
- `PROPRI_PUBLIQUE` (see Decisions).

### To do at deploy time (do not do from here)
1. MR, green pipeline, `deploy/ship.sh <short-sha>` (V10 is additive; take a backup first as usual).
2. The first parcel run is FULL (V10 resets its bootstrap flag): or run `POST /admin/import/parcelles?mode=full`, then
   `POST /admin/import/contexte?source=all&mode=full` (~6 min).
3. `VACUUM FULL parcelle;` once (locks the table for seconds), see CLAUDE.md.
4. Optional healthchecks.io check `40 10 * * *` and `CONTEXTE_HEALTHCHECK_PING_URL`.

### Anomalies
- opendata.swiss' API answered 403 after a few requests; I did not work around it (the licence check of the other layers
  uses SITG's own catalogue pages, which state the same access class).
- One SITG parcel has a self-intersecting outline (`ST_Intersection` threw): handled with `ST_MakeValid` and tested.

## Phase 2, Flutter: done (2026-11-09)
- Null-safe models (`Batiment`, `Restriction`, new `Parcelle` fields); new `parcelles_section.dart` (card per parcel,
  collapsed Bâtiments / Zone et restrictions only when data exists, EGRID copy, "Voir au registre foncier" in the
  in-app browser with a caption naming the State of Geneva's site and what is there). Nothing in lists or feeds.
- `flutter analyze` clean; `flutter test` 26 pass (parsing of missing/null/empty/malformed context, 0/1/2 parcels,
  new sections collapsed then expanded, building cap text, launch mode `inAppBrowserView`, EGRID copy, labels).
- Dev simulator (`56220C87…`, never the prod one) against the local backend with real data: dossier with 1 parcel, with
  2 parcels, with none, light and dark, collapsed and expanded (`app/build/parcel-screenshots/`, git-ignored, 15 files).
  Tapped the button: the Safari sheet opened over the app, then closed (`closeInAppWebView`) and the app was back on the
  details sheet (`safari-3-ferme-retour-app.png`). No CAPTCHA appeared.
- **Finding for the owner:** the ge.ch extract page opened by `lien_rf` showed, with no CAPTCHA and no login, the full
  names of the parcel's private co-owners (with birth dates). That is exactly why it is only linked, never read by us.
  The privacy page already says ge.ch may show owner data; consider whether the App Store review notes/answers should
  say that the button opens it. I deleted the two Safari screenshots that showed those names.
- Build number not bumped (still 1.0.1+5); version 1.1.0 and the TestFlight upload are for the next session.

# Progress: land-registry parcels (backend)

Merged into main (MR !13, `c0f0314`) and deployed to production on 2026-11-09 (see "Production" below). Conventions and rationale: CLAUDE.md, section "Parcels".

## Done
- V9 migration: `parcelle` (73,156 rows, 70 MB with index), `dossier_parcelle`, `sync_state` row.
- Import of SITG `CAD_PARCELLE_MENSU` (open data only, no owner fields): daily incremental on `DATEDT`
  (10:30), weekly full (Sunday 11:15) with a 10% shrink guard, `job_run` rows, optional own
  healthchecks.io check (`PARCELLE_HEALTHCHECK_PING_URL`), `POST /admin/import/parcelles`.
- Spatial join dossier ↔ parcel (~5 cm tolerant), stored, rewritten after every dossier and parcel import.
- API: `parcelles[]` (`egrid, commune, numero, surface_m2, lien_rf`) and `lien_registre_foncier` on
  `/dossiers` and all event feeds; existing fields unchanged.
- Tests: 153 backend tests green (new: link join, API shape, client paging, importer rules).

## Verified (local, 2026-11-09)
- Source count 73,156 = local 73,156; per-commune counts identical on all 48 communes.
- Second full import: `changed=0` (idempotent). Incremental against the live source: 64 parcels in a 10-day
  window, exactly 1 changed (a row I had corrupted by hand), repaired.
- 20 random dossiers vs SITG's own point-in-polygon query (in native EPSG:2056): the official parcel is always
  among ours; 1 in 10 also had a second, adjacent parcel (boundary point, see decisions).
- A failed run (SQL error during development) rolled back everything and left a FAILED `job_run` row.

## Decisions
- The layer is `CAD_PARCELLE_MENSU`, not `CAD_PLANRF` (RF plan sheets, no parcels).
- Keep all parcels with full geometry (70 MB): no simplification, no subset, no lazy lookup.
- ~5 cm tolerance instead of strict `ST_Intersects`: ~21% of SITG dossier points sit within 1 cm of a parcel
  edge, so strict picks a neighbour at random. 23% of dossiers therefore have 2+ parcels.
- Links are computed at import time, not at query time; the dossier-side link step runs after the dossier
  import's transaction and can only WARN (it never undoes an import or its notifications).
- No hard dependency on ge.ch pages: we only expose links. `lien_rf` = SITG's published extrait foncier link;
  `lien_registre_foncier` = `https://www.ge.ch/consulter-registre-foncier`.

## Site: privacy page (2026-11-09)
- `privacy.html` (only language: fr) gains a "Registre foncier" item: the button opens the State of Geneva's extract in the browser; ge.ch may show registry data incl. the owner's name under its own terms; we collect/store/display none of it and send nothing personal (the link carries parcel ids only). Parcel info added to the data-source item; date 9 novembre 2026. `index.html` footer mentions parcel data. Deployed with `deploy/ship.sh --sync` (backend untouched); `/` and `/privacy` return 200 with the new text.

## Next
1. Review/merge the branch (MR on GitLab), then deploy with `deploy/ship.sh`. Prod runs V8: V9 is additive and
   small. The first scheduled parcel run (10:30 next morning) is the bootstrap: a full import (~30 s) that links
   every dossier; until then `parcelles` is `[]`. Or run `POST /admin/import/parcelles?mode=full` right after the
   deploy. Create the optional healthchecks.io check (`30 10 * * *`) and set `PARCELLE_HEALTHCHECK_PING_URL`.
2. Flutter session: show `parcelles` in the details sheet; open `lien_rf` (per parcel) or
   `lien_registre_foncier` (fallback) in the browser. Do not fetch/parse those pages in the app. Tell users a
   boundary dossier can list two parcels.
3. Optional: expose `EXTRAIT_RDPPF_PDF` (public-law restrictions by EGRID); add a parcel-count line to the README.
4. Open question for the owner: the `lien_rf` extrait foncier page is ge.ch's; check once in a browser what it
   shows and that linking to it fits the privacy page / App Store answers (nothing is stored or read by us).

# Progress: land-registry parcels (Flutter)

Same branch. Build number 1.0.0+4 (not uploaded).

## Done
- Null-safe models (`Parcelle`, `Dossier.parcelles`, `lienRegistreFoncier`); details-sheet "Parcelle(s)" block
  (see CLAUDE.md "App" > Parcels). Rows and feeds untouched.
- `flutter analyze` clean; `flutter test` 17 pass (new `test/parcelles_test.dart`: older backend / null / malformed
  fields, 0/1/2 parcels, Swiss format, link fallbacks, copy EGRID).
- Run on the dev simulator against the local backend with real data, light and dark: 1 parcel, 2 parcels, none
  (screenshots in `app/build/parcel-screenshots/`, not committed). The links were not opened (not fetched, by rule).

## Notes
- No backend change needed. For one parcel the heading and card both say "Parcelle" (kept: simple, consistent).
- The simulator holding the prod subscription was not touched.

## Next
- Merge the branch, deploy the backend (see above), ship build 4 to TestFlight when wanted.

# Production deploy (2026-11-09)

Backend `c0f03147` (main), Flyway V8 -> V9. Pre-deploy dump taken and checked; MR and main pipelines green.
- First full parcel import (`POST /admin/import/parcelles?mode=full`, job_run 20): 73,156 parcels fetched = source
  count, 28.5 s, 26,119 links. Table 70 MB (whole database 114 MB).
- Dossiers: 21,276 = 16,437 with one parcel, 4,832 with two, 6 with three, 1 with none. `/dossiers` answers
  with `parcelles[]` filled (200 of 200 in central Geneva), existing fields unchanged, ~0.2 s over HTTPS.
- Resources: disk 8.1 GB of 58 GB used; the backend stayed under 280 MB and Postgres peaked near 210 MB during
  the import (limits 1.5 GB / 1.25 GB); no swap pressure, nothing restarted.
- Scheduling confirmed in the startup log: parcels 10:30 daily and Sunday 11:15, dossiers unchanged (10:15 /
  Sunday 10:45). `PARCELLE_HEALTHCHECK_PING_URL` is unset ("not set: no dead-man's-switch pings"), no WARN/ERROR.
- Not done on purpose: no healthchecks.io check created, nothing in `app/` / TestFlight / App Store Connect.

## Next
- Watch the first scheduled parcel run tomorrow 10:30 (`GET /admin/job-runs`: `parcelle-incremental`, SUCCESS,
  `fetched` small, `changed` near 0) and Sunday 11:15 (`parcelle-full`, `changed` 0).
- Optional: create the healthchecks.io check (`30 10 * * *`) and set `PARCELLE_HEALTHCHECK_PING_URL`.
- Ship app build 1.0.0+4 to TestFlight when wanted (the app already tolerates old/new backends).
- Rollback, if ever needed: `deploy/ship.sh <previous short sha>` (the previous image tag is `a9885824`'s
  revision in the registry); V9 tables are additive and stay.
