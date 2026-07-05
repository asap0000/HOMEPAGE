package com.privacycamera.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max

/**
 * Burns a repeating, semi-transparent diagonal watermark over a "submission copy" bitmap:
 * the output purpose, the destination the user typed, and the output date. This is the ONE
 * gate that stands between the decrypted original and anything leaving the device via print
 * (docs/2026-07-04_仕様_提出用出力機能.md §3.3) — there must be no code path that hands a
 * bitmap to [com.privacycamera.print.SubmissionPrinter] without going through [apply] first.
 */
object WatermarkRenderer {

    const val PURPOSE_TEXT = "本人確認書類の提出用複製・目的外利用不可"

    /** Returns a NEW bitmap; [source] is never mutated. */
    fun apply(source: Bitmap, destination: String, dateText: String): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val text = "$PURPOSE_TEXT　提出先: $destination　$dateText"

        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(120, 220, 30, 30)
            textSize = max(out.width, out.height) * 0.032f
        }

        // Tile the text across a square at least as large as the bitmap's diagonal, then
        // rotate the whole canvas, so the watermark fully covers the image (including
        // corners) regardless of rotation or aspect ratio.
        val diagonal = hypot(out.width.toDouble(), out.height.toDouble()).toFloat()
        val textWidth = paint.measureText(text).coerceAtLeast(1f)
        val colStep = textWidth + paint.textSize * 2f
        val rowStep = paint.textSize * 3f

        canvas.save()
        canvas.rotate(-30f, out.width / 2f, out.height / 2f)
        var y = -diagonal
        while (y < diagonal) {
            var x = -diagonal
            while (x < diagonal) {
                canvas.drawText(text, x, y, paint)
                x += colStep
            }
            y += rowStep
        }
        canvas.restore()
        return out
    }
}
