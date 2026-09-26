# android/ — BusCourse

| 場所 | 中身 |
|---|---|
| `buscourse/` | BusCourse アプリ本体（Kotlin・Jetpack Compose・Room・MapLibre） |
| `ci/offline-allowlist.yml` | ネットワーク系ライブラリの許可リスト（CI が検査） |
| `tools/test-buscourse.sh` | ユニットテストを回して生の数字を出す |

ビルドの種類（`buscourse/build.gradle.kts`）:

- `debug` … 開発用（`.debug` 付き・実データを置かない）
- `field` … 記録用の実機に入れていた版（役目は終了予定。移し替えが済むまで残す）
- `kensa` … 検査場用（`.kensa` 付き）
- `release` … 配布用。CI の Secrets にある配布鍵でだけ署名される（PC では未署名になる）
