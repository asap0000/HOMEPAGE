# istech サーベイランス巡回

istech 標準環境(homepage + istech 両リポジトリ)の定期点検を実施する。
事案が見つかったら報告し、安全に修正できるものは修正する。異常が無ければ「異常なし」と簡潔に報告する。

## 前提チェック

1. `.istech/CLAUDE.md` が存在するか確認する。無ければ `git submodule update --init` で取得する。
   取得できない場合、istech 方針が読めていない旨を報告し、方針判断を伴う修正は行わない。

## 巡回手順

1. **CI の健全性**: GitHub MCP の `actions_list` で `asap0000/homepage` の `android.yml` と `release.yml` の最新 run を確認する
   (結果が巨大な場合は保存ファイルに対して `jq` で `id / head_sha / status / conclusion` のみ抽出する)。
   main の最新 run が failure なら `list_workflow_jobs` で失敗ステップを特定し、`get_job_logs` で原因を調査する。
2. **新規変更の把握**: `git fetch origin` で homepage の新規コミット・新規ブランチを確認する。
   istech リポジトリ(サブモジュール先 `asap0000/istech`)も同様に確認する。
3. **方針整合の簡易監査**(新規コミットがある場合):
   - `AndroidManifest.xml` に `INTERNET` パーミッションが追加されていないか
     (機器連動のないアプリでは禁止。詳細は `.istech/app-android/CLAUDE.md`)
   - `build.gradle.kts` にネットワーク系ライブラリ(okhttp / retrofit / ktor / volley / grpc 等)が追加されていないか
   - CLAUDE.md 三層(root / app-android / app-windows)に矛盾する変更が入っていないか
   - keystore・署名・Secrets 参照まわりの変更が既存フロー(GPG 暗号化 keystore + GitHub Secrets、PKCS12 のため
     keyPassword = RELEASE_STORE_PASSWORD)と整合しているか
4. **submodule 追従**: istech の main が homepage の `.istech` ポインタより進んでいる場合、
   `git submodule update --remote .istech` による bump が必要な旨を報告する(方針変更の内容も要約する)。

## 修正の方針

- 原因が明確で影響範囲が限定的な修正(CI 設定の破損、検査ステップの不備、ドキュメント整合など)は、
  作業ブランチにコミット・プッシュしてよい。
- **main への反映(マージ)はユーザーの明示的な指示がある場合のみ**行う。無ければ修正内容を報告して指示を待つ。
- リリース・配布・署名鍵・Secrets に関わる変更は、必ず事前にユーザーへ報告し確認を取る。

## 過去の既知事案(再発時の参考)

- 2026-06〜07: 署名 keystore の再生成後、main に旧 `release.keystore.gpg` が残り
  「gpg: Bad session key」で CI が失敗し続けた(push が無い期間は発覚しなかった)。
  対策として `android.yml` に週次スケジュール実行を追加済み。
- PKCS12 keystore はストアと鍵が単一パスワード。`RELEASE_KEY_PASSWORD` Secret は参照しない
  (署名設定は `RELEASE_STORE_PASSWORD` を鍵パスワードにも使用する)。
