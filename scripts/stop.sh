#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "[Docker] Stopping containers..."
docker compose -f "$PROJECT_DIR/docker-compose.yml" down

echo "[mDNS] Stopping avahi-daemon..."
sudo systemctl stop avahi-daemon

UPLOADS_DIR="$PROJECT_DIR/uploads"
echo "[Uploads] Cleaning uploads directory..."
rm -rf "$UPLOADS_DIR"
mkdir -p "$UPLOADS_DIR"

echo "Done. wislvault.local is no longer available on the network."
