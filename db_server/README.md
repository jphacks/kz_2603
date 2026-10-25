# 服装提案AI DBサーバー

服装提案AIアプリ（LayeRoom）のデータを受け取り・保存し・返すサーバー。
要件と決定事項の経緯は [requirements.md](requirements.md) を参照。

- DBサーバーは **AIを呼ばない**。AI（外部AI APIの呼び出し）は Android アプリ側で行う。
- 服の選択・おすすめの判断も AI 側。サーバーは絞り込まずにデータを返し、AIの結果を保存するだけ。

## 全体構成

```
Arduino UNO R4 WiFi ──POST 室内温度(5分毎)──────────▶ ┐
                                                     │
Open-Meteo API ◀────── 屋外の天気を定期取得(30分毎) ── DBサーバー
                                                     │  FastAPI + PostgreSQL 16 + 画像保存
Androidアプリ（AI含む）                                │  (Docker Compose)
  ・ユーザー登録 / 気象データ取得                       │
  ・① コーデ写真 + 判定 + 分析を保存 ─────────────────▶ │
  ・② 上下に分けた服 + meta を保存 ──────────────────▶ │
  ・③ 服の一覧を取得 → おすすめを保存 ───────────────▶ │
  ・今日のひろば（部屋ごとの体感の投稿）──────────────▶ ┘
```

## ディレクトリ

```
docker-compose.yml          db (PostgreSQL 16) + api (FastAPI)
.env.example                設定のひな形（.env は Git に上げない）
requirements.md             要件定義
api/app/
  main.py                   起動処理（テーブル作成・列追加・センサーと部屋の登録・天気の定期取得）
  models.py                 テーブル定義
  schemas.py                APIの入出力
  routers/
    system.py               /health, /users
    conditions.py           /readings, /conditions/latest
    outfits.py              コーデ・服
    recommendations.py      おすすめ
    plaza.py                今日のひろば（/rooms）
  weather.py                Open-Meteo からの天気取得
  discomfort.py             不快指数の計算
```

Arduino のスケッチはリポジトリ直下の [`../arduino`](../arduino) にある。

## セットアップ（Ubuntu 24.04）

### 1. 設定ファイルを作る

```bash
cp .env.example .env
nano .env
```

| 項目 | 内容 |
|---|---|
| `POSTGRES_PASSWORD` | DBのパスワード。`openssl rand -hex 16` で作った英数字にする（記号は使わない） |
| `DEVICE_API_KEY` | Arduino が送るキー。`openssl rand -hex 16` で作り、Arduino の `arduino_secrets.h` にも同じ値を書く |
| `DEVICE_ROOM_NAME` | センサーを置いた部屋の名前（初期値 `201教室`）。今日のひろばの同じ名前の部屋が、室温にセンサー値を使う。コーデ一覧の `classroom` 絞り込みにも使う。起動時にDBのセンサーの部屋名もこの値にそろえる |
| `WEATHER_LATITUDE` / `WEATHER_LONGITUDE` | 天気を取る地点（キャンパスの緯度・経度） |
| `API_PORT` | サーバーで待ち受けるポート（初期値 8000） |

**`POSTGRES_PASSWORD` は、一度DBを作ったあとに変えないこと。** DBは最初のパスワードを覚えているので、
`.env` だけ変えると `password authentication failed` で API が起動しなくなる。

### 2. 起動する

```bash
docker compose up -d --build
docker compose ps                 # api が Up になっていればOK
curl localhost:8000/health        # {"status":"ok"}
```

- API仕様（他メンバーへの共有用）: `http://<公開アドレス>/docs`
- ログ: `docker compose logs -f api`
- データはDockerボリューム（`db_data`, `image_data`）に残る。
  **`docker compose down -v` は全データ削除になるので使わない。**
- `restart: unless-stopped` なので、サーバーを再起動しても自動で立ち上がる。

## 本番への反映（コードを更新したとき）

