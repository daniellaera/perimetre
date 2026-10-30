#!/bin/bash
# Deploy Périmètre to perimetre-prod. Run from the Mac, any directory:
#
#   deploy/ship.sh 1a2b3c4d             # deploy that image (CI's 8-char short-sha tag): new or rollback
#   deploy/ship.sh --no-pull 1a2b3c4d   # the same, image already on the box (registry down, or
#                                       # loaded by hand: docker save | ssh perimetre-prod docker load)
#   deploy/ship.sh --sync               # deploy files only (site/, Caddyfile, backup scripts): never
#                                       # pulls, never touches the backend
#
# A deploy always names its image: there is no :latest default. The box tags that image backend:deployed
# (a local tag) and compose runs the backend from :deployed, so a later hand-run `docker compose up` (env
# change, token rotation) keeps the deployed image and never falls back to whatever :latest is.
#
# 1. syncs the tracked deploy files (compose, Caddyfile, site/, the backup scripts) to
#    /opt/perimetre -- never .env.prod, .restic.env or secrets/, which exist only there;
# 2. checks that .env.prod defines every variable (names only) and that compose
#    resolves the file, before anything is torn down;
# 3. --sync: reloads Caddy (a bind-mounted Caddyfile change does not recreate it) and stops;
# 4. saves the running backend's log (a recreate deletes it);
# 5. docker compose pull (backend :<sha>), tags it :deployed, up -d, reloads Caddy;
# 6. waits for the backend's container health, then the public health URL; prints the image and
#    the health JSON. Exits non-zero on any failure.
set -euo pipefail

TARGET=perimetre-prod
DIR=/opt/perimetre
HEALTH=https://api.perimetre.ch/actuator/health
IMAGE=registry.gitlab.com/daniellaera1/perimetre/backend
PULL=1
SYNC_ONLY=
TAG=
case "${1:-}" in
	--sync) SYNC_ONLY=1 ;;
	--no-pull) PULL=; TAG=${2:-} ;;
	*) TAG=${1:-} ;;
esac
if [ -z "$SYNC_ONLY" ] && ! [[ $TAG =~ ^[0-9a-f]{8}$ ]]; then
	echo "usage: $0 <8-char short sha> | --no-pull <short sha> | --sync   (no :latest deploys)" >&2
	exit 2
fi
REQUIRED_VARS="SPRING_PROFILES_ACTIVE DB_URL DB_USER DB_PASSWORD FIREBASE_CREDENTIALS ADMIN_TOKEN"
HEALTH_TIMEOUT=180

cd "$(dirname "$0")"
say() { echo "== $*"; }
fail() { echo "SHIP FAILED: $*" >&2; exit 1; }
remote() { ssh "$TARGET" "cd $DIR && $1"; }
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"

say "target $TARGET:$DIR, ${SYNC_ONLY:+files only, }backend ${TAG:-unchanged}"
[ -n "$(git status --porcelain -- .)" ] && echo "WARN: deploy/ has uncommitted changes; they are shipped as they are"

say "sync deploy files"
# tar, not rsync from the Mac (macOS's openrsync has no --chmod). Caddy mounts
# the caddy/ and site/ directories, not single files: a file replaced by rename
# would stay invisible behind a single-file bind mount.
COPYFILE_DISABLE=1 tar -cf - --no-xattrs --exclude .DS_Store docker-compose.prod.yml Caddyfile backup.sh backup-offsite.sh restic-restore-test.sh \
	set-restic-creds.sh site \
	| ssh "$TARGET" "set -e; cd $DIR; rm -rf .sync; mkdir -m 700 .sync; tar -xf - -C .sync --no-same-owner; \
		install -m 644 .sync/docker-compose.prod.yml docker-compose.prod.yml; \
		install -m 755 .sync/backup.sh .sync/backup-offsite.sh .sync/restic-restore-test.sh .; \
		install -d -m 755 deploy; install -m 755 .sync/set-restic-creds.sh deploy/; \
		install -d -m 755 caddy site; \
		install -m 644 .sync/Caddyfile caddy/Caddyfile; \
		chmod -R u=rwX,go=rX .sync/site; rsync -rlt --delete .sync/site/ site/; \
		rm -rf .sync" || fail "sync to $TARGET:$DIR"

