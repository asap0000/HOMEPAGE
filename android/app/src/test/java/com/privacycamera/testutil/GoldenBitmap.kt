package com.privacycamera.testutil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream

/**
 * Bitmap 入出力ロジック(MaskingEngine 等)用の最小ゴールデン画像アサーション。
 *
 * - 通常実行: コミット済みの基準 PNG とピクセル一致を検証する(不一致は差分ピクセル数を報告)
 * - 記録モード(`-PupdateGoldens=true` → system property `updateGoldens=true`):
 *   基準 PNG を書き出して終了する(record-goldens ワークフローが使う)
 * - ブートストラップ: 基準ディレクトリ自体が未記録の場合は skip 扱いにして、
 *   初回記録前の CI を赤にしない。ディレクトリがあるのに個別ファイルが無い場合は失敗させる。
 *
 * Compose 画面のスクリーンショット比較は Roborazzi 側(src/test/screenshots)を使う。
 * こちらは純粋な Bitmap 処理専用(src/test/goldens)。
 */
object GoldenBitmap {

    private const val DIR = "src/test/goldens"
    private val update: Boolean
        get() = System.getProperty("updateGoldens") == "true"

    fun assertMatches(actual: Bitmap, name: String) {
        val file = File("$DIR/$name.png")
        if (update) {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        assumeTrue(
            "golden directory not recorded yet — run the record-goldens workflow first",
            File(DIR).exists()
        )
        check(file.exists()) { "golden missing: ${file.path} — run the record-goldens workflow" }
        val golden = BitmapFactory.decodeFile(file.path)
            ?: error("golden unreadable: ${file.path}")
        check(golden.width == actual.width && golden.height == actual.height) {
            "$name: size mismatch — golden=${golden.width}x${golden.height}, " +
                "actual=${actual.width}x${actual.height}"
        }
        var diff = 0
        for (y in 0 until golden.height) {
            for (x in 0 until golden.width) {
                if (golden.getPixel(x, y) != actual.getPixel(x, y)) diff++
            }
        }
        check(diff == 0) {
            "$name: $diff pixels differ from golden — if the change is intended, " +
                "re-run the record-goldens workflow to update the baseline"
        }
    }
}
