# バックエンド（端末内・`:backend` モジュール）

LayeRoom のバックエンドです。**Android アプリの中で動き**、画面（`:app`）から呼ばれて、Ubuntu 上の db_server と OpenAI を呼びます。

```
Android 端末
 ├─ :app（フロント・画面）
 │     │  LayeRoomViewModel（app）が backend.xxx() を呼ぶ
 │     ▼
 └─ :backend（このモジュール）
       ├─ ai/      判定・提案（プログラム）と OpenAI 呼び出し（写真の読み取り・アドバイス文）
       ├─ db/      db_server との通信
       └─ image/   写真の切り抜き・上下を1枚にする（Bitmap）
            │
            ▼
   Ubuntu: db_server（FastAPI）→ PostgreSQL
```

判定・提案のロジックは、以前の Python 版 AI からの移植です（Python 版は削除済み。今はこのモジュールが正）。移植したときに、Python 版と同じ入力で同じ結果になることを約2,600件の答え合わせで確かめ、その答えを `src/test/resources/python_parity.json` に固定しています（`PythonParityTest`）。

## 設定（`Layeroom_app/local.properties`。コミットしない）

```properties
DB_SERVER_URL=http://<db_serverのアドレス>:<ポート>
OPENAI_API_KEY=sk-...
# 任意: キーを端末に置かず、Ubuntu 上の中継サーバー経由にするとき（そのときは OPENAI_API_KEY は空でよい）
# OPENAI_BASE_URL=http://<中継サーバー>/v1
```

**注意**: `OPENAI_API_KEY` はアプリ（APK）に入るので、APK を配った相手が取り出せます。配る前に、中継サーバー経由（`OPENAI_BASE_URL`）にするか、使用上限を設定したキーにしてください。

## 画面から使う

```kotlin
val backend = (application as LayeRoomApplication).backend ?: return   // 未設定ならサンプル表示
lifecycleScope.launch {
    try {
        if (backend.session() == null) backend.start("たろう")     // 初回だけ
        val rooms = backend.rooms()                                 // 場所のプルダウン
        val cond = backend.conditions("図書館")                     // 提案タブの室温・湿度・金沢の気温
        val review = backend.review(photoJpeg, "図書館")            // 写真でコーデ診断（まだ保存しない）
        backend.save(review.draftId)                               // 「このコーデを保存」
    } catch (e: BackendException) {
        // e.kind: INPUT / NOT_LOGGED_IN / NOT_FOUND / NO_CLOTHES / AI / SERVER、e.message は日本語
    }
}
```

| 画面（Layeroom_app/README.md） | 呼ぶもの |
|---|---|
| 起動時 | `session()`（null なら `start(nickname)`） |
| 場所のプルダウン | `rooms()`（センサーの部屋は実測、ほかは仮の値。今日の体感の件数つき） |
| 提案タブ: 室温・湿度・金沢の気温・不快指数 | `conditions(classroom)`（`notices` に注意書き） |
| 提案タブ・コーデAI B「今日の服装を提案して」 | `suggestions(classroom)`（`mode == "general"` なら `general` を表示）。画像は `comboImage(upperId, lowerId)` |
| コーデAI A「この写真で診断する」 | `review(jpeg, classroom)`（切り抜きは `itemImages`） |
| 診断結果の「このコーデを保存」 | `save(draftId)`（30分で消える） |
| 提案結果の「このコーデを保存」 | `saveRecommendation(top, bottom, reason)` / `latestRecommendation()` |
| 気温別コーデ | `outfits(temp, kind = "outdoor" or "indoor", within = 2.0)`。画像は `comboImage(upper?.garmentId, lower?.garmentId)` |
| 着た感想 | `feedback(outfitId, "暑かった" / "寒かった" / "ちょうどいい")` |
| 保存した服・誤認識の削除 | `garments()` / `hideGarment(id)` / `garmentImage(id)` |
| みんなのひろば | `posts(roomId)` / `post(roomId, feeling, comment)`（体感の選択肢は `feelings()`） |

どれも `suspend` 関数です（コルーチンの中で呼ぶ）。通信と画像処理はメインスレッドを止めません。

## テスト

```sh
export JAVA_HOME=<JDK 17〜23 の場所>   # Gradle 8.11.1 は JDK 25 以降では動かない
./gradlew :backend:testDebugUnitTest            # PC 上（db_server・OpenAI は偽物。Python 版との答え合わせ・Bitmap の画像処理を含む）
LIVE_DB_SERVER_URL=http://<db_server>:<port> OPENAI_API_KEY=sk-... ./gradlew :backend:testDebugUnitTest --tests "*LiveTest*"   # 本物につなぐ
./gradlew :backend:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.dbServerUrl=http://<db_server>:<port> \
  -Pandroid.testInstrumentationRunnerArguments.openAiKey=sk-...                                            # 端末（エミュレーター）の上で本物につなぐ
```

本物につなぐテストは db_server にデータを書き込みます（利用者名は `test-` で始まる。ひろばの投稿は `[テスト]` で始まる）。

判定の表や式（`ai/Comfort.kt` など）を意図して変えたときは、`PythonParityTest` が失敗します。変えた部分の答え合わせは、そのテストを外すか、新しい期待値のテストに置き換えてください（Python 版が無いので答えは作り直せません）。

## 既知の制限

- 下なし（ワンピースなど）のおすすめは保存できない（db_server が `bottom_garment_id` を必須にしている）。
- 👍/👎（服ごとの好み）は画面に無いので移植していない。
