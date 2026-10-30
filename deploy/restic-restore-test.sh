#!/bin/bash
# Restore test of the off-site backup, on perimetre-prod as ubuntu (by hand, e.g. monthly):
#
#   ssh perimetre-prod /opt/perimetre/restic-restore-test.sh [snapshot]   # default: latest
#
# Fetches /perimetre.dump from Swiss Backup with restic, compares it with the local dump of the same night
# when that is still there, restores it into a throwaway PostGIS container (same image as prod, no network,
# removed afterwards) and prints every table's row count next to prod's. Fails when the restore reports an
# error or a table that has rows in prod comes back empty. Writes nothing to prod.
set -euo pipefail
umask 077

DIR=/opt/perimetre
NAME=perimetre-restore-test
SNAPSHOT=${1:-latest}
set -a; . "$DIR/.restic.env"; set +a
compose() { docker compose -f "$DIR/docker-compose.prod.yml" --env-file "$DIR/.env.prod" "$@"; }

docker rm -f "$NAME" >/dev/null 2>&1 || true   # left over from an interrupted run
work=$(mktemp -d)
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT

echo "== snapshot $SNAPSHOT"
restic snapshots -q --tag pgdump "$SNAPSHOT"
restic dump -q --tag pgdump "$SNAPSHOT" /perimetre.dump > "$work/perimetre.dump"
sum=$(sha256sum "$work/perimetre.dump" | cut -d' ' -f1)
echo "fetched $(du -h "$work/perimetre.dump" | cut -f1), sha256 $sum"
local_copy=$(sha256sum "$DIR"/backups/perimetre-*.dump | awk -v s="$sum" '$1 == s {print $2}' | head -1)
echo "local dump with the same content: ${local_copy:-none (older than the local 14 days, or not the latest)}"

echo "== restore into $NAME"
image=$(compose config --images | grep postgis)
docker run -d --name "$NAME" --network none --memory 1g \
	-e POSTGRES_USER=perimetre -e POSTGRES_DB=perimetre -e POSTGRES_PASSWORD=restore-test \
	-v "$work:/restore:ro" "$image" >/dev/null
# The entrypoint's temporary server (init scripts: PostGIS extensions) listens on the socket only, so
# TCP on loopback answers once the final server is up.
for _ in $(seq 1 60); do
	docker exec "$NAME" pg_isready -q -U perimetre -h 127.0.0.1 2>/dev/null && break
	sleep 2
done
docker exec "$NAME" pg_restore -U perimetre -d perimetre --clean --if-exists --no-owner --exit-on-error /restore/perimetre.dump

echo "== row counts (restored vs prod now)"
restored_sql() { docker exec "$NAME" psql -U perimetre -d perimetre -Atc "$1"; }
prod_sql() { compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$0"' "$1"; }
fail=
for t in $(prod_sql "select tablename from pg_tables where schemaname = 'public' and tablename <> 'spatial_ref_sys' order by 1"); do
	p=$(prod_sql "select count(*) from \"$t\"")
	r=$(restored_sql "select count(*) from \"$t\"" 2>/dev/null || echo missing)
	printf '%-24s restored %8s   prod %8s\n' "$t" "$r" "$p"
	if [ "$p" != 0 ] && { [ "$r" = missing ] || [ "$r" = 0 ]; }; then fail=1; fi
done
[ -z "$fail" ] || { echo "RESTORE TEST FAILED: a table is missing or empty in the restored copy" >&2; exit 1; }
echo "== restore test OK"
