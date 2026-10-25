# 服装提案AIエージェント DBサーバー 要件定義

ハッカソン用。担当範囲は「AI処理のためのデータを受け取り・保存し・返すサーバー」。

## 1. 全体構成

```
Arduino UNO R4 WiFi ──POST 室内温度──────────────▶ ┐
                                                  │
Open-Meteo API ◀────── 定期取得(30分毎) ────────── DBサーバー
                                                  │  (FastAPI + PostgreSQL + 画像保存)
Androidアプリ(AI含む)                              │
  ① GET  今の気象データ ──────────────────────▶ │
  ① POST コーデ写真 + 判定(good/bad) ──────────▶ │
  ② POST 上下に分けた服の画像 ─────────────────▶ │
  ③ GET  服の一覧(全件・判定と気象条件付き) ───▶ │
  ③ POST 今日のおすすめ(上+下) ─────────────────▶ ┘
```

- DBサーバーは **AIを呼ばない**。受け取り・保存・返却のみ。AIはAndroid側。
- 実行環境: 別マシンの Ubuntu 24.04 上で Docker Compose。
- ネットワーク・公開設定は先輩担当。

### AI側の処理の流れ（Android）

| 段階 | タイミング | 内容 | DBサーバーとのやりとり |
|---|---|---|---|
| ① 判定 | 撮影直後 | 今の不快指数に対して、そのコーデが良いか悪いかを判定 | 気象データ取得 → 写真と判定結果を保存 |
| ② 分割 | ①の裏で | 写真を上の服・下の服に切り分ける | 分割した服の画像を、元のコーデに紐付けて保存 |
| ③ 提案 | 時間などのトリガー | 蓄積した服から、その日に合う上下の組み合わせを選ぶ | 服を全件取得 → 提案結果を保存 |

## 2. 扱うデータ

| データ | 取得元 | 備考 |
|---|---|---|
| 室内温度 | 温度センサー (Arduino UNO R4 WiFi) | 講義室に設置 |
| 室内湿度 | **Open-Meteo の屋外湿度で代用** | 湿度センサー未入手のため。後で実測に差し替え可能な設計にする |
| 屋外の気温・湿度・天気・降水 | Open-Meteo API (APIキー不要) | 地点はキャンパス1か所固定。現在値 + 今日の予報 |
| 不快指数 (室内・屋外) | サーバーで計算 | **室内がメイン** |
| コーデ写真 + 判定 | Androidアプリ (①) | ユーザーごとに蓄積 |
| 服の画像 (上/下) | Androidアプリ (②) | コーデ写真から切り出したもの |
| 今日のおすすめ | Androidアプリ (③) | 履歴として保存 |

### 不快指数の計算

```
DI = 0.81 × T + 0.01 × H × (0.99 × T − 14.3) + 46.3
```

- 室内DI: センサーの温度 × 湿度（当面はAPIの湿度で代用 → `humidity_source = 'api'`）
- 屋外DI: APIの気温 × APIの湿度
- 室内と屋外の **差** も返す（「中と外の温度差で服が選びにくい」という課題の中心）

### センサーと時刻の扱い

- センサーは **1台**（講義室1か所）。コーデへの紐付けは「撮影時点の最新値」で一意に決まる。
- Arduino は時計を持たないため、`measured_at` は **サーバーの受信時刻** を使う。
- 時刻はすべて UTC の `timestamptz` で保存し、表示側で日本時間に変換する。
- `/conditions/latest` は各値の取得時刻と「何分前の値か」を返し、センサー停止時に古い値と分かるようにする。

### 朝の提案（③）で使う気象データ

- 屋外: 現在値に加えて **今日の予報**（最高・最低気温、時間ごとの気温・降水確率）を返す。
- 室内: 過去の傾向は使わず、最新値をそのまま使う。

### 服と気象条件の結び付け

服の画像そのものには気象情報がないが、`服 → 元のコーデ → 撮影時の気象データと判定` とたどれる。
服の一覧APIは、各服にこの情報（判定・室内/屋外DI・その差・撮影日時・フィードバック）を付けて返す。

**DBサーバーは服の絞り込み・選択をしない。** どの服を選ぶかはAI側の責務。
当面はAI側が全件を参照して判定する方針なので、一覧APIは条件なしで全件を返す。

## 3. テーブル定義（案）

### users
| 列 | 型 | 備考 |
|---|---|---|
| id | serial PK | |
| name | text | |
| token | text unique | 簡易認証用 |
| created_at | timestamptz | |

### devices
| 列 | 型 | 備考 |
|---|---|---|
| id | serial PK | |
| room_name | text | 講義室名 |
| api_key | text unique | センサー認証用 |

