"""受信 API(FastAPI)。保存するフォルダの構成は端末と同じにする。"""

from __future__ import annotations

import asyncio
import json
import shutil
from collections import defaultdict
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import storage
from .config import Settings, load_settings
from .db import Database, Device, SessionRow, now_iso


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    settings.ensure_dirs()
    db = Database(settings.db_path)
    app = FastAPI(title="Zoom Capture Server", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.db = db
    bearer = HTTPBearer(auto_error=False)
    # captures.csv の追記はセッションごとに1つずつ行う
    csv_locks: dict[tuple[int, str], asyncio.Lock] = defaultdict(asyncio.Lock)

    def current_device(creds: HTTPAuthorizationCredentials | None = Depends(bearer)) -> Device:
        if creds is None or creds.scheme.lower() != "bearer":
            raise HTTPException(401, "認証が必要です")
        device = db.device_for_token(creds.credentials)
        if device is None:
            raise HTTPException(401, "トークンが無効です")
        return device

    def session_path(device: Device, session_id: str) -> tuple[str, Path]:
        try:
            sid = storage.validate_session_id(session_id)
        except storage.InvalidPath as e:
            raise HTTPException(400, str(e)) from e
        return sid, storage.session_dir(settings.sessions_dir, device.name, sid)

    def registered(device: Device, sid: str) -> SessionRow:
        row = db.find_session(device.id, sid)
        if row is None:
            raise HTTPException(404, "セッションが登録されていません(先に session.json を送ってください)")
        return row

    def check_free_space(incoming: int) -> None:
        free = shutil.disk_usage(settings.sessions_dir).free
        if free - incoming < settings.min_free_bytes:
            raise HTTPException(507, "サーバーの空き容量が不足しています")

    def content_length(request: Request) -> int:
        try:
            return int(request.headers.get("content-length", "0"))
        except ValueError:
            return 0

    @app.get("/api/health")
    def health(device: Device = Depends(current_device)) -> dict:
        free = shutil.disk_usage(settings.sessions_dir).free
        return {"status": "ok", "device": device.name, "free_bytes": free, "accepting": free >= settings.min_free_bytes}

    @app.put("/api/sessions/{session_id}/session.json")
    async def put_session_json(session_id: str, request: Request, device: Device = Depends(current_device)) -> dict:
        sid, sdir = session_path(device, session_id)
        if content_length(request) > settings.max_session_json_bytes:
            raise HTTPException(413, "session.json が大きすぎます")
        body = await request.body()
        if len(body) > settings.max_session_json_bytes:
            raise HTTPException(413, "session.json が大きすぎます")
        try:
            json.loads(body)
        except ValueError as e:
            raise HTTPException(400, "session.json が JSON として読めません") from e
        check_free_space(len(body))
        storage.write_atomic(sdir / storage.SESSION_JSON, body)
        row = db.upsert_session(device.id, sid)
        return {"session_id": sid, "finalized": row.finalized_at is not None}

    @app.put("/api/sessions/{session_id}/files/{file_path:path}")
    async def put_file(
        session_id: str,
        file_path: str,
        request: Request,
        device: Device = Depends(current_device),
        x_content_sha256: str = Header(...),
    ) -> dict:
        sid, sdir = session_path(device, session_id)
        row = registered(device, sid)
        try:
            rel = storage.validate_file_path(file_path)
            target = storage.resolve_inside(sdir, rel)
        except storage.InvalidPath as e:
            raise HTTPException(400, str(e)) from e
        expected = x_content_sha256.strip().lower()
        if not storage.SHA256_RE.match(expected):
            raise HTTPException(400, "X-Content-SHA256 は 64 桁の16進数で指定してください")

        existing = db.find_file(row.pk, rel)
        if existing is not None and target.exists():
            if existing.sha256 == expected:
                return {"path": rel, "status": "exists", "size": existing.size}
            raise HTTPException(409, "同じ名前の別の内容のファイルがすでにあります")

        length = content_length(request)
        if length > settings.max_file_bytes:
            raise HTTPException(413, "ファイルが大きすぎます")
        check_free_space(length)
        try:
            tmp, size, digest = await storage.receive_to_temp(
                request.stream(), settings.incoming_dir, settings.max_file_bytes
            )
        except storage.SizeLimitExceeded as e:
            raise HTTPException(413, str(e)) from e
        if digest != expected:
            tmp.unlink(missing_ok=True)
            raise HTTPException(422, "受け取った内容のハッシュが一致しません(送信中に壊れた可能性があります)")
        storage.move_into_place(tmp, target)
        db.record_file(row.pk, rel, size, digest)
        return {"path": rel, "status": "stored", "size": size}

    @app.post("/api/sessions/{session_id}/captures")
    async def append_captures(
        session_id: str,
        request: Request,
        device: Device = Depends(current_device),
        x_offset: int = Header(...),
    ) -> dict:
        sid, sdir = session_path(device, session_id)
        row = registered(device, sid)
        if x_offset < 0:
            raise HTTPException(400, "X-Offset が不正です")
        if content_length(request) > settings.max_captures_chunk_bytes:
            raise HTTPException(413, "一度に送る行が多すぎます")
        body = await request.body()
        if len(body) > settings.max_captures_chunk_bytes:
            raise HTTPException(413, "一度に送る行が多すぎます")
        if body and not body.endswith(b"\n"):
            raise HTTPException(400, "行の途中で区切らないでください(改行で終わる必要があります)")
        async with csv_locks[(row.pk, sid)]:
            try:
                size = storage.append_captures(sdir / storage.CAPTURES_CSV, x_offset, body)
            except storage.OffsetConflict as e:
                raise HTTPException(409, {"message": str(e), "captures_size": e.current_size}) from e
        return {"captures_size": size}

    @app.get("/api/sessions/{session_id}/manifest")
    def manifest(session_id: str, device: Device = Depends(current_device)) -> dict:
        sid, sdir = session_path(device, session_id)
        row = registered(device, sid)
        csv = sdir / storage.CAPTURES_CSV
        return {
            "session_id": sid,
            "captures_size": csv.stat().st_size if csv.exists() else 0,
            "finalized": row.finalized_at is not None,
            "files": [{"path": f.path, "size": f.size, "sha256": f.sha256} for f in db.list_files(row.pk)],
        }

    @app.post("/api/sessions/{session_id}/finalize")
    def finalize(session_id: str, device: Device = Depends(current_device)) -> dict:
        sid, sdir = session_path(device, session_id)
        row = registered(device, sid)
        finalized_at = db.finalize_session(row.pk)
        files = db.list_files(row.pk)
        csv = sdir / storage.CAPTURES_CSV
        marker = {
            "finalized_at": finalized_at,
            "device": device.name,
            "file_count": len(files),
            "total_bytes": sum(f.size for f in files),
            "captures_size": csv.stat().st_size if csv.exists() else 0,
            "written_at": now_iso(),
        }
        storage.write_atomic(sdir / storage.COMPLETE_MARKER, json.dumps(marker, ensure_ascii=False, indent=2).encode())
        return {"session_id": sid, "finalized_at": finalized_at, "file_count": len(files)}

    return app
