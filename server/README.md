# 受信サーバー(zoomcapture_server)

講義ズーム撮影アプリが撮った画像とメタデータを受け取る、研究室サーバー用のプログラムです。学内の IP アドレスで待ち受けます。

- 通信は HTTPS(自己署名の証明書)です。アプリには証明書の公開鍵のフィンガープリントを登録するので、端末に CA 証明書を入れる必要はありません。
- 端末ごとにトークンを発行します。学内の誰でも接続できるネットワークなので、トークンのない接続は拒否します。
- 保存するフォルダの構成は端末と同じです。Posture_Judgment では、USB でコピーしたデータと同じように読めます。

```
<データフォルダ>/
├── config.json            # init で指定したホストとポート
├── server.db              # 端末のトークン(ハッシュ)、セッション、受信済みファイルの記録
├── certs/server.crt, server.key
├── incoming/              # 受信中の一時ファイル
└── sessions/<端末名>/<セッション>/
    ├── session.json
    ├── captures.csv           # 端末と同じ内容(サーバーにある画像の行だけ)
    ├── upload_complete.json   # すべて受け取ると作られる(解析を始めてよい目印)
    └── P01_全体/000001_P01_....jpg ...
```

## セットアップ

Python 3.11 以上が必要です。

```bash
cd server
python -m venv .venv
. .venv/bin/activate            # Windows の場合は .venv\Scripts\activate
pip install -e .

export ZC_DATA_DIR=/srv/zoomcapture     # 容量の大きいディスク上のフォルダ(既定は ./data)

# 証明書を作る。--host にはアプリから接続するときの IP アドレスを指定する(複数指定できる)
python -m zoomcapture_server init --host 10.1.2.3

# 端末を登録する。表示された QR コードを、アプリの「保存先の設定」→「QR コードを読み取る」で読み取る
python -m zoomcapture_server add-device pixel6a-1

# 起動する(既定のポートは 8443)
python -m zoomcapture_server serve
```

常に起動しておく場合は、systemd などで `python -m zoomcapture_server serve` を実行してください。

Docker を使う場合:

```bash
docker compose run --rm zoomcapture python -m zoomcapture_server init --host 10.1.2.3
docker compose run --rm zoomcapture python -m zoomcapture_server add-device pixel6a-1
docker compose up -d
```

## 運用

| やりたいこと | コマンド |
|---|---|
| 端末の一覧 | `list-devices` |
| QR コードをなくした | `reissue-token <端末名>`(古いトークンは使えなくなる) |
| 端末をなくした | `revoke-device <端末名>` |
| サーバーの IP アドレスが変わった | `init --host <新しい IP> --force`(鍵はそのまま使うので、フィンガープリントは変わらない。アプリ側は接続先の URL を変えるため `reissue-token` で設定し直す) |

- サーバーの空き容量が `ZC_MIN_FREE_GB`(既定 20GB)を下回ると、受け取りを止めて 507 を返します。そのあいだ端末は送らずにデータを保持し、空きができたら続きから送ります。
- 1回の講義(12MP の JPEG、1秒に1枚)でおよそ 20GB、1科目 15 回で 300GB 程度になります。
- `sessions/` には学生の顔が写った画像があります。読めるのは研究室のメンバーだけにし、保存期間と削除のルールは倫理審査の内容に従ってください。
- 最初に、教室の Wi-Fi に接続した端末から、アプリの「接続テスト」でサーバーに届くかを確認してください。届かない場合は、学内のファイアウォールでポート(8443)を開ける申請が必要になることがあります。

## API

すべて `Authorization: Bearer <トークン>` が必要です。

| メソッドとパス | 内容 |
|---|---|
| `GET /api/health` | 接続の確認(端末名と空き容量を返す) |
| `PUT /api/sessions/{id}/session.json` | セッションの登録・更新 |
| `PUT /api/sessions/{id}/files/{相対パス}` | 画像の受信。`X-Content-SHA256` が必須。同じ内容なら何度送っても成功し、違う内容なら 409 |
| `POST /api/sessions/{id}/captures` | captures.csv への追記。`X-Offset` には端末側のファイルでの開始位置を指定する。重なった部分は無視し、間が抜ける場合は 409 とサーバー側のサイズを返す |
| `GET /api/sessions/{id}/manifest` | 受け取り済みのファイルとハッシュ、captures.csv のサイズ |
| `POST /api/sessions/{id}/finalize` | セッションの確定(`upload_complete.json` を作る) |

## テスト

```bash
pip install -e ".[test]"
pytest
```

アプリとの結合テストは、このサーバーを起動したうえでアプリ側から実行します。手順は `app/src/test/.../upload/ServerEndToEndTest.kt` を参照してください。
