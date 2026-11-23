#!/usr/bin/env bash
# Starts a throwaway Samba server on port 4455 with share "test" (user smbuser / testpass).
set -euo pipefail
sudo apt-get update -qq
sudo apt-get install -y -qq samba
sudo mkdir -p /srv/smbtest
sudo useradd -M smbuser 2>/dev/null || true
sudo chown smbuser /srv/smbtest
printf '[global]\n server role = standalone server\n map to guest = never\n smb ports = 4455\n[test]\n path = /srv/smbtest\n read only = no\n valid users = smbuser\n' | sudo tee /etc/samba/smb.conf >/dev/null
printf 'testpass\ntestpass\n' | sudo smbpasswd -a -s smbuser
sudo systemctl restart smbd || sudo smbd -D
sleep 2
