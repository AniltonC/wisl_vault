# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

Local-network file storage for testing smartphone uploads. No authentication. The computer runs the stack and phones on the same Wi-Fi connect to it by IP.

The server is accessed via mDNS at `http://wislvault.local` — never by raw IP. Tests always target this hostname. If another host on the network already owns `wislvault.local`, the service stops completely rather than start under any other name.

## Repository layout

```
WiSL_Vault/
├── install.sh                  # One-time system setup (run manually, once per machine)
├── scripts/
│   ├── start.sh                # mDNS check → test file generation → docker compose up
│   ├── stop.sh                 # docker compose down → stop avahi → clean uploads
│   ├── wisl-vault.service      # systemd unit template (WISL_USER / WISL_DIR placeholders)
│   ├── wisl-vault.sudoers      # NOPASSWD rules for automated scripts
│   └── 99-wisl-vault           # NetworkManager dispatcher (restarts on network switch)
├── backend/                    # FastAPI app
├── frontend/                   # React + Vite + nginx
├── android/                    # Native Android app (Kotlin)
├── uploads/                    # Test files (recreated on each start)
└── docker-compose.yml
```

Only `install.sh` lives at the root. All other scripts are under `scripts/`.

## Auto-start on boot (systemd)

The service is managed by systemd. One-time installation:

```bash
./install.sh
```

`install.sh` does four things:
1. Installs `scripts/wisl-vault.sudoers` → `/etc/sudoers.d/wisl-vault`
2. Installs `scripts/99-wisl-vault` → `/etc/NetworkManager/dispatcher.d/99-wisl-vault`
3. Substitutes `WISL_USER` / `WISL_DIR` in `scripts/wisl-vault.service` and writes it to `/etc/systemd/system/wisl-vault.service`
4. Runs `systemctl daemon-reload` and `systemctl enable wisl-vault.service`

After install, the service starts automatically on every boot. Manual controls:

```bash
sudo systemctl start wisl-vault
sudo systemctl stop wisl-vault
journalctl -u wisl-vault -f      # live logs
```

### systemd unit design notes

- `Type=oneshot` + `RemainAfterExit=yes` — `start.sh` exits after `docker compose up -d`; the unit stays `active`
- `TimeoutStartSec=600` — covers ~4 min file generation + 5 s avahi wait + up to 120 s Docker wait
- **No `Requires=docker.service`** — Docker Desktop on Linux does **not** register `docker.service` in systemd (only Docker Engine/docker-ce does). The Docker readiness check is done inside `start.sh` via a polling loop instead.

## mDNS conflict handling

`start.sh` always verifies that `wislvault.local` resolves to this host before starting. If another host already owns the name, the service stops — it never starts under any alternative hostname.

**Pre-start check** (network already has another host):
- Resolves `wislvault.local` before configuring avahi
- If the IP doesn't belong to this host → exit immediately

**Post-start verification** (simultaneous boot race):
- After `systemctl restart avahi-daemon`, wait 5 s for the mDNS probing window to settle
- Resolve again: if `wislvault.local` points elsewhere → `systemctl stop avahi-daemon` + exit

**Network switch** (`99-wisl-vault` NM dispatcher):
- Triggers on interface `up` events
- If `wisl-vault.service` is active, runs `systemctl restart wisl-vault`
- The restart re-runs `start.sh`, which performs a fresh conflict check against the new network

## Running locally (without Docker)

**Backend** — requires [uv](https://docs.astral.sh/uv/):
```bash
cd backend
uv sync
uv run fastapi dev main.py --host 0.0.0.0   # hot-reload on :8000
```

Files are stored in `backend/uploads/` (created automatically).

**Frontend** — requires Node 20+:
```bash
cd frontend
npm install
npm run dev   # Vite dev server on :5173
```

Vite proxies `/api/*` → `http://localhost:8000` via `vite.config.js`, so no CORS or hardcoded URLs are needed in dev.

To expose the dev server to smartphones on the network, `host: '0.0.0.0'` is already set in `vite.config.js`. The terminal will print the `Network:` URL to share.

## Running with Docker

```bash
docker compose up --build
```

Frontend served by nginx on port 80. Phones connect to `http://wislvault.local`.

## Architecture

```
Request from browser/phone
  → nginx :80
      /          → React SPA (static files)
      /api/*     → FastAPI :8000 (prefix stripped by nginx)
```

**Backend (`backend/main.py`)** — single-file FastAPI app. Routes: `GET /files`, `POST /upload`, `GET /files/{filename}`, `DELETE /files/{filename}`. Upload dir is read from `UPLOAD_DIR` env var (default `./uploads`; Docker sets it to `/data`). All uploads are streamed in 1 MB chunks to avoid loading large files into memory.

**Frontend (`frontend/src/App.jsx`)** — single-component React app. Upload progress uses axios `onUploadProgress`; falls back to `file.size` when `e.total` is `0` (common on Safari/iOS mobile). Shows an indeterminate animated bar until the first progress event arrives.

**nginx (`frontend/nginx.conf`)** — sets `proxy_request_buffering off` and `client_max_body_size 0` so large uploads stream through without buffering and without a size cap.

## Docker volume vs local storage

| Mode   | Env var `UPLOAD_DIR` | Physical location       |
|--------|----------------------|-------------------------|
| Docker | `/data`              | `./uploads/` (bind mount) |
| Local  | `./uploads` (default)| `backend/uploads/`      |

## Dependency management

Backend uses `uv` with `pyproject.toml`. A `uv.lock` is committed — use `uv sync --frozen` to install without updating it. To add a dependency: `uv add <package>` (run from `backend/`).

Frontend uses npm with `package.json`. No framework beyond React + Vite + axios.