say "check .env.prod and the Firebase key"
missing=$(ssh "$TARGET" "for v in $REQUIRED_VARS; do grep -qE \"^\$v=.+\" $DIR/.env.prod || echo \$v; done; \
	[ -f $DIR/secrets/firebase-service-account.json ] || echo secrets/firebase-service-account.json") \
	|| fail "cannot read $DIR/.env.prod"
[ -z "$missing" ] || fail "missing in $DIR/.env.prod or secrets/: $(echo "$missing" | tr '\n' ' ')"
remote "$COMPOSE config --quiet" || fail "compose does not resolve docker-compose.prod.yml"

reload_caddy() {
	remote "$COMPOSE exec -T caddy caddy reload --config /etc/caddy/Caddyfile" >/dev/null 2>&1 \
		|| fail "caddy reload (check: $COMPOSE logs caddy)"
}

if [ -n "$SYNC_ONLY" ]; then
	say "reload caddy"
	reload_caddy
	# A box deployed before :deployed existed: tag the running backend's image once, so hand-run compose
	# commands keep it.
	ssh "$TARGET" "docker image inspect $IMAGE:deployed >/dev/null 2>&1 \
		|| docker tag \$(docker inspect perimetre-backend-1 --format '{{.Image}}') $IMAGE:deployed"
	say "synced (backend untouched; compose changes apply with the next deploy)"
	exit 0
fi

say "save the running backend's log"
remote "mkdir -p logs && chmod 700 logs && \
	if docker inspect perimetre-backend-1 >/dev/null 2>&1; then \
		f=logs/backend-\$(date +%Y%m%d-%H%M%S)-\$(docker inspect perimetre-backend-1 --format '{{.Image}}' | cut -c8-19).log; \
		docker logs perimetre-backend-1 > \$f 2>&1; chmod 600 \$f; \
		echo \"saved \$f (\$(wc -l < \$f) lines, ERROR: \$(grep -c ' ERROR ' \$f), WARN: \$(grep -c ' WARN ' \$f))\"; \
		ls -1t logs/backend-*.log | tail -n +31 | xargs -r rm -f; \
	else echo 'no backend container yet'; fi"

if [ -n "$PULL" ]; then
	say "pull"
	remote "BACKEND_TAG=$TAG $COMPOSE pull --quiet" \
		|| fail "docker compose pull (registry login on the box: CLAUDE.md, \"Production\")"
fi
say "tag $TAG as :deployed, up"
ssh "$TARGET" "docker tag $IMAGE:$TAG $IMAGE:deployed" || fail "image $IMAGE:$TAG is not on the box"
remote "$COMPOSE up -d --remove-orphans" || fail "docker compose up"
reload_caddy

say "wait for backend health (up to ${HEALTH_TIMEOUT}s)"
status=
for _ in $(seq 1 $((HEALTH_TIMEOUT / 5))); do
	status=$(ssh "$TARGET" "docker inspect perimetre-backend-1 --format '{{.State.Health.Status}}'" 2>/dev/null || true)
	[ "$status" = healthy ] && break
	[ "$status" = unhealthy ] && break
	sleep 5
done
if [ "$status" != healthy ]; then
	ssh "$TARGET" "docker logs --tail 60 perimetre-backend-1" >&2 || true
	fail "backend is ${status:-not running} after ${HEALTH_TIMEOUT}s"
fi

say "image"
ssh "$TARGET" "img=\$(docker inspect perimetre-backend-1 --format '{{.Image}}'); \
	docker image inspect \$img --format 'id       {{.Id}}
digest   {{join .RepoDigests \" \"}}
revision {{index .Config.Labels \"org.opencontainers.image.revision\"}}'"

say "health $HEALTH"
# Retried for 2 minutes: on a first start Caddy is still obtaining its certificates.
body=
for _ in $(seq 1 24); do
	body=$(curl -fsS --max-time 10 "$HEALTH" 2>/dev/null) && break
	sleep 5
done
[ -n "$body" ] || fail "public health check failed (backend is healthy: check Caddy, $COMPOSE logs caddy)"
echo "$body"
echo
say "shipped"