### indoor_readings
| 列 | 型 | 備考 |
|---|---|---|
| id | bigserial PK | |
| device_id | FK → devices | |
| temperature | real | センサー値 |
| humidity | real | 当面はAPI値 |
| humidity_source | text | `'api'` / `'sensor'` |
| discomfort_index | real | サーバーで計算 |
| measured_at | timestamptz | |

### outdoor_weather
| 列 | 型 | 備考 |
|---|---|---|
| id | bigserial PK | |
| temperature | real | |
| humidity | real | |
| weather_code | int | WMOコード |
| precipitation | real | |
| discomfort_index | real | サーバーで計算 |
| forecast_max_temp | real | 今日の最高気温 |
| forecast_min_temp | real | 今日の最低気温 |
| forecast_hourly | jsonb | 今日の時間ごとの気温・湿度・降水確率 |
| fetched_at | timestamptz | |
| raw_json | jsonb | APIレスポンスをそのまま保存 |

### outfits（① コーデ写真）
| 列 | 型 | 備考 |
|---|---|---|
| id | bigserial PK | |
| user_id | FK → users | |
| image_path | text | 画像ファイル本体はディスク保存 |
| taken_at | timestamptz | |
| indoor_reading_id | FK → indoor_readings | 判定した部屋がセンサーの部屋なら、そのときのセンサー値 |
| outdoor_weather_id | FK → outdoor_weather | 同上 |
| verdict | text | `'good'` / `'bad'` |
| comment | text null | AIのコメント（任意） |
| user_feedback | text null | `hot` / `cold` / `just_right`（日本語で受け取っても英語で保存） |
| analysis | jsonb null | AIの分析結果（level, score, items など。中身はAI側が決める） |
| room_id | FK → rooms, null可 | 判定した部屋（指定がなければセンサーの部屋）。古いコーデは null |
| indoor_temperature | real null | 判定時の室温（センサーの部屋は実測、ほかはダミー） |
| indoor_humidity | real null | 判定時の湿度 |
| indoor_temperature_source | text null | `sensor` / `dummy` |
| indoor_humidity_source | text null | `sensor` / `dummy` |
| indoor_discomfort_index | real null | 判定時の室内の不快指数 |

### garments（② 上下に分けた服）
| 列 | 型 | 備考 |
|---|---|---|
| id | bigserial PK | |
| user_id | FK → users | |
| outfit_id | FK → outfits | 切り出し元のコーデ |
| part | text | `'top'` / `'bottom'` / `'outer'`（outer は将来用、当面は未使用の想定） |
| image_path | text | |
| meta | jsonb null | AIが付ける服の情報（type, color, clo, hidden など。中身はAI側が決める） |
| created_at | timestamptz | |

### recommendations（③ 今日のおすすめ）
| 列 | 型 | 備考 |
|---|---|---|
| id | bigserial PK | |
| user_id | FK → users | |
| top_garment_id | FK → garments | |
| bottom_garment_id | FK → garments, null可 | ワンピースなど上だけで完結するときは null |
| indoor_reading_id | FK → indoor_readings | 提案時の条件 |
| outdoor_weather_id | FK → outdoor_weather | 同上 |
| reason | text null | AIの理由（任意） |
| created_at | timestamptz | |

### rooms（今日のひろばの場所）
| 列 | 型 | 備考 |
|---|---|---|
| id | serial PK | |
| name | text unique | 図書館 / 201教室 / 学生食堂 / 実験室3（初回起動時に登録） |
| temperature | real null | **ダミー値**（センサーのある部屋はAPIでセンサー値に置き換えて返す） |
| humidity | real null | **ダミー値**（湿度を実測したときだけ置き換える） |

### room_posts（今日のひろばの体感の投稿）
| 列 | 型 | 備考 |
|---|---|---|
| id | bigserial PK | |
| room_id | FK → rooms | |
| user_id | FK → users | 投稿者（APIでは返さない） |
| feeling | text | `comfortable`(快適) / `slightly_cold`(少し寒い) / `very_cold`(かなり寒い) / `slightly_hot`(少し暑い) |
| comment | text null | ひとこと（500文字まで） |
| created_at | timestamptz | |

- データはハッカソン期間中すべて保存（削除しない）。
- 同じ服の重複は当面許容する（同一判定はしない）。必要になったら pgvector で対応。
- 写真はDBに入れず、Dockerボリュームに保存してパスだけDBに持つ。

## 4. API（案）

