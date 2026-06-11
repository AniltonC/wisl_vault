#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Check if wislvault.local is already served by another host on the network
RESOLVED_IP=$(avahi-resolve-host-name -4 wislvault.local 2>/dev/null | awk '{print $2}')
if [ -n "$RESOLVED_IP" ]; then
    LOCAL_IPS=$(hostname -I)
    if echo "$LOCAL_IPS" | grep -qw "$RESOLVED_IP"; then
        echo "[mDNS] wislvault.local already points to this host ($RESOLVED_IP), restarting..."
    else
        echo "[Server] wislvault.local is already running on another host ($RESOLVED_IP). Skipping initialization."
        exit 0
    fi
fi

# Install avahi if not present
if ! command -v avahi-daemon &>/dev/null; then
    echo "[mDNS] Installing avahi-daemon..."
    sudo apt-get install -y avahi-daemon
fi

# Write avahi config — enable-dbus=yes is required because the Ubuntu systemd unit
# uses Type=dbus and waits for avahi to register org.freedesktop.Avahi on the bus.
sudo tee /etc/avahi/avahi-daemon.conf > /dev/null << 'EOF'
[server]
host-name=wislvault
domain-name=local
use-ipv4=yes
use-ipv6=no
enable-dbus=yes

[publish]
publish-addresses=yes
publish-workstation=yes

[reflector]
enable-reflector=no

[rlimits]
rlimit-nproc=3
EOF

echo "[mDNS] Starting avahi-daemon on host..."
sudo systemctl enable avahi-daemon
sudo systemctl restart avahi-daemon

# Post-start verification: covers simultaneous boot of multiple hosts.
# Wait for avahi probing window (~3s) then confirm this host won wislvault.local.
sleep 5
WINNER_IP=$(avahi-resolve-host-name -4 wislvault.local 2>/dev/null | awk '{print $2}')
LOCAL_IPS=$(hostname -I)
if [ -n "$WINNER_IP" ] && ! echo "$LOCAL_IPS" | grep -qw "$WINNER_IP"; then
    echo "[mDNS] Conflict lost. wislvault.local is at $WINNER_IP. Stopping."
    sudo systemctl stop avahi-daemon
    exit 0
fi
echo "[mDNS] wislvault.local confirmed on this host ($(hostname -I | awk '{print $1}'))"

UPLOADS_DIR="$PROJECT_DIR/uploads"
echo "[Uploads] Cleaning uploads directory..."
rm -rf "$UPLOADS_DIR"
mkdir -p "$UPLOADS_DIR"

echo "[Uploads] Generating test files (~4 min total)..."
openssl rand -out "$UPLOADS_DIR/test_file_500MB.bin"   524288000
openssl rand -out "$UPLOADS_DIR/test_file_1GB.bin"    1073741824
openssl rand -out "$UPLOADS_DIR/test_file_2GB.bin"    2147483648
openssl rand -out "$UPLOADS_DIR/test_file_5GB.bin"    5368709120
openssl rand -out "$UPLOADS_DIR/test_file_10GB.bin"  10737418240
openssl rand -out "$UPLOADS_DIR/test_file_20GB.bin"  21474836480
echo "[Uploads] Test files ready."

echo "[Docker] Waiting for Docker to be available..."
DOCKER_TIMEOUT=120
DOCKER_ELAPSED=0
until docker info &>/dev/null; do
    if [ "$DOCKER_ELAPSED" -ge "$DOCKER_TIMEOUT" ]; then
        echo "[Docker] Timed out after ${DOCKER_TIMEOUT}s. Is Docker running?"
        exit 1
    fi
    sleep 5
    DOCKER_ELAPSED=$((DOCKER_ELAPSED + 5))
done
echo "[Docker] Docker is ready."

# Start the Docker stack, forwarding any extra arguments (e.g. -d, --build)
docker compose -f "$PROJECT_DIR/docker-compose.yml" up --build "$@"
