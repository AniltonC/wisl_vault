import os
from datetime import datetime

import aiofiles
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

UPLOAD_DIR = os.getenv("UPLOAD_DIR", "./uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
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
        if os.path.isfile(path):
            stat = os.stat(path)
            entries.append({
                "name": name,
                "size": stat.st_size,
                "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(),
            })
    return sorted(entries, key=lambda x: x["modified"], reverse=True)


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    path = safe_path(file.filename)
    async with aiofiles.open(path, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)
    return {"name": os.path.basename(path), "size": os.path.getsize(path)}


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
