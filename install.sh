#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CURRENT_USER="$(id -un)"

echo "[1/4] Installing sudoers rules..."
sudo install -m 0440 "$SCRIPT_DIR/scripts/wisl-vault.sudoers" /etc/sudoers.d/wisl-vault
sudo visudo -c -f /etc/sudoers.d/wisl-vault

echo "[2/4] Installing NetworkManager dispatcher..."
sudo install -m 0755 "$SCRIPT_DIR/scripts/99-wisl-vault" \
    /etc/NetworkManager/dispatcher.d/99-wisl-vault

echo "[3/4] Installing systemd service..."
sed "s|WISL_USER|$CURRENT_USER|g; s|WISL_DIR|$SCRIPT_DIR|g" \
    "$SCRIPT_DIR/scripts/wisl-vault.service" \
    | sudo tee /etc/systemd/system/wisl-vault.service > /dev/null
sudo systemctl daemon-reload
sudo systemctl enable wisl-vault.service

echo "[4/4] Done."
echo "  The server starts automatically on boot."
echo "  To start now:  sudo systemctl start wisl-vault"
echo "  To stop:       sudo systemctl stop wisl-vault"
echo "  Logs:          journalctl -u wisl-vault -f"
