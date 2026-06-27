# プロジェクト状況・引き継ぎ（PROJECT STATUS / HANDOFF）

> 新しいセッションはこのファイルを最初に読めば、会話履歴なしで続きから作業できます。
> **真実の source はリポジトリ**。会話は補助。コード・アイコン・CI・テスト項目表は全て push 済み。

最終更新: 2026-06-25

## 0. 現在地
- 作業ブランチ: `claude/android-camera-pii-masking-io6ucl`
- 最新コミット: `5d55c4a`（作業ツリーはクリーン、origin と同期済み）
- 最新リリース: **`v0.3-beta`**（署名済み Lite/Pro APK を GitHub Release に配布済み）
- 開発・ビルド側で即対応すべき課題は現状ゼロ。残りは「販売する/しない」の意思決定待ち。

## 1. プロダクト概要
プライバシー重視のカメラアプリ（Android / Kotlin + Jetpack Compose）。1コードベースから2フレーバー：
- **Lite**（`com.privacycamera.lite`）: 無料・お試し。保存上限あり・自動モザイクのみ。
- **Pro**（`com.privacycamera`）: 有料想定。無制限・取り込み・高度マスキング等。
- 出し分けは `BuildConfig.IS_PRO`（`com.privacycamera.Tier`）が唯一の判定点。`Tier.LITE_SAVE_LIMIT = 30`。
- minSdk 26 / targetSdk 35。
- 設計思想（＝売り）: **INTERNET権限なし**（ネット送信が技術的に不可能）、原本は**AES-256-GCM × Android Keystore**で暗号化、表示は既定でマスク、`FLAG_SECURE`、`allowBackup=false`。

## 2. 実装済み機能
- 撮影 → **全面モザイク**のマスク済みプレビュー生成（原本は暗号化保存、認証で正規表示、アクセスログ記録）。
- カテゴリ/メモ、マスク版のギャラリー書き出し、画像編集（トリミング/明度/コントラスト）。
- **Lite 保存上限(30)＋入れ替え運用**、**Lite 移行書き出し＝平文ZIP**（manifest.json＋`<uuid>.jpg`）。
- **Pro 取り込み3系統**: ①Lite移行（uuid重複排除・**累計30枚上限**・永続管理）②汎用画像（上限なし）③**暗号化バックアップ復元**（live＋ゴミ箱でuuid重複排除・誤パス検出）。
- **暗号化バックアップ書き出し**（Pro・パスフレーズ・`.pcbak`／AES-256-GCM・PBKDF2）。
- **ゴミ箱（30日ソフト削除）**: 復元（上限ガード付き）・完全削除・空にする・期限切れ自動削除。`TRASH_TTL_MILLIS=30日`。
- **高度マスキング（Pro）**: `MaskingEngine.MaskSpec`（スタイル=モザイク/ぼかし/黒塗り・強さ・全体/範囲指定）、ライブプレビュー、仕様はメタに保存し再編集可、**原本は不変**。
- **フレーバー別アプリアイコン**（カメラ＋南京錠。Pro=濃紺+金縁ロック、Lite=緑+FREE）。adaptive icon、前景を安全領域にインセット済み。
- **versionCode 自動採番**（下記）。

主要ファイル: `app/src/main/java/com/privacycamera/` 配下（`data/SecurePhotoStore.kt`, `data/BackupManager.kt`, `data/MaskingEngine.kt`, `crypto/`, `ui/`, `viewmodel/PhotoViewModel.kt`, `Tier.kt`）。

## 3. ビルド・署名・リリース運用
- **署名鍵**: PKCS12 keystore（`app/release.keystore.gpg` に GPG対称暗号で格納）。**今後ずっと固定維持**（鍵が変わると上書き更新不可＝データ消失）。
  - 署名鍵 SHA-256（ADC等で使用・公開可）:
    `57:D6:89:DF:BD:95:87:15:92:26:F2:44:6D:61:06:6B:45:9D:93:96:A9:44:93:FE:A9:07:F2:54:EE:DD:4C:36`
  - PKCS12は鍵PW=ストアPWに強制されるため、gradleは `keyPassword = RELEASE_STORE_PASSWORD` を使用（`RELEASE_KEY_PASSWORD` の値に非依存）。
  - 秘密値（GPGパスフレーズ・ストアPW・エイリアス`upload`・鍵PW）は **GitHub Actions Secrets** に登録済み（`KEYSTORE_PASSPHRASE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`）。**この文書には値を書かない**（パスワードマネージャ等で別管理）。
