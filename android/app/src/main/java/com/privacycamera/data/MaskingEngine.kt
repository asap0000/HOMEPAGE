package com.privacycamera.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Produces the masked rendition of a photo.
 *
 * Per the product decision, content is NOT inspected: every photo taken with this app
 * is treated as sensitive, so the whole frame is mosaicked. The mask is a heavy
 * pixelation (mosaic) only — coarse enough that text like a licence number is
 * unreadable, but the rough shapes/colours remain so the user can still recognise
 * the photo at a glance (helped further by the caption/category metadata).
 */
object MaskingEngine {

    /** Lower = blockier. 24 means the image is reduced to ~24px wide before upscaling. */
    private const val PIXELATION_COLUMNS = 24

    fun mask(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        // Pixelate by downscaling then upscaling without filtering.
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

        return output
    }
}
