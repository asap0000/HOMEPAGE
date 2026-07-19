package com.istech.buscourse.core.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlin.math.max

/**
 * EXIF `Orientation` タグを尊重したビットマップデコード（2026-07-10追加）。
 *
 * CameraXの`ImageCapture`は端末の向きに応じてJPEGにEXIF回転タグを書き込むだけでピクセル自体は
 * 回転させない（仕様通り）。しかし `BitmapFactory.decodeFile` はEXIFを一切見ないため、素朴に
 * デコードすると実車テストで見つかった「サムネイル・プレビューが90度回転する」不具合になる
 * （原本JPEG自体は無傷。PC・ギャラリー等EXIF対応ビューアでは正しい向きで表示される）。
 * `android.media.ExifInterface`はAndroid SDK標準（API 5〜）で、新規Gradle依存の追加は不要。
 */
object ExifAwareBitmap {

    /**
     * [path] のJPEGを、必要なら [maxLongEdgePx] 程度まで間引き読みしつつ、EXIF回転を
     * ピクセルに焼き込んだ状態でデコードする。デコードできなければ null。
     */
    fun decode(path: String, maxLongEdgePx: Int? = null): Bitmap? {
        val sample = if (maxLongEdgePx != null) computeSampleSize(path, maxLongEdgePx) else 1
        val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val matrix = orientationMatrix(readOrientation(path))
        if (matrix.isIdentity) return raw
        return try {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } finally {
            // createBitmapが新規インスタンスを返した場合のみ元を解放する（同一参照時の二重解放防止）
        }.also { rotated -> if (rotated !== raw) raw.recycle() }
    }

    private fun computeSampleSize(path: String, maxLongEdgePx: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdgePx) sample *= 2
        return sample
    }

    private fun readOrientation(path: String): Int = try {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            // NORMAL・UNDEFINED 等はそのまま（恒等行列）
        }
    }
}
