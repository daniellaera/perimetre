#!/bin/bash
# Nightly database backup on perimetre-prod: /opt/perimetre/backup.sh, run at
# 02:30 Europe/Zurich by /etc/cron.d/perimetre-backup (installed by
# bootstrap.sh) as ubuntu. Output goes to the journal:
#   journalctl -t perimetre-backup
#
# pg_dump in custom format to /opt/perimetre/backups, checked with pg_restore
# --list, 14 days kept. Local only: the off-site copy (Swiss Backup) is a
# manual step, see CLAUDE.md "Production".
set -euo pipefail
umask 077

DIR=/opt/perimetre
OUT=$DIR/backups
KEEP_DAYS=14
compose() { docker compose -f "$DIR/docker-compose.prod.yml" --env-file "$DIR/.env.prod" "$@"; }

file=$OUT/perimetre-$(date +%Y%m%d-%H%M%S).dump
# The image's pg_hba trusts the local socket, so no password is needed inside
# the container. Written under a temporary name until it has been verified.
compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$file.partial"
compose exec -T db pg_restore --list < "$file.partial" > /dev/null
mv "$file.partial" "$file"

find "$OUT" -maxdepth 1 -name 'perimetre-*.dump' -mtime +$((KEEP_DAYS - 1)) -delete
find "$OUT" -maxdepth 1 -name 'perimetre-*.dump.partial' -mtime +1 -delete

echo "backup OK: $file ($(du -h "$file" | cut -f1)), $(find "$OUT" -maxdepth 1 -name 'perimetre-*.dump' | wc -l) kept"
