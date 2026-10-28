"""サーバーの設定。環境変数とデータフォルダの config.json から読む。"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

MB = 1024 * 1024
GB = 1024 * MB


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    # 1ファイルの上限(最大解像度の JPEG や DNG を想定)
    max_file_bytes: int = 300 * MB
    max_session_json_bytes: int = 10 * MB
    # captures.csv に1回で追記できる量
    max_captures_chunk_bytes: int = 16 * MB
    # サーバーのディスクの空きがこれを下回ったら受け取りを止める(507)
    min_free_bytes: int = 20 * GB
    # init で作った設定(証明書のホスト名・ポート)
    hosts: list[str] = field(default_factory=list)
    port: int = 8443

    @property
    def sessions_dir(self) -> Path:
        return self.data_dir / "sessions"

    @property
    def incoming_dir(self) -> Path:
        """受信中の一時ファイル。sessions と同じファイルシステムに置き、os.replace で移せるようにする"""
        return self.data_dir / "incoming"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "server.db"

    @property
    def cert_file(self) -> Path:
        return self.data_dir / "certs" / "server.crt"

    @property
    def key_file(self) -> Path:
        return self.data_dir / "certs" / "server.key"

    @property
    def config_file(self) -> Path:
        return self.data_dir / "config.json"

    def ensure_dirs(self) -> None:
        for d in (self.sessions_dir, self.incoming_dir, self.cert_file.parent):
            d.mkdir(parents=True, exist_ok=True)


def load_settings(data_dir: str | os.PathLike | None = None) -> Settings:
    root = Path(data_dir or os.environ.get("ZC_DATA_DIR", "data")).resolve()
    stored: dict = {}
    config_file = root / "config.json"
    if config_file.exists():
        stored = json.loads(config_file.read_text(encoding="utf-8"))
    kwargs: dict = {"data_dir": root, "hosts": stored.get("hosts", []), "port": stored.get("port", 8443)}
    if "ZC_MIN_FREE_GB" in os.environ:
        kwargs["min_free_bytes"] = int(float(os.environ["ZC_MIN_FREE_GB"]) * GB)
    if "ZC_MAX_FILE_MB" in os.environ:
        kwargs["max_file_bytes"] = int(float(os.environ["ZC_MAX_FILE_MB"]) * MB)
    return Settings(**kwargs)


def save_server_config(settings: Settings, hosts: list[str], port: int) -> None:
    settings.config_file.parent.mkdir(parents=True, exist_ok=True)
    settings.config_file.write_text(json.dumps({"hosts": hosts, "port": port}, indent=2), encoding="utf-8")