- **versionCode/versionName 自動採番**（`app/build.gradle.kts` + `.github/workflows/release.yml`）:
  - `versionCode = 1000 + github.run_number`（単調増加）、`versionName = タグから先頭v除去`。
  - ローカル/デバッグCIは `1` / `0.0-dev` にフォールバック。
  - 注意: beta v0.1〜v0.3 は versionCode=1。次リリースは ~1011 になり >1 なので上書き更新可。
- **CI**: `android.yml`（push連動でdebug/release両ビルド＝健全性確認）、`release.yml`（署名APKをGitHub Releaseへ）。
- **リリースの回し方（重要）**: この実行環境では **git tag の push が 403 で不可**。タグの代わりに **workflow_dispatch** で起動する：
  - GitHub MCP: `mcp__github__actions_run_trigger`（`method: run_workflow`, `workflow_id: release.yml`, `ref: <作業ブランチ>`, `inputs: {tag: "vX.Y-beta"}`）。
  - これで `ref` のコード（＝作業ブランチHEAD）をビルドし、`action-gh-release` がタグ付きReleaseを作成・APK添付。
- リポジトリスコープ: GitHub MCP は `asap0000/homepage` のみ。

## 4. 課題トリアージ（要否/可否/状態）
| # | 課題 | 状態 |
|---|---|---|
|1|実機βテスト（`docs/beta-test-checklist.md`）|🔄 結果待ち（v0.3で継続）|
|2|アイコン過大/FREE見切れ修正|✅ 完了|
|3|versionCode 自動採番|✅ 完了|
|4|署名鍵の固定維持|✅ 方針確定（維持のみ）|
|5|復元時カスタムマスク非搬送|⏸ 既知の制約・保留|
|6|STORESでZIP配信（apk直アップ不可→zip化）|⏸ 方針確定・実体未作成|
|7|特商法表記＋プライバシーポリシー作成|⏸ テンプレ提示済・正式化保留|
|8|STORES「住所・電話 非公開」設定利用|⏸ 方針確定（開設時に設定）|
|9|氏名公開の残存→法人化で回避|⏸ 保留（規模次第）|
|10|既存購入者への無償更新ルート（再DL/¥0クーポン/通知）|⏸ 設計済・実装保留|
|11|配布ZIP同梱物（インストール手順書/更新の受け取り方README）|⏸ 未作成|
|12|海賊版・ライセンス検証|⏸ 任意・保留|
|13|ADC登録（Limited20台 / Full$25）|⏸ 計画（2027 global前に）|
|14|パッケージ登録（Pro/Lite=2件・proof APK）|⏸ ADC時。proof APKは要スニペット|
|15|専用Googleアカウント新設＋分離運用|⏸ 本人作業・保留|
|16|VO＋個人事業主（特商法/税務）|⏸ 検討中（STORES非公開機能で当面不要かも）|
|17|他OS（Win/mac/iOS）の提供元不明対処|⏸ 将来・保留|
|18|ニッチ市場テーマ探索（脱Google/ローカル完結シリーズ）|⏸ 戦略・保留|
|19|ポジショニング言語化（LP/商品説明コピー）|⏸ 未着手|
|20|想定問答（盗撮/スキミング反証）|⏸ 言語化済（履歴）・Pro完成後に詰める合意|

**販売する/しない・時期の意思決定が #6〜#14 をまとめて動かすトリガ。**

