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

    // Kept generic: this app now covers more than identity documents (bank passbooks,
    // statements, etc.), so the wording doesn't assume "本人確認書類".
    const val PURPOSE_TEXT = "提出用複製・目的外利用不可"

    /** Returns a NEW bitmap; [source] is never mutated. */
    fun apply(source: Bitmap, destination: String, dateText: String): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val text = "$PURPOSE_TEXT　提出先: $destination　$dateText"

        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(120, 220, 30, 30)
            // Smaller than before (was 0.032): a shorter, smaller line is both less
            // intrusive and less likely to have its tail (the date) cut at a tile edge —
            // every repeat is now more likely to render fully within the image.
            textSize = max(out.width, out.height) * 0.020f
        }

        // Tile the text across a square at least as large as the bitmap's diagonal, then
        // rotate the whole canvas, so the watermark fully covers the image (including
        // corners) regardless of rotation or aspect ratio.
        val diagonal = hypot(out.width.toDouble(), out.height.toDouble()).toFloat()
        val textWidth = paint.measureText(text).coerceAtLeast(1f)
        val colStep = textWidth + paint.textSize * 2f
        // Row spacing is intentionally NOT tied to the (now smaller) text size: it stays
        // fixed at the previous, larger scale so the gap between lines looks the same as
        // before — only the characters themselves got smaller.
        val rowStep = max(out.width, out.height) * 0.032f * 3f

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
