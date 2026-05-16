# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

Local-network file storage for testing smartphone uploads. No authentication. The computer runs the stack and phones on the same Wi-Fi connect to it by IP.

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

Frontend served by nginx on port 80. Phones connect to `http://<computer-LAN-IP>`.

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