1. （任意）バックアップを取る（サーバーで）

   ```bash
   docker compose exec -T db pg_dump -U outfit outfit > ~/backup_$(date +%Y%m%d_%H%M).sql
   ```

2. コードを送る（手元のマシンで、`db_server/` の中から）。**`.env` は必ず除外する。**

   ```bash
   rsync -av --exclude .env --exclude __pycache__ \
     -e "ssh -p <SSHポート>" ./ <ユーザー>@<サーバー>:~/db_server/
   ```

3. ビルドし直す（サーバーで）。**止めずに実行してよい。** ビルド中は古いAPIが動き続け、
   入れ替えの数秒だけ止まる。DBコンテナは止まらない。

   ```bash
   cd ~/db_server && docker compose up -d --build
   docker compose ps
   ```

テーブルや列の追加は起動時に自動で行われ、既存データは残る（下の「DBの変更」を参照）。

## 認証とユーザー登録

| 呼ぶ側 | ヘッダ | 取得方法 |
|---|---|---|
| Android | `X-User-Token` | `POST /users` で発行 |
| Arduino | `X-Device-Key` | `.env` の `DEVICE_API_KEY` |

認証なしで呼べるのは `/health`・`POST /users`・`/conditions/latest`・`/rooms` の取得系（GET）だけ。
画像の取得（`/outfits/{id}/image`, `/garments/{id}/image`）もトークンが必要。

ログイン画面は作らない。Android アプリは次の流れで使う。

1. 初回起動時に `POST /users`（`{"name":"ゲスト"}` など）を1回だけ呼ぶ
2. 返ってきた `user_id` と `token` を端末に保存する（SharedPreferences / DataStore）
3. 以降の通信はすべてヘッダ `X-User-Token: <token>` を付ける（画像読み込みライブラリも同じ）

アプリのデータを消すと別ユーザーになる（ハッカソンでは許容）。

## API一覧

詳しい入出力は `/docs` を参照。

| メソッド | パス | 用途 |
|---|---|---|
| GET | `/health` | 動作確認 |
| POST | `/users` | ユーザー登録。`id`（= `user_id`）と `token` を返す |
| POST | `/readings` | Arduino が室内温度を送る（`X-Device-Key`） |
| GET | `/conditions/latest` | 室内・屋外の最新値、その差、今日の予報、各値の鮮度。`room_id` か `classroom` で部屋を指定すると、室内はその部屋の値 |
| POST | `/outfits` | ① コーデ写真 + 判定（`verdict`）+ `comment` + `analysis` + 判定した部屋（`room_id` か `classroom`）を保存 |
| GET | `/outfits/{id}` | コーデの詳細（判定・分析・撮影時の気象・分割した服） |
| GET | `/outfits/{id}/image` | コーデ写真 |
| PATCH | `/outfits/{id}/feedback` | 感想（暑かった/寒かった/ちょうどいい）を記録 |
| GET | `/users/{id}/outfits` | コーデ履歴。`limit` / `offset` / `temp_min` / `temp_max` / `classroom`（判定時の部屋・室温）で絞り込み可 |
| POST | `/outfits/{id}/garments` | ② 上下に分けた服の画像 + `part` + `meta` を保存 |
| GET | `/users/{id}/garments` | ③ 服を全件（`meta`・`taken_at`・判定・撮影時の気象付き） |
| PATCH | `/garments/{id}/meta` | 服の `meta` を部分更新（例: `{"hidden": true}`） |
| GET | `/garments/{id}/image` | 服の画像 |
| POST | `/users/{id}/recommendations` | ③ AIが選んだ上下の組み合わせを保存。下の服（`bottom_garment_id`）はワンピースなどのとき省略か null 可 |
| GET | `/users/{id}/recommendations/latest` | 最新のおすすめ |
| GET | `/rooms` | 今日のひろば: 場所の一覧（室温・湿度と今日の体感ごとの件数）。センサーの部屋は実測、ほかはダミー |
| GET | `/rooms/{id}` | 1つの場所の情報と今日の件数 |
| GET | `/rooms/{id}/posts` | その場所の投稿（新しい順）。初期値は今日の分、`all=true` で過去分も |
| POST | `/rooms/{id}/posts` | 体感の投稿（要トークン） |

