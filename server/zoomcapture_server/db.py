"""SQLite: 端末のトークン、セッション、受信済みファイルの記録。"""

from __future__ import annotations

import hashlib
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    token_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revoked_at TEXT
);
CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY,
    device_id INTEGER NOT NULL REFERENCES devices(id),
    session_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    finalized_at TEXT,
    UNIQUE (device_id, session_id)
);
CREATE TABLE IF NOT EXISTS files (
    session_pk INTEGER NOT NULL REFERENCES sessions(id),
    path TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    received_at TEXT NOT NULL,
    PRIMARY KEY (session_pk, path)
);
"""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="milliseconds")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Device:
    id: int
    name: str
    created_at: str
    revoked_at: str | None


@dataclass(frozen=True)
class SessionRow:
    pk: int
    session_id: str
    finalized_at: str | None


@dataclass(frozen=True)
class FileRow:
    path: str
    size: int
    sha256: str


class Database:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.executescript(SCHEMA)
        self._lock = threading.Lock()

    def close(self) -> None:
        self._conn.close()

    # ---- 端末 ----

    def add_device(self, name: str, token: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO devices (name, token_hash, created_at) VALUES (?, ?, ?)",
                (name, hash_token(token), now_iso()),
            )

    def set_token(self, name: str, token: str) -> bool:
        """トークンを作り直す。無効にした端末も、これで再び使えるようになる"""
        with self._lock:
            cur = self._conn.execute(
                "UPDATE devices SET token_hash = ?, revoked_at = NULL WHERE name = ?", (hash_token(token), name)
            )
            return cur.rowcount > 0

    def revoke_device(self, name: str) -> bool:
        with self._lock:
            cur = self._conn.execute(
                "UPDATE devices SET revoked_at = ? WHERE name = ? AND revoked_at IS NULL", (now_iso(), name)
            )
            return cur.rowcount > 0

    def list_devices(self) -> list[Device]:
        with self._lock:
            rows = self._conn.execute("SELECT id, name, created_at, revoked_at FROM devices ORDER BY name").fetchall()
        return [Device(*r) for r in rows]

    def device_for_token(self, token: str) -> Device | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT id, name, created_at, revoked_at FROM devices WHERE token_hash = ? AND revoked_at IS NULL",
                (hash_token(token),),
            ).fetchone()
        return Device(*row) if row else None

    # ---- セッション ----

    def upsert_session(self, device_id: int, session_id: str) -> SessionRow:
        now = now_iso()
        with self._lock:
            self._conn.execute(
                "INSERT INTO sessions (device_id, session_id, created_at, updated_at) VALUES (?, ?, ?, ?) "
                "ON CONFLICT (device_id, session_id) DO UPDATE SET updated_at = excluded.updated_at",
                (device_id, session_id, now, now),
            )
        found = self.find_session(device_id, session_id)
        assert found is not None
        return found

    def find_session(self, device_id: int, session_id: str) -> SessionRow | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT id, session_id, finalized_at FROM sessions WHERE device_id = ? AND session_id = ?",
                (device_id, session_id),
            ).fetchone()
        return SessionRow(*row) if row else None

    def finalize_session(self, session_pk: int) -> str:
        now = now_iso()
        with self._lock:
            self._conn.execute(
                "UPDATE sessions SET finalized_at = COALESCE(finalized_at, ?), updated_at = ? WHERE id = ?",
                (now, now, session_pk),
            )
            row = self._conn.execute("SELECT finalized_at FROM sessions WHERE id = ?", (session_pk,)).fetchone()
        return row[0]

    # ---- ファイル ----

    def find_file(self, session_pk: int, path: str) -> FileRow | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT path, size, sha256 FROM files WHERE session_pk = ? AND path = ?", (session_pk, path)
            ).fetchone()
        return FileRow(*row) if row else None

    def record_file(self, session_pk: int, path: str, size: int, sha256: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT OR REPLACE INTO files (session_pk, path, size, sha256, received_at) VALUES (?, ?, ?, ?, ?)",
                (session_pk, path, size, sha256, now_iso()),
            )

    def list_files(self, session_pk: int) -> list[FileRow]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT path, size, sha256 FROM files WHERE session_pk = ? ORDER BY path", (session_pk,)
            ).fetchall()
        return [FileRow(*r) for r in rows]
