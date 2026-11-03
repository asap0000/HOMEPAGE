/*
 * AlcoholChecker.ino
 * ESP32 + MQ-3 アルコールセンサー  BLE ファームウェア
 *
 * 必要ライブラリ:
 *   - ESP32 Arduino Core (BLEDevice/BLEServer/BLEUtils/BLE2902 同梱)
 *   - U8g2 (OLED 表示用) -- Arduino IDE ライブラリマネージャからインストール
 *
 * ピン配線:
 *   MQ-3  AOUT -> GPIO 34
 *   OLED  SDA  -> GPIO 21  SCL -> GPIO 22
 *   緑LED       -> GPIO 25 (220Ω経由)
 *   赤LED       -> GPIO 26 (220Ω経由)
 *   ブザー      -> GPIO 27
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <math.h>

// ── BLE UUID ────────────────────────────────────────────────
#define SERVICE_UUID   "4fafc201-1fb5-459e-8fcc-c5c9c3319145"
#define BAC_CHAR_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CMD_CHAR_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a9"

// ── ピン ────────────────────────────────────────────────────
#define PIN_MQ3     34
#define PIN_LED_G   25
#define PIN_LED_R   26
#define PIN_BUZZER  27

// ── 基準値 ──────────────────────────────────────────────────
// 日本の道路交通法: 呼気中アルコール濃度 0.15 mg/L 以上で行政処分
#define BAC_LIMIT   0.15f

// MQ-3 感度曲線定数 (アルコール: Rs/R0 vs BAC の近似)
// ★ 実際の機器では R0 をゼロエア(清潔な空気)で校正すること
#define MQ3_RL       10.0f    // 負荷抵抗 kΩ
#define MQ3_R0       10.0f    // 清潔空気中センサ抵抗 kΩ (要校正)
#define MQ3_CURVE_A  0.3934f  // y = A * x^B  (x = Rs/R0, y = BAC mg/L)
#define MQ3_CURVE_B -1.504f

// ── OLED ────────────────────────────────────────────────────
U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);

// ── BLE オブジェクト ─────────────────────────────────────────
BLEServer*           pServer     = nullptr;
BLECharacteristic*   pBacChar    = nullptr;
BLECharacteristic*   pCmdChar    = nullptr;

// ── 状態 ─────────────────────────────────────────────────────
bool     deviceConnected = false;
bool     measuring       = false;
float    peakBac         = 0.0f;
unsigned long measureStart = 0;
const unsigned long MEASURE_MS = 5000;  // 5 秒間計測

// ── BLE コールバック ─────────────────────────────────────────
class ServerCB : public BLEServerCallbacks {
    void onConnect(BLEServer*) override {
        deviceConnected = true;
        showOled("接続済み", "");
    }
    void onDisconnect(BLEServer*) override {
        deviceConnected = false;
        measuring = false;
        showOled("待機中...", "スキャン再開");
        BLEDevice::startAdvertising();
    }
    static void showOled(const char* line1, const char* line2);
};

class CmdCB : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string v = pChar->getValue();
        if (v == "START") {
            measuring    = true;
            measureStart = millis();
            peakBac      = 0.0f;
            digitalWrite(PIN_LED_G, LOW);
            digitalWrite(PIN_LED_R, LOW);
        } else if (v == "RESET") {
            measuring = false;
            peakBac   = 0.0f;
            digitalWrite(PIN_LED_G, LOW);
            digitalWrite(PIN_LED_R, LOW);
        }
    }
};

// ── 関数: OLED 表示 ──────────────────────────────────────────
void showOled(const char* line1, const char* line2, const char* line3 = "") {
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_unifont_t_japanese2);
    u8g2.drawUTF8(0, 18, line1);
    u8g2.drawUTF8(0, 38, line2);
    u8g2.drawUTF8(0, 58, line3);
    u8g2.sendBuffer();
}

void ServerCB::showOled(const char* line1, const char* line2) {
    ::showOled(line1, line2);
}

// ── 関数: MQ-3 読み取り → BAC (mg/L) ────────────────────────
float readBacMgL() {
    // 10 回平均
    long sum = 0;
    for (int i = 0; i < 10; i++) {
        sum += analogRead(PIN_MQ3);
        delay(5);
    }
    float adc  = (float)sum / 10.0f;
    float vOut = adc * 3.3f / 4095.0f;
    if (vOut <= 0.01f) return 0.0f;
    float rs   = MQ3_RL * (3.3f - vOut) / vOut;
    float ratio = rs / MQ3_R0;
    float bac   = MQ3_CURVE_A * powf(ratio, MQ3_CURVE_B);
    return max(0.0f, bac);
}

// ── 関数: BLE で BAC 送信 ────────────────────────────────────
void sendBac(float bac) {
    uint8_t buf[4];
    memcpy(buf, &bac, 4);
    pBacChar->setValue(buf, 4);
    pBacChar->notify();
}

// ── 関数: 計測完了処理 ───────────────────────────────────────
void finalizeMeasurement() {
    measuring = false;
    sendBac(peakBac);

    char bacStr[16];
    snprintf(bacStr, sizeof(bacStr), "%.3f mg/L", peakBac);

    if (peakBac < BAC_LIMIT) {
        // 合格
        showOled("★ 合格", bacStr, "0.15未満");
        for (int i = 0; i < 3; i++) {
            digitalWrite(PIN_LED_G, HIGH); delay(300);
            digitalWrite(PIN_LED_G, LOW);  delay(200);
        }
        digitalWrite(PIN_LED_G, HIGH);
    } else {
        // 不合格
        showOled("✗ 不合格", bacStr, "運転不可");
        digitalWrite(PIN_LED_R, HIGH);
        tone(PIN_BUZZER, 880, 3000);
        delay(3000);
        noTone(PIN_BUZZER);
    }
}

// ── setup ────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);

    pinMode(PIN_LED_G, OUTPUT);
    pinMode(PIN_LED_R, OUTPUT);
    pinMode(PIN_BUZZER, OUTPUT);

    // OLED 初期化
    u8g2.begin();
    showOled("ウォームアップ", "30秒待機...");

    // センサ予熱: 30 秒 (点滅で表示)
    for (int i = 0; i < 30; i++) {
        digitalWrite(PIN_LED_G, i % 2 == 0 ? HIGH : LOW);
        digitalWrite(PIN_LED_R, i % 2 != 0 ? HIGH : LOW);
        delay(1000);
    }
    digitalWrite(PIN_LED_G, LOW);
    digitalWrite(PIN_LED_R, LOW);

    // BLE 初期化
    BLEDevice::init("AlcoholChecker");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCB());

    BLEService* svc = pServer->createService(SERVICE_UUID);

    // BAC 通知キャラクタリスティック
    pBacChar = svc->createCharacteristic(
        BAC_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pBacChar->addDescriptor(new BLE2902());

    // コマンド書き込みキャラクタリスティック
    pCmdChar = svc->createCharacteristic(
        CMD_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCmdChar->setCallbacks(new CmdCB());

    svc->start();

    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    BLEDevice::startAdvertising();

    showOled("準備完了", "アプリ接続待ち");
    Serial.println("BLE ready");
}

// ── loop ─────────────────────────────────────────────────────
void loop() {
    if (!measuring) {
        delay(100);
        return;
    }

    unsigned long elapsed = millis() - measureStart;
    float bac = readBacMgL();
    if (bac > peakBac) peakBac = bac;

    // 進捗表示
    char prog[32], bacStr[16];
    int  sec = (int)(elapsed / 1000);
    snprintf(prog,   sizeof(prog),   "計測中... %d/5s", sec);
    snprintf(bacStr, sizeof(bacStr), "%.3f mg/L", bac);
    showOled("息を吹きかけて", prog, bacStr);

    // リアルタイム送信
    if (deviceConnected) sendBac(bac);

    if (elapsed >= MEASURE_MS) {
        finalizeMeasurement();
    }

    delay(200);
}
