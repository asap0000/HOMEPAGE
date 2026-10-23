# 職業ドライバー向け DIY アルコールチェッカー

ESP32 + MQ-3 センサーで自作したアルコール検知器と、Bluetooth LE でスマホ（Android）を連動させるシステムです。

---

## システム概要

```
┌──────────────────────┐        BLE         ┌────────────────────┐
│  DIY アルコール検知器  │ ←───────────────── │  Android アプリ     │
│                      │  BAC 値 notify      │                    │
│  ESP32 + MQ-3        │ ─────────────────→ │  ・ドライバー管理   │
│  OLED + LED + Buzzer │  START/RESET cmd    │  ・チェック記録     │
│                      │                    │  ・CSV 出力         │
└──────────────────────┘                    └────────────────────┘
```

**判定基準**: 呼気中アルコール濃度 **0.15 mg/L** 以上で不合格  
（道路交通法施行令第 44 条の 3 に基づく行政処分の基準値）

---

## ディレクトリ構成

```
alcohol-checker/
├── hardware/
│   ├── parts_list.md                  # 部品リスト・費用目安
│   ├── wiring.md                      # 配線図
│   └── firmware/
│       └── AlcoholChecker/
│           └── AlcoholChecker.ino    # ESP32 ファームウェア
└── android-app/                       # Android スマホアプリ
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    └── app/
        └── src/main/
            ├── AndroidManifest.xml
            └── java/com/alcoholchecker/
                ├── MainActivity.kt
                ├── AlcoholCheckerApp.kt
                ├── ble/BleManager.kt           # BLE スキャン・接続
                ├── data/
                │   ├── CheckRecord.kt          # 記録エンティティ + DAO
                │   ├── Driver.kt               # ドライバーエンティティ + DAO
                │   ├── CheckDatabase.kt        # Room データベース
                │   └── CheckRepository.kt
                ├── viewmodel/
                │   └── AlcoholCheckerViewModel.kt
                └── ui/
                    ├── HomeScreen.kt           # デバイス接続画面
                    ├── CheckScreen.kt          # チェック実施画面
                    ├── HistoryScreen.kt        # 記録一覧・CSV 出力
                    ├── DriverScreen.kt         # ドライバー管理
                    └── theme/Theme.kt
```

---

## ハードウェアのセットアップ

### 1. 部品を揃える
→ [`hardware/parts_list.md`](hardware/parts_list.md) を参照

### 2. 配線する
→ [`hardware/wiring.md`](hardware/wiring.md) を参照

### 3. ファームウェアを書き込む

**必要環境**
- Arduino IDE 2.x
- ESP32 Arduino Core (`https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`)
- ライブラリ: **U8g2** (ライブラリマネージャからインストール)

**書き込み手順**
1. `hardware/firmware/AlcoholChecker/AlcoholChecker.ino` を Arduino IDE で開く
2. ボード: `ESP32 Dev Module`、ポート: 接続した COM ポートを選択
3. `MQ3_R0` を実測値に合わせて校正（後述）
4. 書き込み (→)

### 4. MQ-3 センサーの校正 (重要)

新鮮な空気中でセンサーを 5 分以上通電→ ADC 値を読み取り、計算式で `R0` を算出します。

```cpp
// シリアルモニタ (115200 baud) でADC値を確認
// 例: ADC = 1200 のとき
float vOut = 1200.0 * 3.3 / 4095.0;  // ≒ 0.966V
float rs   = 10.0 * (3.3 - 0.966) / 0.966;  // ≒ 24.2 kΩ
// 清潔空気中の Rs/R0 は MQ-3 データシートより約 9.8
float R0   = rs / 9.8;  // ≒ 2.47 kΩ  ← この値を MQ3_R0 に設定
```

---

## Android アプリのビルド

### 必要環境
- Android Studio Ladybug (2024.2) 以降
- Android SDK API 35
- JDK 17

### ビルド手順

```bash
cd alcohol-checker/android-app

# Gradle Wrapper が必要な場合は親プロジェクトからコピー
cp -r ../android/gradle .
cp ../android/gradlew .
cp ../android/gradlew.bat .

# デバッグ APK ビルド
./gradlew assembleDebug

# 実機インストール (USB デバッグ有効化済みの端末)
./gradlew installDebug
```

Android Studio で `android-app/` フォルダを開いても OK です。

---

## アプリの使い方

### 初期設定

1. **ドライバー登録**  
   「ドライバー」タブ → ＋ボタン → 社員ID・氏名・免許番号・車両番号を入力

### 乗務前後のチェック手順

1. **ホーム**タブ → 「デバイスに接続」をタップ（デバイス名: `AlcoholChecker` を自動検索）
2. **チェック**タブを開く
3. ドライバーを選択し「乗務前」または「乗務後」を選ぶ
4. 「チェック開始」をタップ
5. マウスピースを付けてセンサーに 5 秒間息を吹き込む
6. 結果（合格 / 不合格・BAC 値）を確認
7. 必要に応じて備考を入力し「記録を保存」

### 記録の確認・出力

- **記録**タブで全チェック履歴を一覧表示
- 右上の↓アイコンで CSV ファイルをメール・クラウドへ共有
- CSV 形式: `ID, 日時, 社員ID, 氏名, 種別, 測定値, 判定, 備考, 緯度, 経度`

---

## BLE プロトコル仕様

| 項目 | UUID |
|------|------|
| Service | `4fafc201-1fb5-459e-8fcc-c5c9c3319145` |
| BAC 通知 (Notify) | `beb5483e-36e1-4688-b7f5-ea07361b26a8` |
| コマンド (Write) | `beb5483e-36e1-4688-b7f5-ea07361b26a9` |

**BAC 通知**: 4バイト float (IEEE 754, リトルエンディアン) で `mg/L` 値を送信。  
計測中は 200ms 間隔で送信し、完了後に最終値を通知。

**コマンド**:
- `START` → 計測開始（デバイスが 5 秒間計測しピーク値を送信）
- `RESET` → リセット

---

## 法律・免責事項

- 本システムは**研究・教育目的**で作成されたものです。
- 業務用途で法的に有効な記録を作成する場合は、**国土交通省認定のアルコール検知器**（IT点呼対応機器等）をご使用ください（道路交通法・貨物自動車運送事業輸送安全規則等に基づく）。
- MQ-3 センサーは温度・湿度・センサー個体差によって精度が変動します。本デバイスの測定値を法的根拠とすることはできません。
- 本ソフトウェアはいかなる保証もなく「現状のまま」提供されます。