## データの取り決め

- **時刻**: すべて UTC（末尾 `Z`）。ただし `/conditions/latest` の `forecast_hourly[].time` は日本時間（タイムゾーン表記なし）。
- **「今日」**: 日本時間の0時から（今日のひろばの件数・投稿一覧）。
- **multipart の JSON 項目**: `analysis`（コーデ）と `meta`（服）は **JSON を文字列にして** 送る。オブジェクト（`{...}`）でなければ 422。中身は AI 側が決める。
- **`part`**: `top` / `bottom` / `outer`（上着は top として扱う運用。`outer` は将来用）。
- **感想 `feedback`**: `暑かった` / `寒かった` / `ちょうどいい` でも英語でも受け付け、`hot` / `cold` / `just_right` で保存・返却する。
- **体感 `feeling`**: `快適` / `少し寒い` / `かなり寒い` / `少し暑い` でも英語でも受け付ける。
  返却は `feeling`（`comfortable` / `slightly_cold` / `very_cold` / `slightly_hot`）と表示用の `feeling_label`（日本語）。
  ひとことは任意・500文字まで。
- **今日のひろばの場所**: 図書館 / 201教室 / 学生食堂 / 実験室3 を初回起動時に登録する。
  フロントは `GET /rooms` の `name` と `id` をそのまま使う。
- **部屋の室温・湿度**: `DEVICE_ROOM_NAME` と同じ名前の部屋（201教室）だけ、室温を **センサーの最新値** にする
  （`temperature_source: "sensor"`, `is_dummy: false`）。ほかの部屋は **ダミー値**（`"dummy"`, `is_dummy: true`）。
  湿度は湿度センサーで実測したときだけ `"sensor"` になり、それまではダミー値。
  センサー値が15分以上届いていなければ `stale: true`（値は最後に届いたものを返す）。
- **部屋ごとの判定（適温か）**: 判定の前に `GET /conditions/latest?room_id=<部屋のid>` でその部屋の室内の値を取り、
  保存するときは `POST /outfits` に同じ `room_id` を付ける。コーデには **判定した部屋とそのときの室温・湿度・不快指数** が記録され、
  あとでセンサー値やダミー値が変わっても履歴の値は変わらない。部屋を指定しなければ 201教室（センサーの部屋）として扱う。
  部屋が記録されていない古いコーデは、紐付いたセンサー値を返す（`room_id: null`）。
- **室内の値の見分け方**: `indoor` の `temperature_source` / `humidity_source` で区別する。
  `sensor` は実測、`dummy` は部屋のダミー値、`api`（湿度のみ）は屋外の湿度で代用した値。
  部屋を指定しない `/conditions/latest` は従来どおりセンサーの値で、湿度は屋外の湿度で代用（`api`）。
  AI は `api` の間は室内湿度を使わない。センサーを付けたら Arduino から `humidity` も送れば `"sensor"` になる。
- **画像**: jpeg / png / webp。Docker ボリュームに保存し、DBにはパスだけ持つ。同じ服の重複は許容。
- **データの削除**: ハッカソン期間中はすべて保存（削除APIなし）。
- **Android から http で通信する**: Android 9 以降は `android:usesCleartextTraffic="true"`（または network security config）が必要。
- **CORS**: 設定していない（クライアントは Android と Arduino のみ）。

## 使い方の例（curl）