## 5. 配布・販売の調査結論
### STORES（EC）
- ダウンロード販売: ≤1GB/件、購入で自動配信（購入ゲート）。自作ソフトの販売可（違法/著作権侵害のみ禁止）。
- ⚠️ **許可形式に `.apk` なし**（zip は可）→ **APKをZIPに包んで配る**。中身に手順書・READMEを同梱。
- 既存購入者: **会員ログイン購入なら購入履歴から無期限で再DL**可。→ 無償更新は「ファイル差し替え＋再DL＋告知」or「¥0クーポン」。
- **「所在地・電話番号 非公開」機能**（個人事業主向け）: STORES社の住所/電話が代理表示され、自宅住所/電話を隠せる。ただし **事業者氏名＝本名は表示必須**（屋号のみ不可）。氏名も隠すなら**法人化**。
- 買い手側: デジタルDLは配送先住所が不要 → 買い手の個人情報露出は最小（メルカリ的な相互住所交換は起きない）。

### Android Developer Console（ADC）＝開発者確認（Play掲載とは別）
- 個人で登録可（**D-U-N-S不要**）。**Limited**（無料・≤20台・政府ID不要・2026/8開始）／**Full**（$25・政府ID＋住所証明）。
- **パッケージ登録はアプリごと**（SHA-256登録＋所有証明APLをアップ）。Pro/Lite=2件。
- 強制: 4か国2026/9 → **global 2027**（日本は概ね2027）。効果は「未登録＝身元不明アプリのブロック回避」。
- ※「不明なアプリのインストール許可」（提供元別）は**仕様上ずっと残る**。現時点の日本ではPlay Protect表示が即ゼロになるとは限らない。
- 住所運用案: **Googleの本人確認＝自宅（非公開）／公開表記＝VO**、と分離。

### 他OSの「提供元不明」
- Windows: SmartScreen。**OV/IVコード署名証明書（個人可・年~€189〜$230）**で発行元表示＋実績で評判蓄積。EVは法人限定＆SmartScreen優遇なし。2026/2以降 証明書は1年更新。
- macOS: Gatekeeper。**Apple Developer Program $99/年 → 署名＋ノータライズ**で警告消える。
- iOS: 最も閉鎖的。App Store/TestFlight必須、野良は実質不可（EUのみDMAで代替）。
- Linux: ほぼ自由。

### 戦略
- サイドロード摩擦は「摩擦を厭わない＝ストア非依存/プライバシー志向」層をふるい分ける。現アプリ（脱Google・ローカル完結）はこの層と理想的に合致。**「Google圏外で完結」を売りに**。
- 身元確認は1回の固定費 → **1つの検証済み身元の下に同系統の製品ラインを束ねる**ほど割に合う（暗号メモ/ローカル金庫/オフライン書類スキャナ等の横展開候補）。
- 専用Googleアカウント分離運用は可。最大リスクは**復旧/ロックアウト（単一障害点）**→ 復旧情報・2FA・バックアップコードを堅牢に。署名鍵とGitHubはGoogleアカウントと独立。
- 個人事業主はGoogleの「個人（実名）」区分を変えない（法人化＝organization＋D-U-N-S）。税務/銀行/屋号表示に有用。

## 6. 既知の制約
- 暗号化バックアップ復元は**カスタムマスクを持ち越さない**（原本のみ搬送 → 復元後は全面モザイク）。
- **自動更新なし**（INTERNET権限なしの設計）。更新は再DL＋上書きインストール（同一鍵＋versionCode増分）。
- **Lite移行書き出しは平文**（マスク前原本を含む）＝お試しの割り切り。

## 7. すぐ手伝える未着手タスク（依頼があれば）
- 特商法表記＋プライバシーポリシーの正式化（`docs/`保存）
- 配信ZIP実体の作成（APK＋インストール手順書を同梱）
- ADC用 proof APK のビルド（あなたがADCで発行されたスニペット取得後）
- ポジショニング/商品説明コピー、ニッチ検証、想定問答の正式化（Pro完成後）

## 8. 新セッションでの再開手順
1. 同じリポジトリ／ブランチ `claude/android-camera-pii-masking-io6ucl` で新セッション開始。
2. 最初の指示: 「`android/docs/PROJECT-STATUS.md` を読んで続きから」。
3. 必要に応じ最新Release/CI状況を確認（GitHub MCP は `asap0000/homepage` スコープ）。
