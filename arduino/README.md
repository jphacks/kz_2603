# Arduino（室内温度センサー）

講義室の室温を測り、5分ごとに DB サーバー（[`../db_server`](../db_server)）の `POST /readings` に送る。

- ボード: Arduino UNO R4 WiFi
- センサー: LM61CIZ（アナログ出力の温度センサー）
- スケッチ: [`sensor_sender/sensor_sender.ino`](sensor_sender/sensor_sender.ino)

## 書き込みまで

1. Arduino IDE の ボードマネージャで **Arduino UNO R4 Boards** を入れ、ボードに **Arduino UNO R4 WiFi** を選ぶ
   （`WiFiS3.h: No such file or directory` はボードの選択ミス）
2. `sensor_sender/arduino_secrets.h.example` をコピーして `sensor_sender/arduino_secrets.h` を作り、
   Wi-Fi・キー・接続先（`SECRET_SERVER_HOST` / `SECRET_SERVER_PORT`）を書く。
   `SECRET_DEVICE_KEY` は DB サーバーの `.env` の `DEVICE_API_KEY` と同じ値にする。
   **`arduino_secrets.h` は Git に上げない（`.gitignore` 済み）。接続先もスケッチ本体には書かない。**
3. 温度センサー LM61CIZ を A0 に接続（文字の面を手前・足を下にして 左:+5V / 中:A0 / 右:GND）。
   逆に挿すと発熱するので注意。室温で A0 が 0.8〜0.9V 前後なら正常。
4. 書き込んでシリアルモニタ（115200bps）で `response: HTTP/1.1 201 Created` が出れば成功

- Wi-Fi は **2.4GHz帯** のみ。学籍番号でログインする方式（WPA2-Enterprise）やブラウザでログインする方式には非対応。
- 湿度センサーを付けたときは、送る JSON に `humidity` を足せば DB サーバー側で実測値として扱われる。
- Linux で書き込みに失敗するときは `sudo ~/.arduino15/packages/arduino/hardware/renesas_uno/*/post_install.sh` を実行してUSBを挿し直す。

| シリアルモニタ | 原因 |
|---|---|
| `retry in 5s...` が続く | Wi-Fi に繋がらない（SSID・パスワード・2.4GHz帯か） |
| `connect failed` | サーバーに届かない（接続先・ネットワーク）。接続直後の1回なら次の送信で回復する |
| `HTTP/1.1 401` | `SECRET_DEVICE_KEY` がサーバーの `DEVICE_API_KEY` と違う |
| `HTTP/1.1 422` | 温度が範囲外（-40〜80°C）。配線か計算式を確認 |
