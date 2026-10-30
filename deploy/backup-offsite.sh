#!/bin/bash
# Nightly backup with an off-site copy, on perimetre-prod: /opt/perimetre/backup-offsite.sh, run at 02:30
# Europe/Zurich by /etc/cron.d/perimetre-backup (installed by bootstrap.sh) as ubuntu. Output goes to the
# journal: journalctl -t perimetre-backup
#
# 1. backup.sh: the local pg_dump, verified, 14 days kept;
# 2. that dump to the restic repository on Swiss Backup (S3) as /perimetre.dump, tag pgdump;
# 3. retention: 7 daily, 4 weekly, 6 monthly snapshots (forget --prune), then restic check;
# 4. healthchecks.io (BACKUP_PING_URL, a check of its own): /start, then the URL on success or /fail
#    with the output's tail. Unset = no pings; a failed ping never fails the backup.
# Credentials: /opt/perimetre/.restic.env, written by deploy/set-restic-creds.sh. Without it the local
# dump is still made and the run fails.
set -euo pipefail
umask 077

DIR=/opt/perimetre
ENV_FILE=$DIR/.restic.env

if [ -f "$ENV_FILE" ]; then set -a; . "$ENV_FILE"; set +a; fi
ping() {
	[ -n "${BACKUP_PING_URL:-}" ] || return 0
	curl -fsS -m 10 --retry 3 -o /dev/null --data-binary @- "$BACKUP_PING_URL$1" \
		|| echo "WARN: healthchecks ping ${1:-/success} failed"
}

log=$(mktemp)
trap 'rm -f "$log"' EXIT
ping /start < /dev/null

set +e
(
	set -euo pipefail
	"$DIR/backup.sh"
	[ -f "$ENV_FILE" ] || { echo "no $ENV_FILE: run deploy/set-restic-creds.sh (CLAUDE.local.md)"; exit 1; }
	dump=$(ls -1t "$DIR"/backups/perimetre-*.dump | head -1)
	restic backup -q --host perimetre-prod --tag pgdump --stdin --stdin-filename perimetre.dump < "$dump"
	restic forget -q --host perimetre-prod --tag pgdump --keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune
	restic check -q
	echo "offsite OK: $(basename "$dump") -> $RESTIC_REPOSITORY, $(restic snapshots -q --tag pgdump --json | grep -o '"short_id"' | wc -l) snapshots"
) > "$log" 2>&1
status=$?
set -e

cat "$log"
if [ $status = 0 ]; then ping "" < "$log"; else tail -c 10000 "$log" | ping /fail; fi
exit $status