| メソッド | パス | 段階 | 内容 |
|---|---|---|---|
| GET | `/health` | — | 動作確認用 |
| POST | `/users` | — | ユーザー登録。`user_id` とトークンを返す |
| POST | `/readings` | — | Arduinoが室内温度を送信（`X-Device-Key` ヘッダ） |
| GET | `/conditions/latest` | ①③ | 室内・屋外の最新値、その差、今日の予報、各値の鮮度。`room_id`/`classroom` 指定で室内をその部屋の値に |
| POST | `/outfits` | ① | 写真（multipart）+ 判定結果 + `analysis`（JSON文字列、任意）+ 判定した部屋（`room_id`/`classroom`）を保存 |
| GET | `/outfits/{id}` | — | コーデの詳細（判定・分析・撮影時の気象データ・分割済みの服） |
| GET | `/outfits/{id}/image` | — | コーデ写真本体 |
| PATCH | `/outfits/{id}/feedback` | — | `hot`/`cold`/`just_right`（または 暑かった/寒かった/ちょうどいい）を記録 |
| POST | `/outfits/{id}/garments` | ② | 上下の服の画像を保存（multipart, `part` 指定, `meta` はJSON文字列で任意） |
| GET | `/users/{id}/outfits` | — | コーデ履歴（新しい順）。`limit`/`offset`、判定時の室温 `temp_min`/`temp_max`、判定した部屋 `classroom` で絞り込み可 |
| GET | `/users/{id}/garments` | ③ | ユーザーの服を全件返す（`meta`・`taken_at`・判定・撮影時の室内/屋外DI・差・フィードバック付き） |
| PATCH | `/garments/{id}/meta` | — | 服の `meta` を部分更新（渡したキーだけ上書き。例: `{"hidden": true}`） |
| GET | `/garments/{id}/image` | ③ | 服の画像本体 |
| POST | `/users/{id}/recommendations` | ③ | 提案結果を保存。`bottom_garment_id` は省略か null 可 |
| GET | `/users/{id}/recommendations/latest` | ③ | 最新のおすすめ |
| GET | `/rooms` | ひろば | 場所の一覧。室温・湿度（センサーのある部屋は実測、ほかはダミー）と今日の体感ごとの投稿数 |
| GET | `/rooms/{id}` | ひろば | 1つの場所の情報と今日の体感ごとの投稿数 |
| GET | `/rooms/{id}/posts` | ひろば | 投稿一覧（新しい順）。初期値は今日の分のみ、`all=true` で過去分も。`limit`/`offset` |
| POST | `/rooms/{id}/posts` | ひろば | 体感を投稿（要トークン）。`feeling` は英語コードでも日本語でも可 |

FastAPI の自動ドキュメント (`/docs`) をそのまま他メンバーへのAPI仕様として共有し、Android側はこの仕様に合わせて実装する。

- AI（外部AI APIの呼び出し）はAndroid側で行う。DBサーバーにAIは載せない。
- 今日のひろば: 場所の名前はDBが正で、フロントはAPIの `name` をそのまま表示する。「今日」は日本時間0時から。
  センサーの部屋（`.env` の `DEVICE_ROOM_NAME` = 201教室）だけ室温をセンサーの最新値にし、ほかの部屋はダミー値。
  どちらかは `temperature_source` / `humidity_source`（`sensor` / `dummy`）と `is_dummy` で分かる。
  一覧の取得は認証なし、投稿はユーザーのトークンが必要。
- ユーザー登録はアプリの初回起動時に `POST /users` を1回呼び、返ってきた `user_id` と `token` を端末に保存して使い回す。
- 適温の判定は部屋ごとに行う。判定前に `/conditions/latest?room_id=` でその部屋の値を取り、保存時に `POST /outfits` へ同じ部屋を渡す。
  コーデには判定した部屋と、そのときの室温・湿度・不快指数を記録する（ダミーの部屋でも記録できるよう値そのものを持つ）。
  コーデ一覧の室温・部屋の絞り込みもこの記録した値で行う。部屋を指定しなければセンサーの部屋（201教室）として扱う。
- 画像取得もトークン必須。Androidアプリでは画像読み込み時にヘッダー `X-User-Token` を付ける。
- CORS は設定しない（クライアントはAndroidアプリとArduinoのみで、ブラウザからは呼ばない）。

## 5. 技術スタック

- Python 3.12 / FastAPI / SQLAlchemy
- PostgreSQL 16（服の同一判定や類似検索をするなら pgvector を追加）
- 天気の定期取得: サーバー内のスケジューラ（APScheduler）
- Docker Compose（`api` と `db` の2コンテナ + 画像用ボリューム）

## 6. 未決事項

- [ ] キャンパスの緯度・経度
- [ ] センサーの送信間隔（案: 5分）
- [ ] 湿度センサー (DHT22 / SHT31) を入手したら `humidity_source = 'sensor'` に切り替え

## 7. 後回しにするもの

- [ ] 画像の形式・サイズ上限（案: JPEG、10MB、Android側で縮小）
- [ ] 画像取得時の認証（Android の画像読み込みライブラリでトークンをヘッダに付ける）
- [ ] デモ用のシードデータ投入スクリプト
- [ ] 開発中の Android からの接続先（本番マシン準備前）
- [ ] バックアップ（`pg_dump` の定期実行。優先度低）
