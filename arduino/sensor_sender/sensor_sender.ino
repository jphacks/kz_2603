// Arduino UNO R4 WiFi: 室内温度を一定間隔で DBサーバーの POST /readings に送る
//
// 準備:
//   1. arduino_secrets.h.example をコピーして arduino_secrets.h を作り、WiFi・キー・接続先を書く
//      （本番の接続先は公開リポジトリに載せないよう arduino_secrets.h に書く）
//   2. 温度センサーは LM61CIZ を A0 に接続

#include <WiFiS3.h>
#include "arduino_secrets.h"

// ---- 設定 --------------------------------------------------------------
#ifdef SECRET_SERVER_HOST
const char SERVER_HOST[] = SECRET_SERVER_HOST;
const int SERVER_PORT = SECRET_SERVER_PORT;
#else
const char SERVER_HOST[] = "192.168.0.10";       // arduino_secrets.h に書かなかったときの仮の値
const int SERVER_PORT = 8000;
#endif
const unsigned long SEND_INTERVAL_MS = 5UL * 60UL * 1000UL;  // 送信間隔: 5分

const int SENSOR_PIN = A0;
// -----------------------------------------------------------------------

WiFiClient client;  // HTTPS にする場合は WiFiSSLClient に変え、ポートを 443 にする
unsigned long lastSent = 0;
bool firstSend = true;

float readTemperatureC() {
  // LM61: 10mV/°C、0°Cで600mV。5V基準・14bit で読み、10回平均でノイズを減らす
  long sum = 0;
  for (int i = 0; i < 10; i++) {
    sum += analogRead(SENSOR_PIN);
    delay(10);
  }
  float voltage = (sum / 10.0) * 5.0 / 16383.0;
  Serial.print("A0 voltage: ");  // 確認用。値が安定したら消してよい
  Serial.println(voltage, 3);
  return (voltage - 0.6) * 100.0;
}

void connectWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;
  Serial.print("WiFi connecting to ");
  Serial.println(SECRET_SSID);
  while (WiFi.begin(SECRET_SSID, SECRET_PASS) != WL_CONNECTED) {
    Serial.println("  retry in 5s...");
    delay(5000);
  }
  // DHCP でIPが振られるまで少し待つ
  while (WiFi.localIP() == IPAddress(0, 0, 0, 0)) delay(500);
  Serial.print("WiFi connected, IP: ");
  Serial.println(WiFi.localIP());
  delay(2000);  // 接続直後は通信が安定しないので少し待つ
}

bool sendReading(float temperature) {
  if (!client.connect(SERVER_HOST, SERVER_PORT)) {
    Serial.println("connect failed");
    return false;
  }

  String body = String("{\"temperature\":") + String(temperature, 2) + "}";

  client.println("POST /readings HTTP/1.1");
  client.print("Host: ");
  client.println(SERVER_HOST);
  client.println("Content-Type: application/json");
  client.print("X-Device-Key: ");
  client.println(SECRET_DEVICE_KEY);
  client.print("Content-Length: ");
  client.println(body.length());
  client.println("Connection: close");
  client.println();
  client.print(body);

  // ステータス行 (例: "HTTP/1.1 201 Created") だけ読む
  unsigned long start = millis();
  while (!client.available() && millis() - start < 5000) delay(10);
  String status = client.readStringUntil('\n');
  client.stop();

  Serial.print("response: ");
  Serial.println(status);
  return status.indexOf(" 201 ") > 0;
}

void setup() {
  Serial.begin(115200);
  analogReadResolution(14);
  connectWiFi();
}

void loop() {
  if (firstSend || millis() - lastSent >= SEND_INTERVAL_MS) {
    firstSend = false;
    lastSent = millis();

    connectWiFi();
    float t = readTemperatureC();
    Serial.print("temperature: ");
    Serial.println(t);
    sendReading(t);
  }
  delay(100);
}
