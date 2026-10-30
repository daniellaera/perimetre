#!/bin/bash
# Périmètre server bootstrap: turns a fresh Ubuntu 24.04 VPS into a Docker host
# for deploy/docker-compose.prod.yml. Idempotent: every step checks or
# overwrites, so a second run changes nothing and is safe after an upgrade.
#
#   ssh perimetre-prod 'sudo bash -s' < deploy/bootstrap.sh
#
# It does not touch the app itself (compose file, env file, secrets): ship.sh
# syncs the tracked files, .env.prod and the Firebase key are written once by
# hand (see CLAUDE.md, "Production").
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive LC_ALL=C

[ "$(id -u)" = 0 ] || { echo "run as root (sudo bash -s)" >&2; exit 1; }

DEPLOY_USER=ubuntu
DIR=/opt/perimetre
APT_OPTS=(-y -q -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

log() { echo "== $*"; }

# --- packages ----------------------------------------------------------------
log "apt update/upgrade"
apt-get update -q
apt-get "${APT_OPTS[@]}" upgrade
# python3-systemd: fail2ban's systemd backend reads sshd from the journal.
apt-get "${APT_OPTS[@]}" install ca-certificates curl gnupg unattended-upgrades fail2ban python3-systemd restic

log "unattended-upgrades"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
systemctl enable --now unattended-upgrades >/dev/null

log "fail2ban (sshd jail)"
cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
backend = systemd
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable fail2ban >/dev/null
systemctl restart fail2ban

# --- swap --------------------------------------------------------------------
log "swap (2 GB, swappiness 10)"
if [ ! -f /swapfile ]; then
	fallocate -l 2G /swapfile
	chmod 600 /swapfile
	mkswap /swapfile >/dev/null
fi
swapon --show=NAME --noheadings | grep -qx /swapfile || swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness = 10' > /etc/sysctl.d/99-perimetre.conf
sysctl -q -p /etc/sysctl.d/99-perimetre.conf

# --- Docker Engine + compose plugin (Docker's apt repo) ----------------------
log "docker"
install -d -m 755 /etc/apt/keyrings
if [ ! -s /etc/apt/keyrings/docker.asc ]; then
	curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
	chmod 644 /etc/apt/keyrings/docker.asc
fi
# shellcheck source=/dev/null
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
	> /etc/apt/sources.list.d/docker.list
apt-get update -q
apt-get "${APT_OPTS[@]}" install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker >/dev/null
usermod -aG docker "$DEPLOY_USER"

# --- sshd --------------------------------------------------------------------
# sshd keeps the first value it reads and Include comes first in sshd_config,
# so 10- wins over the cloud image's 60-cloudimg-settings.conf.
log "sshd"
cat > /etc/ssh/sshd_config.d/10-perimetre.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sshd -t
systemctl reload ssh

# --- timezone ----------------------------------------------------------------
log "timezone Europe/Zurich"
timedatectl set-timezone Europe/Zurich
# cron reads the zone at start; the backup entry below is in local time.
systemctl restart cron

# --- directories, backup cron ------------------------------------------------
log "directories"
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 755 "$DIR"
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 700 "$DIR/secrets" "$DIR/backups"

log "backup cron (02:30 Europe/Zurich)"
# backup-offsite.sh (local pg_dump via backup.sh, then restic to Swiss Backup) is
# synced by ship.sh; until then, and until set-restic-creds.sh has written
# .restic.env, the entry fails harmlessly (logged under the perimetre-backup
# tag: journalctl -t perimetre-backup).
cat > /etc/cron.d/perimetre-backup <<EOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 2 * * * $DEPLOY_USER $DIR/backup-offsite.sh 2>&1 | logger -t perimetre-backup
EOF
chmod 644 /etc/cron.d/perimetre-backup

log "done"
echo "swap: $(swapon --show=NAME,SIZE --noheadings)  swappiness: $(cat /proc/sys/vm/swappiness)"
echo "docker: $(docker --version)  compose: $(docker compose version --short)"
echo "timezone: $(timedatectl show -p Timezone --value)"
echo "sshd: $(sshd -T | grep -E '^(passwordauthentication|permitrootlogin) ' | tr '\n' ' ')"
echo "fail2ban: $(fail2ban-client status sshd | grep -E 'Currently banned' | tr -s '\t ' ' ')"
[ -f /var/run/reboot-required ] && echo "NOTE: reboot required (kernel/libc upgrade)" || true
