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

/**
 * A single stored photo: the masked preview plus its user-added metadata.
 *
 * [id] is the on-device storage key (also the file name) and may differ between
 * devices/installs. [uuid] is a stable, install-independent identity minted once at
 * capture time; it travels inside encrypted exports so that imports can de-duplicate
 * and (later) accumulate batches across multiple backups without resurrecting or
 * double-importing the same photo. Always key cross-app/cross-backup logic on [uuid],
 * never on [id].
 */
data class PhotoItem(
    val id: String,
    val uuid: String,
    val maskedFile: File,
    val createdAt: Long,
    val caption: String = "",
    val category: String = PhotoCategories.UNCLASSIFIED,
    /** When this photo was moved to the trash, or 0 if it is a live (non-trashed) photo. */
    val deletedAt: Long = 0L
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
    // Soft-deleted photos live here for TRASH_TTL_MILLIS so an accidental delete can be
    // undone. Recovery is intentionally the trash's job, separate from backups.
    private val trashDir = File(baseDir, "trash").apply { mkdirs() }
    private val trashOriginalsDir = File(trashDir, "originals").apply { mkdirs() }
    private val trashMaskedDir = File(trashDir, "masked").apply { mkdirs() }
    private val trashMetaDir = File(trashDir, "meta").apply { mkdirs() }
    private val categoriesFile = File(baseDir, "categories.json")
    // Detail access log: encrypted, append-only JSON array of recent AccessEntry rows.
    private val accessLogFile = File(baseDir, "access_log.enc")
    // Pre-encryption format (plaintext JSON). Only read once, to migrate into accessLogFile.
    private val legacyAccessLogFile = File(baseDir, "access_log.json")
    // Entries older than the retention window are rolled up here, one gzip+encrypted JSON
    // array per calendar month, and removed from accessLogFile. See compactAccessLogIfNeeded.
    private val logArchiveDir = File(baseDir, "log_archive").apply { mkdirs() }
    private val importedUuidsFile = File(baseDir, "migration_imported.json")
    // Persistent tombstones: uuids the user deleted ON THIS install. A backup restore skips
    // these so it never resurrects something deleted here — even after the 30-day trash
    // window has purged the photo. A different device starts with an empty set, so a backup
    // restored there DOES bring the photo back (that device never knew about the deletion).
    private val deletedUuidsFile = File(baseDir, "deleted_uuids.json")

    init {
        // Defence in depth: even though internal storage is never media-scanned,
        // drop a .nomedia marker so nothing here is ever indexed.
        File(baseDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    /**
     * Persists a freshly captured JPEG: mints a new uuid, encrypts the original, writes a
     * masked preview, and stores initial metadata. [jpegBytes] should already be upright.
     */
    fun save(
        jpegBytes: ByteArray,
        caption: String = "",
        category: String = PhotoCategories.UNCLASSIFIED
    ): PhotoItem = persist(
        jpegBytes,
        uuid = java.util.UUID.randomUUID().toString(),
        createdAt = System.currentTimeMillis(),
        caption = caption,
        category = category
    )

    /**
     * Imports an image (from a Lite migration archive or a general image pick) under a
     * given [uuid] and metadata. Same on-device protection as a capture: the bytes become
     * the encrypted original and a masked preview is generated. Throws if the bytes are
     * not a decodable image so the caller can skip it.
     */
    fun importOriginal(
        jpegBytes: ByteArray,
        uuid: String,
        createdAt: Long,
        caption: String,
        category: String
    ): PhotoItem = persist(jpegBytes, uuid, createdAt, caption, category)

    private fun persist(
        jpegBytes: ByteArray,
        uuid: String,
        createdAt: Long,
        caption: String,
        category: String
    ): PhotoItem {
        // Decode first so a non-image input fails before anything is written to disk.
        val source = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalArgumentException("Not a decodable image")
        val id = newId()

        File(originalsDir, "$id.enc").writeBytes(CryptoManager.encrypt(jpegBytes))

        val masked = MaskingEngine.mask(source)
        val maskedFile = File(maskedDir, "$id.jpg")
        maskedFile.outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        source.recycle()
        masked.recycle()

        writeMeta(id, uuid, caption, category, createdAt)

        return PhotoItem(id, uuid, maskedFile, createdAt, caption, category)
    }

    /** Returns a storage id (file-name key) not already in use. */
    private fun newId(): String {
        var id = "IMG_${System.currentTimeMillis()}"
        var n = 1
        while (File(originalsDir, "$id.enc").exists() || File(maskedDir, "$id.jpg").exists()) {
            id = "IMG_${System.currentTimeMillis()}_$n"
            n++
        }
        return id
    }

    /** Lists stored photos, newest first, with metadata applied. */
    fun list(): List<PhotoItem> {
        return maskedDir.listFiles { f -> f.extension == "jpg" }
            ?.map { f ->
                val id = f.nameWithoutExtension
                val meta = readMeta(id)
                val createdAt =
                    meta?.optLong("createdAt", f.lastModified()) ?: f.lastModified()
                val caption = meta?.optString("caption", "") ?: ""
                val category = meta?.optString("category", PhotoCategories.UNCLASSIFIED)
                    ?: PhotoCategories.UNCLASSIFIED
                PhotoItem(
                    id = id,
                    uuid = ensureUuid(id, meta, caption, category, createdAt),
                    maskedFile = f,
                    createdAt = createdAt,
                    caption = caption,
                    category = category
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** Updates the caption/category for a photo, preserving its original timestamp and uuid. */
    fun updateMeta(id: String, caption: String, category: String) {
        val meta = readMeta(id)
        val createdAt = meta?.optLong("createdAt", System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val uuid = ensureUuid(id, meta, caption, category, createdAt)
        writeMeta(id, uuid, caption, category, createdAt)
    }

    /**
     * Replaces the original of an existing photo with [jpegBytes] (e.g. after editing):
     * re-encrypts the original and regenerates the masked preview. Because cropping etc.
     * changes the geometry, any saved custom mask spec is dropped and the preview falls
     * back to the safe whole-frame mosaic (over-masks rather than under-masks).
     */
    fun replaceOriginal(id: String, jpegBytes: ByteArray) {
        File(originalsDir, "$id.enc").writeBytes(CryptoManager.encrypt(jpegBytes))
        val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val masked = MaskingEngine.mask(src)
        File(maskedDir, "$id.jpg").outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        src.recycle()
        masked.recycle()
        readMeta(id)?.let { meta ->
            if (meta.has("mask")) {
                meta.remove("mask")
                File(metaDir, "$id.json").writeText(meta.toString())
            }
        }
    }

    /** Decrypts and decodes the original (unmasked) image. App-only reveal. */
    fun decryptOriginal(id: String): Bitmap? {
        val enc = File(originalsDir, "$id.enc")
        if (!enc.exists()) return null
        val plain = CryptoManager.decrypt(enc.readBytes())
        return BitmapFactory.decodeByteArray(plain, 0, plain.size)
    }

    /** True if the encrypted original for [id] is present on disk. */
    fun hasOriginal(id: String): Boolean = File(originalsDir, "$id.enc").exists()

    /** The custom mask spec for [id], or the whole-frame default if none was saved. */
    fun loadMaskSpec(id: String): MaskingEngine.MaskSpec {
        val mask = readMeta(id)?.optJSONObject("mask") ?: return MaskingEngine.MaskSpec()
        return MaskingEngine.specFromJson(mask)
    }

    /**
     * Pro mask editing: regenerates the masked preview from the (untouched) encrypted
     * original according to [spec] and persists the spec so it can be re-edited. Returns
     * false if the original is unavailable.
     */
    fun applyMask(id: String, spec: MaskingEngine.MaskSpec): Boolean {
        val original = decryptOriginal(id) ?: return false
        val masked = MaskingEngine.render(original, spec)
        File(maskedDir, "$id.jpg").outputStream().use { out ->
            masked.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        original.recycle()
        masked.recycle()
        val meta = readMeta(id) ?: JSONObject()
        meta.put("mask", MaskingEngine.specToJson(spec))
        File(metaDir, "$id.json").writeText(meta.toString())
        return true
    }

    /** Decrypts the original (unmasked) JPEG bytes — used when building an encrypted backup. */
    fun decryptOriginalBytes(id: String): ByteArray? {
        val enc = File(originalsDir, "$id.enc")
        if (!enc.exists()) return null
        return CryptoManager.decrypt(enc.readBytes())
    }

    /**
     * Soft-deletes a photo: moves it to the trash and stamps it with the deletion time.
     * It stays recoverable until [TRASH_TTL_MILLIS] elapses (see [purgeExpiredTrash]).
     */
    fun delete(id: String) {
        val deletedAt = System.currentTimeMillis()
        move(File(originalsDir, "$id.enc"), File(trashOriginalsDir, "$id.enc"))
        move(File(maskedDir, "$id.jpg"), File(trashMaskedDir, "$id.jpg"))
        val meta = readMeta(id) ?: JSONObject()
        // Tombstone the uuid so a later backup restore won't resurrect this photo here.
        addDeletedUuid(meta.optString("uuid", ""))
        meta.put("deletedAt", deletedAt)
        File(trashMetaDir, "$id.json").writeText(meta.toString())
        File(metaDir, "$id.json").delete()
    }

    /** Lists trashed photos, most-recently-deleted first. */
    fun listTrash(): List<PhotoItem> {
        return trashMaskedDir.listFiles { f -> f.extension == "jpg" }
            ?.map { f ->
                val id = f.nameWithoutExtension
                val meta = readTrashMeta(id)
                PhotoItem(
                    id = id,
                    uuid = meta?.optString("uuid", "") ?: "",
                    maskedFile = f,
                    createdAt = meta?.optLong("createdAt", f.lastModified()) ?: f.lastModified(),
                    caption = meta?.optString("caption", "") ?: "",
                    category = meta?.optString("category", PhotoCategories.UNCLASSIFIED)
                        ?: PhotoCategories.UNCLASSIFIED,
                    deletedAt = meta?.optLong("deletedAt", f.lastModified()) ?: f.lastModified()
                )
            }
            ?.sortedByDescending { it.deletedAt }
            ?: emptyList()
    }

    /** Restores a trashed photo back to the live library, clearing its deletion stamp. */
    fun restore(id: String) {
        move(File(trashOriginalsDir, "$id.enc"), File(originalsDir, "$id.enc"))
        move(File(trashMaskedDir, "$id.jpg"), File(maskedDir, "$id.jpg"))
        val meta = readTrashMeta(id) ?: JSONObject()
        // The photo is back in the library, so lift its tombstone.
        removeDeletedUuid(meta.optString("uuid", ""))
        meta.remove("deletedAt")
        File(metaDir, "$id.json").writeText(meta.toString())
        File(trashMetaDir, "$id.json").delete()
    }

    /** Permanently removes a single trashed photo. */
    fun purge(id: String) {
        File(trashOriginalsDir, "$id.enc").delete()
        File(trashMaskedDir, "$id.jpg").delete()
        File(trashMetaDir, "$id.json").delete()
    }

    /** Permanently removes every trashed photo. */
    fun emptyTrash() {
        trashMaskedDir.listFiles { f -> f.extension == "jpg" }
            ?.forEach { purge(it.nameWithoutExtension) }
    }

    /** Permanently removes trashed photos older than [TRASH_TTL_MILLIS]. */
    fun purgeExpiredTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_TTL_MILLIS
        trashMaskedDir.listFiles { f -> f.extension == "jpg" }?.forEach { f ->
            val id = f.nameWithoutExtension
            val deletedAt = readTrashMeta(id)?.optLong("deletedAt", 0L) ?: 0L
            if (deletedAt in 1L until cutoff) purge(id)
        }
    }

    private fun readTrashMeta(id: String): JSONObject? {
        val f = File(trashMetaDir, "$id.json")
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Moves [from] to [to], falling back to copy+delete across filesystem boundaries. */
    private fun move(from: File, to: File) {
        if (!from.exists()) return
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
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

    /**
     * UUIDs ever imported through the Lite-migration path. This is the basis for the
     * lifetime import cap: the set only grows, so deleting an imported photo never frees a
     * slot (which would otherwise allow a delete -> re-import loop past the cap).
     */
    fun loadImportedUuids(): Set<String> {
        if (!importedUuidsFile.exists()) return emptySet()
        return try {
            val arr = JSONArray(importedUuidsFile.readText())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** Records [uuids] as imported-via-migration (union with the existing set). */
    fun addImportedUuids(uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        val merged = loadImportedUuids().toMutableSet().apply { addAll(uuids) }
        val arr = JSONArray()
        merged.forEach { arr.put(it) }
        importedUuidsFile.writeText(arr.toString())
    }

    /** UUIDs the user has deleted on this install (tombstones); see [deletedUuidsFile]. */
    fun loadDeletedUuids(): Set<String> {
        if (!deletedUuidsFile.exists()) return emptySet()
        return try {
            val arr = JSONArray(deletedUuidsFile.readText())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun writeDeletedUuids(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        deletedUuidsFile.writeText(arr.toString())
    }

    /** Marks [uuid] as deleted-on-this-device so a backup restore won't resurrect it. */
    private fun addDeletedUuid(uuid: String) {
        if (uuid.isEmpty()) return
        val set = loadDeletedUuids()
        if (uuid in set) return
        writeDeletedUuids(set + uuid)
    }

    /** Clears [uuid]'s tombstone (the photo is back), e.g. when restored from the trash. */
    private fun removeDeletedUuid(uuid: String) {
        if (uuid.isEmpty()) return
        val set = loadDeletedUuids()
        if (uuid !in set) return
        writeDeletedUuids(set - uuid)
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

    /**
     * Appends an access-log entry. The log is encrypted at rest (AES-256-GCM via
     * [CryptoManager], same as photo originals) and never leaves the device. A photo-less
     * event (file-level import/export, setting change, log management) uses an empty
     * [photoId].
     */
    fun logAccess(photoId: String, action: String, caption: String) {
        val arr = readAccessLogArray()
        arr.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("a", action)
                .put("id", photoId)
                .put("c", caption)
        )
        writeAccessLogArray(arr)
    }

    /** Loads the (uncompacted) detail access log, newest first. */
    fun loadAccessLog(): List<AccessEntry> = jsonArrayToEntries(readAccessLogArray()).sortedByDescending { it.timestamp }

    /**
     * Permanently erases all log history (detail entries AND monthly archives). Returns the
     * number of entries erased, so the caller can record a [AccessActions.LOG_DELETE]
     * tombstone stating how much was removed — the log system audits its own erasure, so a
     * wipe is never itself invisible.
     */
    fun clearAccessLog(): Int {
        val detailCount = readAccessLogArray().length()
        val archivedCount = listArchivedMonths().sumOf { it.total }
        accessLogFile.delete()
        logArchiveDir.listFiles()?.forEach { it.delete() }
        return detailCount + archivedCount
    }

    /**
     * Rolls detail-log entries older than the retention window into per-month, gzip+encrypted
     * archives, removing them from the detail log. Retention keeps whichever is LARGER of:
     * entries from the last [DETAIL_RETENTION_DAYS] days, or the most recent
     * [DETAIL_MIN_COUNT] entries overall (so light usage across many years still keeps a
     * useful amount of raw detail). Safe to call repeatedly (e.g. on every app start); a
     * no-op run compacts nothing. Returns the list of calendar months ("yyyy-MM") that were
     * rolled up in this call, so the caller can record one [AccessActions.LOG_COMPACT] entry
     * per month — compaction reorganizes data, it never discards it.
     */
    fun compactAccessLogIfNeeded(): List<String> {
        val all = jsonArrayToEntries(readAccessLogArray()).sortedByDescending { it.timestamp }
        if (all.isEmpty()) return emptyList()

        val cutoff = System.currentTimeMillis() - DETAIL_RETENTION_DAYS * 24 * 60 * 60 * 1000
        // Split by index (not a set difference) so entries that are structurally identical
        // (same millisecond/action/photo/caption) can never collide and vanish.
        val retained = mutableListOf<AccessEntry>()
        val aged = mutableListOf<AccessEntry>()
        all.forEachIndexed { index, entry ->
            if (index < DETAIL_MIN_COUNT || entry.timestamp >= cutoff) retained.add(entry) else aged.add(entry)
        }
        if (aged.isEmpty()) return emptyList()

        val byMonth = aged.groupBy { monthKey(it.timestamp) }
        for ((month, entries) in byMonth) {
            val existing = readArchiveMonth(month)
            writeArchiveMonth(month, existing + entries)
        }

        val retainedArr = JSONArray()
        retained.sortedBy { it.timestamp }.forEach { retainedArr.put(entryToJson(it)) }
        writeAccessLogArray(retainedArr)

        return byMonth.keys.sorted()
    }

    /** Lists archived months (newest first) with per-action-code counts, for a history UI. */
    fun listArchivedMonths(): List<ArchivedMonth> {
        val months = logArchiveDir.listFiles { f -> f.name.endsWith(".json.gz.enc") }
            ?.map { it.name.removeSuffix(".json.gz.enc") }
            ?: emptyList()
        return months.sortedDescending().mapNotNull { month ->
            val entries = readArchiveMonth(month)
            if (entries.isEmpty()) return@mapNotNull null
            val counts = entries.groupingBy { it.action }.eachCount()
            ArchivedMonth(month, counts, entries.size)
        }
    }

    /** Fully decompresses/decrypts one archived month's entries, newest first (UI expansion). */
    fun loadArchivedMonthEntries(month: String): List<AccessEntry> =
        readArchiveMonth(month).sortedByDescending { it.timestamp }

    // ---- access-log internals ----

    private fun entryToJson(e: AccessEntry): JSONObject =
        JSONObject().put("t", e.timestamp).put("a", e.action).put("id", e.photoId).put("c", e.caption)

    private fun jsonArrayToEntries(arr: JSONArray): List<AccessEntry> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AccessEntry(
                timestamp = o.optLong("t"),
                action = o.optString("a"),
                photoId = o.optString("id"),
                caption = o.optString("c")
            )
        }

    /** Reads the encrypted detail log, transparently migrating a pre-existing plaintext file. */
    private fun readAccessLogArray(): JSONArray {
        if (!accessLogFile.exists() && legacyAccessLogFile.exists()) {
            val migrated = try {
                JSONArray(legacyAccessLogFile.readText())
            } catch (e: Exception) {
                JSONArray()
            }
            writeAccessLogArray(migrated)
            legacyAccessLogFile.delete()
        }
        if (!accessLogFile.exists()) return JSONArray()
        return try {
            JSONArray(String(CryptoManager.decrypt(accessLogFile.readBytes()), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeAccessLogArray(arr: JSONArray) {
        accessLogFile.writeBytes(CryptoManager.encrypt(arr.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun readArchiveMonth(month: String): List<AccessEntry> {
        val f = File(logArchiveDir, "$month.json.gz.enc")
        if (!f.exists()) return emptyList()
        return try {
            val gzipped = CryptoManager.decrypt(f.readBytes())
            val json = java.util.zip.GZIPInputStream(gzipped.inputStream()).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            jsonArrayToEntries(JSONArray(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeArchiveMonth(month: String, entries: List<AccessEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.timestamp }.forEach { arr.put(entryToJson(it)) }
        val plain = arr.toString().toByteArray(Charsets.UTF_8)
        val gzipOut = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gzipOut).use { it.write(plain) }
        File(logArchiveDir, "$month.json.gz.enc").writeBytes(CryptoManager.encrypt(gzipOut.toByteArray()))
    }

    /** "yyyy-MM" key (device-local calendar) for grouping entries into monthly archives. */
    private fun monthKey(timestampMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestampMillis
        return "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
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

    /**
     * Returns the photo's stable [PhotoItem.uuid], minting and persisting one for legacy
     * photos that pre-date uuid tracking. Once assigned, a photo's uuid never changes, so
     * exports/imports can rely on it for de-duplication.
     */
    private fun ensureUuid(
        id: String,
        meta: JSONObject?,
        caption: String,
        category: String,
        createdAt: Long
    ): String {
        val existing = meta?.optString("uuid", "")?.takeIf { it.isNotEmpty() }
        if (existing != null) return existing
        val minted = java.util.UUID.randomUUID().toString()
        writeMeta(id, minted, caption, category, createdAt)
        return minted
    }

    private fun writeMeta(
        id: String,
        uuid: String,
        caption: String,
        category: String,
        createdAt: Long
    ) {
        // Start from any existing metadata so extra keys (e.g. a custom mask spec) survive
        // a caption/category edit.
        val json = (readMeta(id) ?: JSONObject()).apply {
            put("uuid", uuid)
            put("caption", caption)
            put("category", category)
            put("createdAt", createdAt)
        }
        File(metaDir, "$id.json").writeText(json.toString())
    }

    companion object {
        // Detail access-log retention: keep whichever is larger of "last N days" or "last N
        // entries" (see compactAccessLogIfNeeded), then roll the rest into monthly archives.
        private const val DETAIL_RETENTION_DAYS = 365L
        private const val DETAIL_MIN_COUNT = 1000

        /** How long a soft-deleted photo is kept in the trash before being purged. */
        const val TRASH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days

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
