package com.privacycamera.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Environment
import android.provider.MediaStore
import com.privacycamera.crypto.CryptoManager
import org.json.JSONArray
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
    private val categoriesFile = File(baseDir, "categories.json")
    private val accessLogFile = File(baseDir, "access_log.json")

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

    /** User-defined categories (persisted across sessions). */
    fun loadCustomCategories(): List<String> {
        if (!categoriesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(categoriesFile.readText())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds a new custom category if it is non-blank and not already known. */
    fun addCustomCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || PhotoCategories.isBuiltIn(trimmed)) return
        val current = loadCustomCategories().toMutableList()
        if (trimmed in current) return
        current.add(trimmed)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        categoriesFile.writeText(arr.toString())
    }

    /** Appends an access-log entry (kept in the secure area, never leaves the device). */
    fun logAccess(photoId: String, action: String, caption: String) {
        val arr = try {
            if (accessLogFile.exists()) JSONArray(accessLogFile.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
        arr.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("a", action)
                .put("id", photoId)
                .put("c", caption)
        )
        // Keep only the most recent MAX_LOG_ENTRIES.
        val start = maxOf(0, arr.length() - MAX_LOG_ENTRIES)
        val trimmed = JSONArray()
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        accessLogFile.writeText(trimmed.toString())
    }

    /** Loads the access log, newest first. */
    fun loadAccessLog(): List<AccessEntry> {
        if (!accessLogFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(accessLogFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AccessEntry(
                    timestamp = o.optLong("t"),
                    action = o.optString("a"),
                    photoId = o.optString("id"),
                    caption = o.optString("c")
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAccessLog() {
        accessLogFile.delete()
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
        private const val MAX_LOG_ENTRIES = 1000

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
