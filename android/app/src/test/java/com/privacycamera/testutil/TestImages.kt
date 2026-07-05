package com.privacycamera.testutil

import android.graphics.Bitmap
import android.graphics.Color

/** マスキング/ウォーターマークのテストで使う決定的な合成画像(乱数・I/Oなし)。 */
object TestImages {

    /**
     * 横方向に色相が変化するグラデーション+白の対角線。
     * 対角線が入っているため、モザイク化・領域マスクの効果がピクセル比較で確実に検出できる。
     */
    fun gradient(width: Int = 96, height: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = x * 255 / (width - 1)
                val g = y * 255 / (height - 1)
                val b = (x + y) * 255 / (width + height - 2)
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        for (i in 0 until minOf(width, height)) {
            bmp.setPixel(i, i, Color.WHITE)
        }
        return bmp
    }

    /** 画像内の異なり色数(ダウンサンプル効果の測定用)。 */
    fun distinctColors(bmp: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                seen.add(bmp.getPixel(x, y))
            }
        }
        return seen.size
    }
}
