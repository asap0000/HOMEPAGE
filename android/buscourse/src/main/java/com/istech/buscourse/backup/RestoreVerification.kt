package com.istech.buscourse.backup

/**
 * 展開結果とmanifestの突き合わせ（設計ドラフト§8-5「復元後に照合する」・タスク指示書§3
 * 「照合は展開しながら行う。manifestの値・fileCount・totalBytesと突き合わせる」）。
 *
 * ★[BackupManifest]にはZIP全体のSHA-256を含めない（同クラスKDoc参照＝ZIP自身の中に
 * 自己参照的に埋め込めないため、書き出し側の実装時点で意図的に外された項目）。したがって
 * 復元側が自動で突き合わせられる相手は、manifestが実際に持つ2値―― `fileCount` と
 * `totalBytes` ――に限られる（**自分で決めた点**）。ZIPのSHA-256自体は
 * [RestoreImporter]が展開しながら計算し完了画面に参考値として表示するに留め、
 * 自動照合の合否ゲートにはしない。
 */
object RestoreVerification {
    fun matches(
        manifestFileCount: Int,
        manifestTotalBytes: Long,
        extractedFileCount: Int,
        extractedTotalBytes: Long,
    ): Boolean =
        manifestFileCount == extractedFileCount && manifestTotalBytes == extractedTotalBytes
}
