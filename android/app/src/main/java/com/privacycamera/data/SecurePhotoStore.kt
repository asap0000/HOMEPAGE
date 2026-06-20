package com.privacycamera.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Environment
import android.provider.MediaStore
import com.privacycamera.crypto.CryptoManager
import java.io.ByteArrayOutputStream
import java.io.File

/** A single stored photo: id plus the on-disk masked preview file. */
data class PhotoItem(
    val id: String,
    val maskedFile: File,
    val createdAt: Long
)

/**
 * Owns all on-device persistence.
 *
 * Layout (all under the app-private internal storage, which media scanners and cloud
 * photo apps like Google Photos / Amazon Photos cannot see or upload):
 *   filesDir/secure/originals/<id>.enc   -> AES-GCM encrypted full-resolution JPEG
 *   filesDir/secure/masked/<id>.jpg      -> masked preview (safe to display anywhere)
 */
class SecurePhotoStore(private val context: Context) {

    private val baseDir = File(context.filesDir, "secure").apply { mkdirs() }
    private val originalsDir = File(baseDir, "originals").apply { mkdirs() }
    private val maskedDir = File(baseDir, "masked").apply { mkdirs() }

    init {
        // Defence in depth: even though internal storage is never media-scanned,
        // drop a .nomedia marker so nothing here is ever indexed.
        File(baseDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    /**
     * Persists a freshly captured JPEG: encrypts the original and writes a masked preview.
     * [jpegBytes] should already be correctly rotated/upright.
     */
    fun save(jpegBytes: ByteArray): PhotoItem {
        val id = "IMG_${System.currentTimeMillis()}"

        // Encrypt and store the original.
        File(originalsDir, "$id.enc").writeBytes(CryptoManager.encrypt(jpegBytes))

        // Build and store the masked preview.
        val source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val masked = MaskingEngine.mask(source)
        val maskedFile = File(maskedDir, "$id.jpg")
        maskedFile.outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        source.recycle()
        masked.recycle()

        return PhotoItem(id = id, maskedFile = maskedFile, createdAt = System.currentTimeMillis())
    }

    /** Lists stored photos, newest first. */
    fun list(): List<PhotoItem> {
        return maskedDir.listFiles { f -> f.extension == "jpg" }
            ?.map { f ->
                val id = f.nameWithoutExtension
                PhotoItem(id = id, maskedFile = f, createdAt = f.lastModified())
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** Decrypts and decodes the original (unmasked) image. App-only reveal. */
    fun decryptOriginal(id: String): Bitmap? {
        val enc = File(originalsDir, "$id.enc")
        if (!enc.exists()) return null
        val plain = CryptoManager.decrypt(enc.readBytes())
        return BitmapFactory.decodeByteArray(plain, 0, plain.size)
    }

    fun delete(id: String) {
        File(originalsDir, "$id.enc").delete()
        File(maskedDir, "$id.jpg").delete()
    }

    /**
     * Exports ONLY the masked preview to the shared gallery (Pictures/PrivacyCamera).
     * This is the deliberately-safe copy: even if Google/Amazon Photos upload it,
     * only the masked image leaves the device. The original is never exported.
     */
    fun exportMaskedToGallery(item: PhotoItem): Boolean {
        // Scoped-storage MediaStore writes (no permission needed) require API 29+.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${item.id}_masked.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/PrivacyCamera"
            )
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(item.maskedFile.readBytes())
            }
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    companion object {
        /** Rotates the given JPEG bytes by [rotationDegrees] and re-encodes as JPEG. */
        fun rotateJpeg(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray {
            if (rotationDegrees == 0) return jpegBytes
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return jpegBytes
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            if (rotated != bmp) bmp.recycle()
            rotated.recycle()
            return out.toByteArray()
        }
    }
}
