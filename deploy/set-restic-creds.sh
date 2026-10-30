#!/bin/bash
# Stores the Swiss Backup S3 credentials of the off-site backup (backup-offsite.sh) on perimetre-prod.
# Interactive, run by hand (the keys are typed at a hidden prompt, never passed as arguments or logged):
#
#   ssh -t perimetre-prod sudo /opt/perimetre/deploy/set-restic-creds.sh
#
# First run: generates the restic repository password, writes /opt/perimetre/.restic.env (600, owned by
# ubuntu, which runs the backup), prints the password ONCE (save it in Vaultwarden: without it the
# off-site copies cannot be read) and initialises the repository. A later run replaces the S3 keys and the
# ping URL and keeps the password, so it never orphans the existing repository.
set -euo pipefail
umask 077

DIR=/opt/perimetre
ENV_FILE=$DIR/.restic.env
OWNER=ubuntu
# Swiss Backup S3 device "perimetre-prod". restic creates the bucket on init.
REPOSITORY=s3:https://s3.swiss-backup02.infomaniak.com/perimetre
REGION=us-east-1

[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }
[ -t 0 ] || { echo "needs a terminal: ssh -t perimetre-prod sudo $0" >&2; exit 1; }
command -v restic >/dev/null || { echo "restic is not installed (apt-get install restic)" >&2; exit 1; }

# Read, never sourced: the file belongs to ubuntu and this runs as root.
current() { [ -f "$ENV_FILE" ] && sed -n "s/^$1=//p" "$ENV_FILE" | head -1 || true; }
ask_secret() {
	local value
	read -rsp "$1: " value; echo >&2
	[ -n "$value" ] || { echo "empty, aborted" >&2; exit 1; }
	[[ $value =~ ^[^[:space:]\'\"\\]+$ ]] || { echo "unexpected characters, aborted" >&2; exit 1; }
	printf '%s' "$value"
}

password=$(current RESTIC_PASSWORD)
new_password=
if [ -z "$password" ]; then
	password=$(openssl rand -hex 32)
	new_password=1
fi

echo "Swiss Backup S3 device perimetre-prod ($REPOSITORY)" >&2
access_key=$(ask_secret "Access key")
secret_key=$(ask_secret "Secret key")
ping_url=$(current BACKUP_PING_URL)
read -rp "healthchecks.io ping URL of the backup check (Enter = ${ping_url:+keep the current one}${ping_url:-none}): " input
if [ -n "$input" ]; then
	[[ $input =~ ^https://[^[:space:]\'\"\\]+$ ]] || { echo "not an https URL, aborted" >&2; exit 1; }
	ping_url=${input%/}
fi

tmp=$(mktemp "$DIR/.restic.env.XXXXXX")
trap 'rm -f "$tmp"' EXIT
cat > "$tmp" <<ENV
# Off-site backup (restic -> Swiss Backup S3). Written by set-restic-creds.sh; sourced by backup-offsite.sh
# and restic-restore-test.sh. The repository password is also in Vaultwarden.
RESTIC_REPOSITORY=$REPOSITORY
RESTIC_PASSWORD=$password
AWS_ACCESS_KEY_ID=$access_key
AWS_SECRET_ACCESS_KEY=$secret_key
AWS_DEFAULT_REGION=$REGION
BACKUP_PING_URL=$ping_url
ENV
chown "$OWNER:$OWNER" "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$ENV_FILE"
trap - EXIT
echo "wrote $ENV_FILE (600, $OWNER)" >&2

if [ -n "$new_password" ]; then
	cat >&2 <<MSG

================================================================================
 restic repository password (shown once, save it in Vaultwarden NOW as
 "Périmètre restic (Swiss Backup)"; without it the off-site backups are lost):

   $password

================================================================================
MSG
	read -rp "Press Enter once it is saved. " _
fi

restic_as_owner() { sudo -u "$OWNER" -H bash -c 'set -a; . "$0"; set +a; exec restic "$@"' "$ENV_FILE" "$@"; }
if restic_as_owner cat config >/dev/null 2>&1; then
	echo "repository already initialised, password and keys OK" >&2
elif restic_as_owner init; then
	echo "repository initialised" >&2
else
	echo "restic init failed: wrong S3 keys, or the repository exists with another password" >&2
	exit 1
fi
