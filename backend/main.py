import json
import os
import re
import shutil
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import aiofiles
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

UPLOAD_DIR = os.getenv("UPLOAD_DIR", "./uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)

LOG_FILE = os.path.join(os.path.realpath(UPLOAD_DIR), ".debug.jsonl")

CHUNKS_ROOT = os.path.join(os.path.realpath(UPLOAD_DIR), ".chunks")

def cleanup_uploads():
    removed_files, removed_chunks = [], 0

    # Remove todos os arquivos enviados (ignora arquivos ocultos como .debug.jsonl)
    for entry in os.scandir(UPLOAD_DIR):
        if entry.is_file() and not entry.name.startswith('.'):
            os.remove(entry.path)
            removed_files.append(entry.name)

    # Remove todos os chunks pendentes
    if os.path.isdir(CHUNKS_ROOT):
        for entry in os.scandir(CHUNKS_ROOT):
            if entry.is_dir():
                shutil.rmtree(entry.path, ignore_errors=True)
                removed_chunks += 1

    if removed_files:
        print(f"[cleanup] {len(removed_files)} arquivo(s) removido(s): {removed_files}")
    if removed_chunks:
        print(f"[cleanup] {removed_chunks} diretório(s) de chunks removido(s)")
    if not removed_files and not removed_chunks:
        print("[cleanup] Diretório de uploads já estava vazio.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    cleanup_uploads()
    yield


app = FastAPI(lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

_UUID_RE = re.compile(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)


def safe_path(filename: str) -> str:
    name = os.path.basename(filename)
    path = os.path.realpath(os.path.join(UPLOAD_DIR, name))
    if not path.startswith(os.path.realpath(UPLOAD_DIR)):
        raise HTTPException(status_code=400, detail="Invalid filename")
    return path


@app.get("/files")
async def list_files():
    entries = []
    for name in os.listdir(UPLOAD_DIR):
        path = os.path.join(UPLOAD_DIR, name)
        if os.path.isfile(path) and not name.startswith('.'):
            stat = os.stat(path)
            entries.append({
                "name": name,
                "size": stat.st_size,
                "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(),
            })
    return sorted(entries, key=lambda x: x["modified"], reverse=True)


@app.post("/upload")
async def upload_file(
    file: UploadFile = File(...),
    file_id: str = Form(...),
    chunk_index: int = Form(...),
    total_chunks: int = Form(...),
):
    if not _UUID_RE.match(file_id):
        raise HTTPException(status_code=400, detail="Invalid file_id")
    if total_chunks <= 0 or not (0 <= chunk_index < total_chunks):
        raise HTTPException(status_code=400, detail="Invalid chunk parameters")

    chunk_dir = os.path.join(CHUNKS_ROOT, file_id)
    os.makedirs(chunk_dir, exist_ok=True)

    chunk_path = os.path.join(chunk_dir, f"{chunk_index:06d}")
    async with aiofiles.open(chunk_path, "wb") as f:
        while data := await file.read(1024 * 1024):
            await f.write(data)

    if chunk_index < total_chunks - 1:
        return {"chunk_index": chunk_index, "done": False}

    # Último chunk recebido — monta o arquivo final
    final_path = safe_path(file.filename)
    try:
        async with aiofiles.open(final_path, "wb") as out:
            for i in range(total_chunks):
                cp = os.path.join(chunk_dir, f"{i:06d}")
                async with aiofiles.open(cp, "rb") as c:
                    while data := await c.read(1024 * 1024):
                        await out.write(data)
    finally:
        shutil.rmtree(chunk_dir, ignore_errors=True)

    return {"name": os.path.basename(final_path), "size": os.path.getsize(final_path), "done": True}


@app.post("/debug/log", status_code=204)
async def write_log(request: Request):
    body = await request.json()
    body["server_ts"] = datetime.now(timezone.utc).isoformat()
    async with aiofiles.open(LOG_FILE, "a", encoding="utf-8") as f:
        await f.write(json.dumps(body, ensure_ascii=False) + "\n")


@app.get("/debug/logs")
async def read_logs():
    if not os.path.isfile(LOG_FILE):
        return []
    async with aiofiles.open(LOG_FILE, "r", encoding="utf-8") as f:
        content = await f.read()
    return [json.loads(line) for line in content.splitlines() if line.strip()]


@app.delete("/debug/logs", status_code=204)
async def clear_logs():
    if os.path.isfile(LOG_FILE):
        os.remove(LOG_FILE)


@app.get("/files/{filename}")
async def download_file(filename: str):
    path = safe_path(filename)
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(path, filename=os.path.basename(path))


@app.delete("/files/{filename}")
async def delete_file(filename: str):
    path = safe_path(filename)
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="File not found")
    os.remove(path)
    return {"message": "deleted"}
