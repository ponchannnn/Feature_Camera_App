"""保存先のパスの検証と、壊れたファイルを残さない書き込み。"""

from __future__ import annotations

import hashlib
import os
import re
import unicodedata
import uuid
from pathlib import Path
from typing import AsyncIterator

DEVICE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_EXTENSIONS = {".jpg", ".jpeg", ".dng"}
_FORBIDDEN_CHARS = set('\\:*?"<>|\x00')
MAX_SEGMENT_LEN = 200

SESSION_JSON = "session.json"
CAPTURES_CSV = "captures.csv"
COMPLETE_MARKER = "upload_complete.json"


class InvalidPath(ValueError):
    pass


class SizeLimitExceeded(ValueError):
    pass


class HashMismatch(ValueError):
    pass


def _check_segment(seg: str) -> str:
    seg = unicodedata.normalize("NFC", seg)
    if not seg or seg in (".", "..") or seg.startswith("."):
        raise InvalidPath(f"不正なパスです: {seg!r}")
    if len(seg) > MAX_SEGMENT_LEN or any(c in _FORBIDDEN_CHARS or ord(c) < 0x20 for c in seg):
        raise InvalidPath(f"不正なパスです: {seg!r}")
    return seg


def validate_device_name(name: str) -> str:
    if not DEVICE_NAME_RE.match(name):
        raise InvalidPath("端末名は英数字・'-'・'_' の 64 文字以内にしてください")
    return name


def validate_session_id(session_id: str) -> str:
    if "/" in session_id:
        raise InvalidPath("セッション ID に '/' は使えません")
    return _check_segment(session_id)


def validate_file_path(rel: str) -> str:
    """セッションフォルダからの相対パス(例: P01_全体/000001_P01_20261014_103000_123.jpg)"""
    parts = [_check_segment(p) for p in rel.split("/")]
    if len(parts) < 2:
        # session.json / captures.csv などセッション直下のファイルは専用の API で受け取る
        raise InvalidPath("画像はプリセットのフォルダの中に置いてください")
    if Path(parts[-1]).suffix.lower() not in ALLOWED_EXTENSIONS:
        raise InvalidPath(f"受け付けない拡張子です: {parts[-1]}")
    return "/".join(parts)


def session_dir(sessions_root: Path, device: str, session_id: str) -> Path:
    return sessions_root / device / session_id


def resolve_inside(base: Path, rel: str) -> Path:
    target = (base / rel).resolve()
    if not target.is_relative_to(base.resolve()):
        raise InvalidPath("セッションフォルダの外は指定できません")
    return target


async def receive_to_temp(
    chunks: AsyncIterator[bytes], incoming_dir: Path, max_bytes: int
) -> tuple[Path, int, str]:
    """受信した内容を一時ファイルに書き、(一時ファイル, サイズ, SHA-256) を返す"""
    incoming_dir.mkdir(parents=True, exist_ok=True)
    tmp = incoming_dir / f"{uuid.uuid4().hex}.part"
    digest = hashlib.sha256()
    size = 0
    try:
        with open(tmp, "wb") as f:
            async for chunk in chunks:
                size += len(chunk)
                if size > max_bytes:
                    raise SizeLimitExceeded(f"ファイルが大きすぎます(上限 {max_bytes} バイト)")
                digest.update(chunk)
                f.write(chunk)
            f.flush()
            os.fsync(f.fileno())
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    return tmp, size, digest.hexdigest()


def move_into_place(tmp: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    os.replace(tmp, target)


def write_atomic(target: Path, data: bytes) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, target)


def append_captures(csv_path: Path, offset: int, data: bytes) -> int:
    """
    captures.csv を端末のファイルとバイト単位で同じ内容に保つ。
    offset は端末のファイルの中での data の開始位置。すでに受け取った部分は書き直さない(冪等)。
    戻り値は追記後のサイズ。offset が今のサイズより先なら、間が抜けるので OffsetConflict を投げる。
    """
    current = csv_path.stat().st_size if csv_path.exists() else 0
    if offset > current:
        raise OffsetConflict(current)
    end = offset + len(data)
    if end <= current:
        return current
    tail = data[current - offset:]
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with open(csv_path, "ab") as f:
        f.write(tail)
        f.flush()
        os.fsync(f.fileno())
    return end


class OffsetConflict(Exception):
    def __init__(self, current_size: int):
        super().__init__(f"captures.csv の位置が一致しません(サーバー側は {current_size} バイト)")
        self.current_size = current_size
