package com.privacycamera.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Environment
import android.provider.MediaStore
import com.privacycamera.crypto.CryptoManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** A single stored photo: the masked preview plus its user-added metadata. */
data class PhotoItem(
    val id: String,
    val maskedFile: File,
    val createdAt: Long,
    val caption: String = "",
    val category: String = PhotoCategories.UNCLASSIFIED
)

/**
 * Owns all on-device persistence.
 *
 * Layout (all under the app-private internal storage, which media scanners and cloud
 * photo apps like Google Photos / Amazon Photos cannot see or upload):
 *   filesDir/secure/originals/<id>.enc   -> AES-GCM encrypted full-resolution JPEG
 *   filesDir/secure/masked/<id>.jpg      -> masked (mosaic) preview, safe to display
 *   filesDir/secure/meta/<id>.json       -> { caption, category, createdAt }
 */
class SecurePhotoStore(private val context: Context) {

    private val baseDir = File(context.filesDir, "secure").apply { mkdirs() }
    private val originalsDir = File(baseDir, "originals").apply { mkdirs() }
    private val maskedDir = File(baseDir, "masked").apply { mkdirs() }
    private val metaDir = File(baseDir, "meta").apply { mkdirs() }

    init {
        // Defence in depth: even though internal storage is never media-scanned,
        // drop a .nomedia marker so nothing here is ever indexed.
        File(baseDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    /**
     * Persists a freshly captured JPEG: encrypts the original, writes a masked preview,
     * and stores initial metadata. [jpegBytes] should already be upright.
     */
    fun save(
        jpegBytes: ByteArray,
        caption: String = "",
        category: String = PhotoCategories.UNCLASSIFIED
    ): PhotoItem {
        val id = "IMG_${System.currentTimeMillis()}"
        val createdAt = System.currentTimeMillis()

        // Encrypt and store the original.
        File(originalsDir, "$id.enc").writeBytes(CryptoManager.encrypt(jpegBytes))

        // Build and store the masked (mosaic) preview.
        val source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val masked = MaskingEngine.mask(source)
        val maskedFile = File(maskedDir, "$id.jpg")
        maskedFile.outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        source.recycle()
        masked.recycle()

        writeMeta(id, caption, category, createdAt)

        return PhotoItem(id, maskedFile, createdAt, caption, category)
    }

    /** Lists stored photos, newest first, with metadata applied. */
    fun list(): List<PhotoItem> {
        return maskedDir.listFiles { f -> f.extension == "jpg" }
            ?.map { f ->
                val id = f.nameWithoutExtension
                val meta = readMeta(id)
                PhotoItem(
                    id = id,
                    maskedFile = f,
                    createdAt = meta?.optLong("createdAt", f.lastModified()) ?: f.lastModified(),
                    caption = meta?.optString("caption", "") ?: "",
                    category = meta?.optString("category", PhotoCategories.UNCLASSIFIED)
                        ?: PhotoCategories.UNCLASSIFIED
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** Updates the caption/category for a photo, preserving its original timestamp. */
    fun updateMeta(id: String, caption: String, category: String) {
        val createdAt = readMeta(id)?.optLong("createdAt", System.currentTimeMillis())
            ?: System.currentTimeMillis()
        writeMeta(id, caption, category, createdAt)
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
        File(metaDir, "$id.json").delete()
    }

    /**
     * Exports ONLY the masked preview to the shared gallery (Pictures/PrivacyCamera).
     * The original is never exported.
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

    private fun readMeta(id: String): JSONObject? {
        val f = File(metaDir, "$id.json")
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(id: String, caption: String, category: String, createdAt: Long) {
        val json = JSONObject().apply {
            put("caption", caption)
            put("category", category)
            put("createdAt", createdAt)
        }
        File(metaDir, "$id.json").writeText(json.toString())
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
