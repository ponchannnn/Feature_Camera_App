"""
管理用のコマンド。

    python -m zoomcapture_server init --host 10.1.2.3          # 証明書と設定を作る
    python -m zoomcapture_server add-device pixel6a-1           # 端末を登録して QR コードを表示する
    python -m zoomcapture_server reissue-token pixel6a-1        # トークンを作り直す(QR をなくしたとき)
    python -m zoomcapture_server revoke-device pixel6a-1        # 端末を無効にする(端末をなくしたとき)
    python -m zoomcapture_server list-devices
    python -m zoomcapture_server serve                          # サーバーを起動する
"""

from __future__ import annotations

import argparse
import json
import secrets
import sqlite3
import sys
from pathlib import Path

from . import certs, storage
from .config import load_settings, save_server_config
from .db import Database

PAIRING_VERSION = 1


def _url(host: str, port: int) -> str:
    return f"https://[{host}]:{port}" if ":" in host else f"https://{host}:{port}"


def pairing_payload(url: str, device: str, token: str, pin: str) -> str:
    return json.dumps({"v": PAIRING_VERSION, "url": url, "device": device, "token": token, "pin": pin},
                      separators=(",", ":"))


def _show_pairing(settings, device: str, token: str, url: str | None, svg: str | None) -> None:
    if not settings.cert_file.exists():
        sys.exit("証明書がありません。先に init を実行してください")
    if url is None:
        if not settings.hosts:
            sys.exit("接続先がわかりません。init で --host を指定するか、--url を指定してください")
        url = _url(settings.hosts[0], settings.port)
    payload = pairing_payload(url, device, token, certs.public_key_pin(settings.cert_file))

    import qrcode

    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    qr.add_data(payload)
    qr.make(fit=True)
    print("アプリの「保存先の設定」→「QR コードを読み取る」で、次の QR コードを読み取ってください。\n")
    try:
        qr.print_ascii(invert=True)
    except UnicodeEncodeError:
        print("(このターミナルでは QR コードを表示できません。--svg を指定してください)")
    if svg:
        import qrcode.image.svg

        qrcode.make(payload, image_factory=qrcode.image.svg.SvgPathImage).save(svg)
        print(f"\nQR コードを {svg} に保存しました。読み取ったら削除してください(トークンが含まれています)")
    print("\nQR コードを読み取れない場合は、次の文字列をアプリに貼り付けてください:")
    print(payload)
    print("\nトークンはこのときしか表示されません。")


def cmd_init(args) -> None:
    settings = load_settings(args.data_dir)
    settings.ensure_dirs()
    hosts = args.host
    if settings.cert_file.exists() and not args.force:
        sys.exit(f"証明書がすでにあります ({settings.cert_file})。作り直すときは --force を付けてください")
    certs.create_self_signed(settings.cert_file, settings.key_file, hosts, reuse_key=not args.new_key)
    save_server_config(settings, hosts, args.port)
    Database(settings.db_path).close()
    print(f"データフォルダ: {settings.data_dir}")
    print(f"証明書: {settings.cert_file}(対象: {', '.join(hosts)})")
    print(f"公開鍵のフィンガープリント: {certs.public_key_pin(settings.cert_file)}")
    print(f"接続先: {_url(hosts[0], args.port)}")


def cmd_add_device(args) -> None:
    settings = load_settings(args.data_dir)
    name = storage.validate_device_name(args.name)
    db = Database(settings.db_path)
    token = secrets.token_urlsafe(32)
    try:
        db.add_device(name, token)
    except sqlite3.IntegrityError:
        sys.exit(f"{name} はすでに登録されています。トークンを作り直すときは reissue-token を使ってください")
    _show_pairing(settings, name, token, args.url, args.svg)


def cmd_reissue(args) -> None:
    settings = load_settings(args.data_dir)
    db = Database(settings.db_path)
    token = secrets.token_urlsafe(32)
    if not db.set_token(args.name, token):
        sys.exit(f"{args.name} は登録されていません")
    print("古いトークンは使えなくなりました。")
    _show_pairing(settings, args.name, token, args.url, args.svg)


def cmd_revoke(args) -> None:
    settings = load_settings(args.data_dir)
    if Database(settings.db_path).revoke_device(args.name):
        print(f"{args.name} を無効にしました")
    else:
        sys.exit(f"{args.name} は登録されていないか、すでに無効です")


def cmd_list(args) -> None:
    settings = load_settings(args.data_dir)
    devices = Database(settings.db_path).list_devices()
    if not devices:
        print("登録されている端末はありません")
    for d in devices:
        state = f"無効({d.revoked_at})" if d.revoked_at else "有効"
        print(f"{d.name}\t{state}\t登録 {d.created_at}")


def cmd_serve(args) -> None:
    import uvicorn

    from .main import create_app

    settings = load_settings(args.data_dir)
    if not settings.cert_file.exists():
        sys.exit("証明書がありません。先に init を実行してください")
    uvicorn.run(
        create_app(settings),
        host=args.bind,
        port=args.port or settings.port,
        ssl_certfile=str(settings.cert_file),
        ssl_keyfile=str(settings.key_file),
        # 画像の受信中に接続が切れたと判断されないよう、長めにする
        timeout_keep_alive=30,
    )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="zoomcapture_server", description="講義ズーム撮影の受信サーバー")
    parser.add_argument("--data-dir", help="データフォルダ(既定: 環境変数 ZC_DATA_DIR、なければ ./data)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("init", help="証明書と設定を作る")
    p.add_argument("--host", action="append", required=True, help="サーバーの IP アドレスまたはホスト名(複数指定可)")
    p.add_argument("--port", type=int, default=8443)
    p.add_argument("--force", action="store_true", help="証明書を作り直す(鍵は使い回す)")
    p.add_argument("--new-key", action="store_true", help="鍵も作り直す(登録済みの端末はすべて設定し直しになる)")
    p.set_defaults(func=cmd_init)

    for name, func, help_text in (
        ("add-device", cmd_add_device, "端末を登録し、設定用の QR コードを表示する"),
        ("reissue-token", cmd_reissue, "端末のトークンを作り直し、QR コードを表示する"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("name", help="端末名(英数字・'-'・'_')")
        p.add_argument("--url", help="アプリが接続する URL(既定: init で指定した最初のホスト)")
        p.add_argument("--svg", help="QR コードを SVG ファイルにも保存する")
        p.set_defaults(func=func)

    p = sub.add_parser("revoke-device", help="端末を無効にする")
    p.add_argument("name")
    p.set_defaults(func=cmd_revoke)

    sub.add_parser("list-devices", help="登録されている端末の一覧").set_defaults(func=cmd_list)

    p = sub.add_parser("serve", help="サーバーを起動する")
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--port", type=int)
    p.set_defaults(func=cmd_serve)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