```bash
# ユーザー登録 → token を控える
curl -X POST localhost:8000/users -H 'Content-Type: application/json' -d '{"name":"taro"}'

# センサー値の送信（Arduino がやること）
curl -X POST localhost:8000/readings -H 'X-Device-Key: change-me-device-key' \
  -H 'Content-Type: application/json' -d '{"temperature":24.5}'

# 今の気象データ（部屋を指定すると室内はその部屋の値。3 = 学生食堂）
curl localhost:8000/conditions/latest
curl "localhost:8000/conditions/latest?room_id=3"

# ① コーデ写真 + 判定 + 分析（判定した部屋も付ける）
curl -X POST localhost:8000/outfits -H "X-User-Token: $TOKEN" \
  -F image=@outfit.jpg -F verdict=good -F comment='ちょうどいい' \
  -F 'analysis={"level":"ok","score":80}' -F room_id=3

# ② 上下に分けた服（meta は任意の JSON 文字列）
curl -X POST localhost:8000/outfits/1/garments -H "X-User-Token: $TOKEN" \
  -F image=@top.jpg -F part=top -F 'meta={"type":"jacket","color":"ブラウン","clo":0.95}'

# 服を一覧から隠す（meta の部分更新）
curl -X PATCH localhost:8000/garments/1/meta -H "X-User-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"hidden":true}'

# 感想
curl -X PATCH localhost:8000/outfits/1/feedback -H "X-User-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"feedback":"暑かった"}'

# コーデ履歴を室温で絞る
curl "localhost:8000/users/1/outfits?temp_min=20&temp_max=25&limit=20" -H "X-User-Token: $TOKEN"

# ③ 服を全件取得 → おすすめを保存
curl localhost:8000/users/1/garments -H "X-User-Token: $TOKEN"
curl -X POST localhost:8000/users/1/recommendations -H "X-User-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"top_garment_id":1,"bottom_garment_id":2}'
curl -X POST localhost:8000/users/1/recommendations -H "X-User-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"top_garment_id":3}'      # 下の服なし（ワンピースなど）

# 今日のひろば: 場所の一覧 → 投稿 → 投稿一覧
curl localhost:8000/rooms
curl -X POST localhost:8000/rooms/2/posts -H "X-User-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"feeling":"快適","comment":"長袖シャツ一枚で大丈夫"}'
curl localhost:8000/rooms/2/posts
```

## Arduino

室内温度センサー（Arduino UNO R4 WiFi + LM61CIZ）の配線・設定・書き込み方は [`../arduino/README.md`](../arduino/README.md) を参照。
`SECRET_DEVICE_KEY` には `.env` の `DEVICE_API_KEY` と同じ値を書く。

## トラブルシューティング

| 症状 | 対処 |
|---|---|
| `api` が `Restarting` を繰り返す | `docker compose logs api --tail 30` を見る |
| ログに `password authentication failed` | `.env` の `POSTGRES_PASSWORD` を最初の値に戻す。分からなければ（データが不要なら）`docker compose down -v` で作り直す |
| ビルド中に PyPI へ接続できない | 一時的なことが多いので、もう一度 `docker compose up -d --build` |
| `/conditions/latest` の `outdoor` が `null` | `docker compose logs api \| grep -i weather` で天気の取得エラーを見る |
| 201教室が `temperature_source: "dummy"` のまま | `.env` の `DEVICE_ROOM_NAME` が `201教室` か確認して `docker compose up -d`。それでも dummy ならセンサー値がまだ1件もない |
| 室温が更新されず `stale: true` | Arduino からの送信が15分以上止まっている。Arduino が動いていない間は最後の値を返し続ける |
| 学内のブラウザで squid のエラー画面 | 大学のプロキシ経由になっている。`curl --noproxy '*'` かモバイル回線で確認する |
| 外から届くか確かめたい | サーバーで `sudo tcpdump -ni any 'tcp port 8000'` を実行したまま外からアクセスし、行が流れるか見る |

## DBの変更

マイグレーションツールは使わず、起動時に次を行う。

- `Base.metadata.create_all` で **新しいテーブル** を作る（既存テーブルはそのまま）
- 既存テーブルに **列を足す** ときは、`main.py` の `_ADDED_COLUMNS` に
  `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` を追記する（データを残したまま反映される）
- 列の型変更や削除は自動では行われない。必要なら手で `ALTER TABLE` を実行する
