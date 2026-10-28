import hashlib
import json
from urllib.parse import quote

import pytest
from fastapi.testclient import TestClient

from zoomcapture_server.config import Settings
from zoomcapture_server.db import Database
from zoomcapture_server.main import create_app

SID = "20261014_103000_講義室1"
IMG = "P01_全体/000001_P01_20261014_103000_123.jpg"


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


@pytest.fixture()
def env(tmp_path):
    settings = Settings(data_dir=tmp_path, min_free_bytes=0)
    settings.ensure_dirs()
    db = Database(settings.db_path)
    db.add_device("pixel6a-1", "token-a")
    db.add_device("pixel6a-2", "token-b")
    db.close()
    client = TestClient(create_app(settings))
    return settings, client


def auth(token="token-a"):
    return {"Authorization": f"Bearer {token}"}


def url(path: str) -> str:
    return quote(path, safe="/")


def register(client, token="token-a"):
    r = client.put(url(f"/api/sessions/{SID}/session.json"), content=b'{"a":1}', headers=auth(token))
    assert r.status_code == 200, r.text


def test_requires_token(env):
    _, client = env
    assert client.get("/api/health").status_code == 401
    assert client.get("/api/health", headers=auth("wrong")).status_code == 401
    r = client.get("/api/health", headers=auth())
    assert r.status_code == 200
    assert r.json()["device"] == "pixel6a-1"


def test_revoked_device_rejected(env):
    settings, client = env
    Database(settings.db_path).revoke_device("pixel6a-1")
    assert client.get("/api/health", headers=auth()).status_code == 401


def test_upload_file_same_layout_as_device(env):
    settings, client = env
    register(client)
    body = b"\xff\xd8jpegdata"
    r = client.put(url(f"/api/sessions/{SID}/files/{IMG}"), content=body,
                   headers={**auth(), "X-Content-SHA256": sha(body)})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "stored"
    stored = settings.sessions_dir / "pixel6a-1" / SID / "P01_全体" / "000001_P01_20261014_103000_123.jpg"
    assert stored.read_bytes() == body
    assert json.loads((settings.sessions_dir / "pixel6a-1" / SID / "session.json").read_text()) == {"a": 1}

    # 同じ内容をもう一度送っても成功する(冪等)
    r = client.put(url(f"/api/sessions/{SID}/files/{IMG}"), content=body,
                   headers={**auth(), "X-Content-SHA256": sha(body)})
    assert r.json()["status"] == "exists"
    # 別の内容は拒否する
    r = client.put(url(f"/api/sessions/{SID}/files/{IMG}"), content=b"other",
                   headers={**auth(), "X-Content-SHA256": sha(b"other")})
    assert r.status_code == 409

    m = client.get(url(f"/api/sessions/{SID}/manifest"), headers=auth()).json()
    assert m["files"] == [{"path": IMG, "size": len(body), "sha256": sha(body)}]


def test_hash_mismatch_leaves_no_file(env):
    settings, client = env
    register(client)
    r = client.put(url(f"/api/sessions/{SID}/files/{IMG}"), content=b"broken",
                   headers={**auth(), "X-Content-SHA256": sha(b"original")})
    assert r.status_code == 422
    assert not (settings.sessions_dir / "pixel6a-1" / SID / "P01_全体").exists()
    assert list(settings.incoming_dir.iterdir()) == []


def test_file_requires_registered_session(env):
    _, client = env
    r = client.put(url(f"/api/sessions/{SID}/files/{IMG}"), content=b"x",
                   headers={**auth(), "X-Content-SHA256": sha(b"x")})
    assert r.status_code == 404


@pytest.mark.parametrize("bad", [
    "../escape/a.jpg",
    "P01/../../a.jpg",
    "a.jpg",
    "P01/.hidden.jpg",
    "P01/a.exe",
    "P01//a.jpg",
])
def test_rejects_bad_paths(env, bad):
    _, client = env
    register(client)
    r = client.put(f"/api/sessions/{url(SID)}/files/{bad}", content=b"x",
                   headers={**auth(), "X-Content-SHA256": sha(b"x")})
    assert r.status_code in (400, 404), (bad, r.status_code)


def test_bad_session_id(env):
    _, client = env
    r = client.put("/api/sessions/.hidden/session.json", content=b"{}", headers=auth())
    assert r.status_code == 400


def test_devices_are_isolated(env):
    _, client = env
    register(client, "token-a")
    assert client.get(url(f"/api/sessions/{SID}/manifest"), headers=auth("token-b")).status_code == 404


def test_captures_append_is_idempotent(env):
    settings, client = env
    register(client)
    path = url(f"/api/sessions/{SID}/captures")
    header = b"seq,file\n"
    row1 = b"1,P01/a.jpg\n"
    row2 = b"2,P01/b.jpg\n"

    r = client.post(path, content=header + row1, headers={**auth(), "X-Offset": "0"})
    assert r.json()["captures_size"] == len(header + row1)
    # 同じものを送り直しても重複しない
    r = client.post(path, content=header + row1, headers={**auth(), "X-Offset": "0"})
    assert r.json()["captures_size"] == len(header + row1)
    # 一部が重なっていても、新しい部分だけ追記する
    r = client.post(path, content=row1 + row2, headers={**auth(), "X-Offset": str(len(header))})
    assert r.json()["captures_size"] == len(header + row1 + row2)
    # 間が抜けるときは 409 と今のサイズを返す
    r = client.post(path, content=b"9,x\n", headers={**auth(), "X-Offset": "1000"})
    assert r.status_code == 409
    assert r.json()["detail"]["captures_size"] == len(header + row1 + row2)
    # 行の途中では区切れない
    r = client.post(path, content=b"3,P01/c", headers={**auth(), "X-Offset": str(len(header + row1 + row2))})
    assert r.status_code == 400

    csv = settings.sessions_dir / "pixel6a-1" / SID / "captures.csv"
    assert csv.read_bytes() == header + row1 + row2


def test_finalize_writes_marker(env):
    settings, client = env
    register(client)
    r = client.post(url(f"/api/sessions/{SID}/finalize"), headers=auth())
    assert r.status_code == 200
    marker = json.loads((settings.sessions_dir / "pixel6a-1" / SID / "upload_complete.json").read_text())
    assert marker["device"] == "pixel6a-1"
    assert client.get(url(f"/api/sessions/{SID}/manifest"), headers=auth()).json()["finalized"] is True
    # 2回目も成功し、最初の時刻を保つ
    again = client.post(url(f"/api/sessions/{SID}/finalize"), headers=auth()).json()
    assert again["finalized_at"] == r.json()["finalized_at"]


def test_disk_full_returns_507(tmp_path):
    settings = Settings(data_dir=tmp_path, min_free_bytes=1 << 62)
    settings.ensure_dirs()
    db = Database(settings.db_path)
    db.add_device("d", "t")
    db.close()
    client = TestClient(create_app(settings))
    r = client.put(url(f"/api/sessions/{SID}/session.json"), content=b"{}", headers=auth("t"))
    assert r.status_code == 507
