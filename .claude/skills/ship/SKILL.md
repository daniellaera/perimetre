---
name: ship
description: Use when the user asks to ship, deploy, release, or push Périmètre changes to production — or says something like "ship it", "deploy this", "get this on the server". Pushes main (CI builds the image), waits for the pipeline, runs deploy/ship.sh, verifies.
---

# Ship Périmètre

## Target

One server. There is no staging.

| Target | SSH alias | Deploy dir | Health | Env file |
|---|---|---|---|---|
| Infomaniak VPS Lite | `perimetre-prod` | `/opt/perimetre` | `https://api.perimetre.ch/actuator/health` | `.env.prod` (server only) |

Containers: `perimetre-caddy-1`, `perimetre-backend-1`, `perimetre-db-1` (compose project `perimetre`).
Details in CLAUDE.md, "Production".

## Before anything: what changed

`git status`, `git diff --stat origin/main`. Only `app/` changed → no server deploy: run
`flutter analyze` and report. Only `deploy/site/` or `deploy/Caddyfile` changed → no new image needed:
`deploy/ship.sh` alone syncs and reloads Caddy.

## Backend changed

1. **Test first.** Local DB up (`docker compose up -d`), then `cd backend && ./gradlew test`. CI does not
   run the tests (it has no PostGIS). Failing tests: STOP and report, do not deploy.
2. **Push main.** CI (`.gitlab-ci.yml`, shared runners) builds
   `registry.gitlab.com/daniellaera1/perimetre/backend:<short-sha>` and `:latest`. Pushing is the user's
   call: ask if they have not said "ship"/"deploy".
3. **Wait for the pipeline** of that commit: `glab ci status --live` (or `glab ci view`). Failed → read
   the job log (`glab ci trace`), report, stop.
4. **Ship.** `deploy/ship.sh` (`:latest`) or `deploy/ship.sh <short-sha>` to pin. It syncs the deploy
   files, checks `.env.prod` (names only) and the Firebase key, saves the running backend's log to
   `/opt/perimetre/logs/`, pulls, `up -d`, reloads Caddy, waits for container health, prints the image
   (id, digest, `org.opencontainers.image.revision`) and the public health JSON. Non-zero exit = failed:
   it prints the last 60 log lines; report them.
5. **Check the revision** it printed is the commit you pushed, and read the startup log for WARN/ERROR:
   `ssh perimetre-prod 'docker logs perimetre-backend-1 2>&1 | grep -E " (WARN|ERROR) "'`. The Flyway
   `extension "postgis" already exists` WARN is expected.

Rollback: `deploy/ship.sh <previous short-sha>` (tags in the GitLab registry). A rolled-back box returns
to `:latest` on the next plain `ship.sh`.

## Flyway migration added

Never edit an applied migration. What prod has:
```bash
ssh perimetre-prod "cd /opt/perimetre && docker compose -f docker-compose.prod.yml --env-file .env.prod \
  exec -T db sh -c 'psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"SELECT version, description FROM flyway_schema_history ORDER BY installed_rank\"'"
```
A migration that rewrites data: run `/opt/perimetre/backup.sh` on the box first.

## Rules

- Never scp `.env.prod` or anything into `secrets/`: the server's copies are the only ones. Never print
  their values (names only).
- Never `docker compose down -v` on the server: it deletes the database and Caddy's certificates.
- `/admin/**` needs `Authorization: Bearer $ADMIN_TOKEN`. The token is in `.env.prod` and the user's
  Vaultwarden; do not echo it into chat or logs.
- Report what you deployed (revision), and what the health check returned. Don't claim success without it.
