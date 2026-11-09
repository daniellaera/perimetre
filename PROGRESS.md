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
