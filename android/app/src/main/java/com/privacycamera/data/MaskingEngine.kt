package com.privacycamera.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Produces the masked rendition of a photo.
 *
 * Lite (and the capture-time default) treats every photo as sensitive and mosaicks the
 * whole frame — content is not inspected. Pro can refine this with [MaskSpec]: choose the
 * style (mosaic / blur / solid), the strength, and which rectangular regions to cover
 * (or keep the whole frame). The original is never altered; only the masked preview is
 * regenerated, so masking can always be redone from the untouched original.
 */
object MaskingEngine {

    /** Default mosaic coarseness: the image is reduced to ~this many columns before upscaling. */
    const val DEFAULT_COLUMNS = 24

    enum class MaskStyle { MOSAIC, BLUR, SOLID }

    /** A rectangular region to mask, in normalized image coordinates (0..1). */
    data class MaskRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * How a photo should be masked. [wholeFrame] (or an empty [regions] list) masks the
     * entire image; otherwise only [regions] are masked. [columns] is the mosaic/blur
     * coarseness (lower = stronger).
     */
    data class MaskSpec(
        val wholeFrame: Boolean = true,
        val style: MaskStyle = MaskStyle.MOSAIC,
        val columns: Int = DEFAULT_COLUMNS,
        val regions: List<MaskRegion> = emptyList()
    )

    /** The capture-time / Lite default: heavy whole-frame mosaic. */
    fun mask(source: Bitmap): Bitmap = render(source, MaskSpec())

    /** Renders the masked bitmap for [source] according to [spec]. */
    fun render(source: Bitmap, spec: MaskSpec): Bitmap {
        val w = source.width
        val h = source.height

        if (spec.wholeFrame || spec.regions.isEmpty()) {
            val full = Rect(0, 0, w, h)
            if (spec.style == MaskStyle.SOLID) {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(out).drawColor(Color.BLACK)
                return out
            }
            return scaleRegion(source, full, spec.columns, filter = spec.style == MaskStyle.BLUR)
        }

        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (region in spec.regions) {
            val rect = toPixelRect(region, w, h) ?: continue
            applyToRegion(source, canvas, rect, spec.style, spec.columns)
        }
        return out
    }

    private fun applyToRegion(
        source: Bitmap,
        canvas: Canvas,
        rect: Rect,
        style: MaskStyle,
        columns: Int
    ) {
        if (style == MaskStyle.SOLID) {
            canvas.drawRect(rect, solidPaint)
            return
        }
        val patch = scaleRegion(source, rect, columns, filter = style == MaskStyle.BLUR)
        canvas.drawBitmap(patch, rect.left.toFloat(), rect.top.toFloat(), copyPaint)
        patch.recycle()
    }

    /**
     * Downscales the [rect] sub-image to [columns] wide then upscales it back. With
     * [filter] = false the result is crisp mosaic blocks; with true it is a soft blur.
     */
    private fun scaleRegion(source: Bitmap, rect: Rect, columns: Int, filter: Boolean): Bitmap {
        val sub = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        val scale = (columns.toFloat() / sub.width).coerceIn(0.01f, 1f)
        val smallW = (sub.width * scale).toInt().coerceAtLeast(1)
        val smallH = (sub.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(sub, smallW, smallH, filter)
        val big = Bitmap.createScaledBitmap(small, sub.width, sub.height, filter)
        if (sub != small) sub.recycle()
        if (small != big) small.recycle()
        return big
    }

    private fun toPixelRect(r: MaskRegion, w: Int, h: Int): Rect? {
        val left = (r.left.coerceIn(0f, 1f) * w).toInt()
        val top = (r.top.coerceIn(0f, 1f) * h).toInt()
        val right = (r.right.coerceIn(0f, 1f) * w).toInt()
        val bottom = (r.bottom.coerceIn(0f, 1f) * h).toInt()
        val rect = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
        if (rect.width() < 1 || rect.height() < 1) return null
        return rect
    }

    private val solidPaint = Paint().apply { color = Color.BLACK }
    private val copyPaint = Paint().apply { isFilterBitmap = false }

    // ---- (de)serialization for persistence in photo metadata ----

    fun specToJson(spec: MaskSpec): JSONObject {
        val regions = JSONArray()
        spec.regions.forEach { r ->
            regions.put(
                JSONObject()
                    .put("l", r.left.toDouble())
                    .put("t", r.top.toDouble())
                    .put("r", r.right.toDouble())
                    .put("b", r.bottom.toDouble())
            )
        }
        return JSONObject()
            .put("wholeFrame", spec.wholeFrame)
            .put("style", spec.style.name)
            .put("columns", spec.columns)
            .put("regions", regions)
    }

    fun specFromJson(obj: JSONObject): MaskSpec {
        val style = runCatching { MaskStyle.valueOf(obj.optString("style", MaskStyle.MOSAIC.name)) }
            .getOrDefault(MaskStyle.MOSAIC)
        val regionsJson = obj.optJSONArray("regions") ?: JSONArray()
        val regions = (0 until regionsJson.length()).map { i ->
            val o = regionsJson.getJSONObject(i)
            MaskRegion(
                o.optDouble("l", 0.0).toFloat(),
                o.optDouble("t", 0.0).toFloat(),
                o.optDouble("r", 1.0).toFloat(),
                o.optDouble("b", 1.0).toFloat()
            )
        }
        return MaskSpec(
            wholeFrame = obj.optBoolean("wholeFrame", true),
            style = style,
            columns = obj.optInt("columns", DEFAULT_COLUMNS),
            regions = regions
        )
    }
}
