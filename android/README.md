# プライバシーカメラ (Privacy Camera)

個人情報を含む対象（運転免許証・保険証・マイナンバーカード・預金通帳など）を撮影するための
Android 専用カメラアプリです。**このアプリで撮った写真はすべて機密扱い**とし、

1. **クラウドに自動アップロードされない場所**（アプリ専用の内部ストレージ）に保存し、
2. **原本は端末内で暗号化**（AES-256-GCM / Android Keystore）して保存、
3. ローカルには**マスク済みの画像**を生成して保存、
4. **このアプリ内で開いたときだけ復号して正規の内容を表示**します。

> 設計方針：画像の内容を解析して個人情報を「判定」することはしません。
> このアプリで撮影した時点で機密とみなし、無条件にマスキング・暗号化します。

---

## セキュリティ設計

| 要件 | 実装 |
|------|------|
| Google フォト / Amazon フォト等にアップロードされない | 画像はすべて `filesDir/secure/`（アプリ専用内部ストレージ）に保存。メディアスキャナの対象外で、ギャラリーアプリからは不可視。`.nomedia` も設置 |
| アプリ自身からの送信も不可能に | `AndroidManifest.xml` で **INTERNET 権限を付与していない**ため、ネットワーク送信が技術的に不可能 |
| 原本の保護 | AES-256-GCM で暗号化。鍵は **Android Keystore** 内に生成され端末外に取り出せない |
| クラウドバックアップ・端末間移行での流出防止 | `allowBackup=false` ＋ `data_extraction_rules.xml` で全データを除外 |
| スクショ / 履歴サムネからの漏洩防止 | Activity に `FLAG_SECURE` を設定 |
| マスク済み画像のみ共有可能 | ビューアの「書き出し」で**マスク版のみ**を共有ギャラリー（`Pictures/PrivacyCamera`）へ保存可能。原本は決して書き出されない |

### 保存レイアウト
```
filesDir/secure/
├── .nomedia
├── originals/<id>.enc   ← AES-GCM 暗号化された原本 JPEG
└── masked/<id>.jpg      ← マスク済みプレビュー（どこに出しても安全）
```

---

## 画面構成
- **CameraScreen** … CameraX のプレビューとシャッター。撮影すると暗号化＋マスク保存。
- **GalleryScreen** … 保護フォルダ内のマスク済みサムネイル一覧。
- **ViewerScreen** … タップした写真を**復号して正規表示**（アプリ内のみ）。マスク表示への切替、マスク版の書き出し、削除が可能。

## 技術スタック
- Kotlin / Jetpack Compose (Material3)
- CameraX (`camera-camera2` / `camera-lifecycle` / `camera-view`)
- Android Keystore (AES-256-GCM)
- minSdk 26 / targetSdk 35

---

## ビルド方法
Android SDK が必要です（Android Studio 推奨）。

```bash
cd android
# 1) SDK の場所を local.properties に設定（Android Studio で開けば自動）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2) デバッグ APK をビルド
./gradlew assembleDebug

# 3) 端末へインストール（USB デバッグ有効化済みの実機 / エミュレータ）
./gradlew installDebug
```

Android Studio で `android/` フォルダを開けば、そのままビルド・実行できます。

---

## 今後の拡張候補
- 復号表示の前に生体認証（BiometricPrompt）を要求
- マスク方式の選択（黒塗り / ぼかし / 部分マスク）
- 領域指定での部分マスキング（ML Kit による文字領域検出の追加）
- フォルダ分け・タグ付け
