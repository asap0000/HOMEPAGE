package com.privacycamera.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Produces the masked rendition of a photo.
 *
 * Per the product decision, content is NOT inspected: every photo taken with this app
 * is treated as sensitive, so the whole frame is masked. The mask is a heavy
 * pixelation plus a dark veil and a lock badge, making the masked file safe to show
 * outside the app while the encrypted original remains the only source of truth.
 */
object MaskingEngine {

    /** Lower = blockier. 24 means the image is reduced to ~24px wide before upscaling. */
    private const val PIXELATION_COLUMNS = 24

    fun mask(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        // 1) Pixelate by downscaling then upscaling without filtering.
        val scale = (PIXELATION_COLUMNS.toFloat() / width).coerceIn(0.005f, 1f)
        val smallW = (width * scale).toInt().coerceAtLeast(1)
        val smallH = (height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, false)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val blockPaint = Paint().apply { isFilterBitmap = false }
        canvas.drawBitmap(
            small,
            Rect(0, 0, smallW, smallH),
            Rect(0, 0, width, height),
            blockPaint
        )
        if (small != source) small.recycle()

        // 2) Dark veil to suppress any residual legibility.
        canvas.drawColor(Color.argb(140, 0, 0, 0))

        // 3) Lock badge in the centre.
        drawLockBadge(canvas, width, height)

        return output
    }

    private fun drawLockBadge(canvas: Canvas, width: Int, height: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val unit = (minOf(width, height) * 0.12f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        // Lock body
        val bodyLeft = cx - unit * 0.6f
        val bodyTop = cy - unit * 0.1f
        val bodyRight = cx + unit * 0.6f
        val bodyBottom = cy + unit * 0.8f
        canvas.drawRoundRect(
            RectF(bodyLeft, bodyTop, bodyRight, bodyBottom),
            unit * 0.15f, unit * 0.15f, fill
        )

        // Shackle
        val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.18f
        }
        val shackleRect = RectF(cx - unit * 0.38f, cy - unit * 0.6f, cx + unit * 0.38f, cy + unit * 0.1f)
        canvas.drawArc(shackleRect, 180f, 180f, false, shackle)

        // Keyhole
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B132B") }
        canvas.drawCircle(cx, cy + unit * 0.25f, unit * 0.13f, hole)
    }
}
