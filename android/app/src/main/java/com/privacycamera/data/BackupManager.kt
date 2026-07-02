package com.privacycamera.data

import com.privacycamera.crypto.BackupCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.CipherOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds the encrypted-backup ("ferry") container that carries photos out of one install
 * and into another (notably Lite -> Pro). The container is encrypted with a passphrase
 * (see [BackupCrypto]) so it is portable across apps/devices, unlike the per-app
 * AndroidKeyStore originals.
 *
 * Encrypted payload (inside the AES-GCM stream), length-prefixed so it can be read back
 * without loading everything into memory at once:
 *   int32 manifestLen | manifest JSON (UTF-8) | for each photo: int32 jpegLen | jpeg bytes
 *
 * The manifest carries the per-photo metadata in the same order the JPEG blobs follow,
 * including each photo's stable [PhotoItem.uuid] so the importer can de-duplicate and
 * enforce its import cap.
 */
object BackupManager {

    const val FORMAT_VERSION = 1

    /** Manifest entry name inside a plaintext migration ZIP. */
    const val MANIFEST_NAME = "manifest.json"

    /**
     * Streams a PLAINTEXT migration archive of [items] (whose originals still exist) to
     * [out] as a ZIP: a [MANIFEST_NAME] entry plus one "<uuid>.jpg" entry per photo holding
     * the decrypted original. This is Lite's "limited" export — the originals leave the
     * device unprotected, which the user knowingly accepts to migrate trial photos. The
     * manifest carries each photo's stable uuid so the Pro importer can de-duplicate and
     * enforce its lifetime migration cap.
     */
    fun exportPlainZip(
        out: OutputStream,
        items: List<PhotoItem>,
        store: SecurePhotoStore
    ): Int {
        val exportable = items.filter { store.hasOriginal(it.id) }
        var written = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val manifest = buildManifest(exportable).toString().toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest)
            zip.closeEntry()
            for (item in exportable) {
                val bytes = store.decryptOriginalBytes(item.id) ?: continue
                zip.putNextEntry(ZipEntry("${item.uuid}.jpg"))
                zip.write(bytes)
                zip.closeEntry()
                written++
            }
        }
        return written
    }

    /**
     * Streams an encrypted backup of [items] (whose originals still exist) to [out],
     * reading and encrypting one original at a time. [passphrase] is used only to derive
     * the key and is not retained here; the caller owns wiping it.
     */
    fun export(
        out: OutputStream,
        items: List<PhotoItem>,
        store: SecurePhotoStore,
        passphrase: CharArray
    ): Int {
        val exportable = items.filter { store.hasOriginal(it.id) }

        val salt = ByteArray(BackupCrypto.SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = BackupCrypto.deriveKey(passphrase, salt)
        val cipher = BackupCrypto.newEncryptCipher(key, salt)

        // Plaintext header: format magic + KDF salt + GCM iv.
        out.write(BackupCrypto.MAGIC)
        out.write(salt)
        out.write(cipher.iv)
        out.flush()

        // Closing this DataOutputStream flushes and closes the CipherOutputStream exactly
        // once, which is what writes the trailing GCM tag (cipher.doFinal). Nesting two
        // separate .use blocks on the cipher stream would call doFinal twice and throw.
        val cipherOut = CipherOutputStream(out, cipher)
        DataOutputStream(BufferedOutputStream(cipherOut)).use { dos ->
            val manifest = buildManifest(exportable).toString().toByteArray(Charsets.UTF_8)
            dos.writeInt(manifest.size)
            dos.write(manifest)
            for (item in exportable) {
                val bytes = store.decryptOriginalBytes(item.id) ?: ByteArray(0)
                dos.writeInt(bytes.size)
                dos.write(bytes)
            }
        }
        return exportable.size
    }

    /** Outcome of importing a Lite-migration archive. */
    data class MigrationImportResult(
        val imported: Int,
        val skippedDuplicate: Int,
        val skippedOverCap: Int,
        // Beta diagnostics: pinpoint where an import that yields nothing actually broke
        // (empty/unreadable ZIP vs. manifest-without-images vs. matching/cap issue).
        val entriesSeen: Int = 0,
        val imageEntries: Int = 0,
        val manifestPhotos: Int = 0
    )

    private data class Incoming(
        val uuid: String,
        val createdAt: Long,
        val caption: String,
        val category: String
    )

    /**
     * Imports a plaintext migration ZIP (as produced by [exportPlainZip]) into [store],
     * subject to the lifetime migration [cap]: photos whose uuid was already imported are
     * skipped (de-dup), and once the cap is reached the rest are skipped as over-cap. The
     * caller passes the tier cap (e.g. Tier.LITE_SAVE_LIMIT).
     *
     * The manifest supplies per-photo metadata (caption/category/timestamp); when it is
     * absent or an image entry isn't listed in it, the image is still imported using its
     * "<uuid>.jpg" file name as the identity, so a manifest problem never silently drops
     * the whole import.
     */
    fun importMigrationZip(
        input: InputStream,
        store: SecurePhotoStore,
        cap: Int
    ): MigrationImportResult {
        val seen = store.loadImportedUuids().toMutableSet()
        var slots = cap - seen.size
        var imported = 0
        var duplicate = 0
        var overCap = 0
        var entriesSeen = 0
        var imageEntries = 0
        val newlyImported = mutableListOf<String>()
        val metaByFile = HashMap<String, Incoming>()

        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entriesSeen++
                val name = entry.name
                val bytes = zis.readBytes()
                if (name == MANIFEST_NAME) {
                    parseManifest(bytes, metaByFile)
                } else if (bytes.isNotEmpty()) {
                    imageEntries++
                    // Prefer the manifest's metadata, but fall back to the entry's own file
                    // name (which is "<uuid>.jpg") so images are still imported even if the
                    // manifest is missing, unreadable, or its entries don't line up. Without
                    // this fallback a single manifest hiccup silently imports nothing.
                    val meta = metaByFile[name] ?: Incoming(
                        uuid = name.substringBeforeLast('.', ""),
                        createdAt = System.currentTimeMillis(),
                        caption = "",
                        category = PhotoCategories.UNCLASSIFIED
                    )
                    when {
                        meta.uuid.isEmpty() -> { /* no usable identity — skip */ }
                        meta.uuid in seen -> duplicate++
                        slots <= 0 -> overCap++
                        else -> {
                            try {
                                store.importOriginal(
                                    bytes, meta.uuid, meta.createdAt, meta.caption, meta.category
                                )
                                seen.add(meta.uuid)
                                newlyImported.add(meta.uuid)
                                imported++
                                slots--
                            } catch (e: Exception) {
                                // Skip an undecodable / corrupt entry without aborting.
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        store.addImportedUuids(newlyImported)
        return MigrationImportResult(
            imported, duplicate, overCap,
            entriesSeen = entriesSeen,
            imageEntries = imageEntries,
            manifestPhotos = metaByFile.size
        )
    }

    /** Outcome of restoring an encrypted backup. */
    sealed class RestoreOutcome {
        data class Success(val imported: Int, val skipped: Int) : RestoreOutcome()

        /** Header was missing/unrecognized — the file is not one of our backups. */
        object NotABackup : RestoreOutcome()

        /** Wrong passphrase or a corrupt/tampered file (decryption produced garbage). */
        object WrongPassphraseOrCorrupt : RestoreOutcome()
    }

    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    private const val MAX_BLOB_BYTES = 256 * 1024 * 1024

    /**
     * Restores an encrypted backup (as produced by [export]) into [store], decrypting with
     * [passphrase]. Photos whose uuid is already present ([existingUuids], which should
     * include trashed photos so a restore doesn't resurrect something the user deleted) are
     * skipped; the rest are re-encrypted into the local store with their metadata.
     *
     * The caller owns wiping [passphrase] afterwards.
     */
    fun importEncrypted(
        input: InputStream,
        store: SecurePhotoStore,
        passphrase: CharArray,
        existingUuids: Set<String>
    ): RestoreOutcome {
        val header = ByteArray(BackupCrypto.MAGIC.size + BackupCrypto.SALT_SIZE + BackupCrypto.IV_SIZE)
        if (!readFully(input, header)) return RestoreOutcome.NotABackup
        if (!header.copyOfRange(0, BackupCrypto.MAGIC.size).contentEquals(BackupCrypto.MAGIC)) {
            return RestoreOutcome.NotABackup
        }
        val salt = header.copyOfRange(BackupCrypto.MAGIC.size, BackupCrypto.MAGIC.size + BackupCrypto.SALT_SIZE)
        val iv = header.copyOfRange(BackupCrypto.MAGIC.size + BackupCrypto.SALT_SIZE, header.size)

        val key = BackupCrypto.deriveKey(passphrase, salt)
        val cipher = BackupCrypto.newDecryptCipher(key, salt, iv)

        // Decrypt the whole payload in ONE doFinal instead of streaming through a
        // CipherInputStream. AES-GCM is an AEAD cipher: the authentication tag covers the
        // entire ciphertext and can only be verified once all of it has been processed, and
        // CipherInputStream is documented to mis-handle GCM for multi-block inputs (it can
        // fail or silently corrupt on larger files — which is exactly the reported symptom:
        // small backups restored, an 18-photo backup failed as "wrong passphrase/corrupt").
        // A successful doFinal both decrypts and authenticates, so it doubles as the
        // passphrase/integrity gate. The file format is unchanged, so existing backups
        // written by [export] restore correctly.
        val plain = try {
            cipher.doFinal(input.readBytes())
        } catch (e: Exception) {
            return RestoreOutcome.WrongPassphraseOrCorrupt
        }

        var imported = 0
        var skipped = 0
        val newlyImported = HashSet<String>()
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { din ->
                val manifestLen = din.readInt()
                if (manifestLen !in 1..MAX_MANIFEST_BYTES) return RestoreOutcome.WrongPassphraseOrCorrupt
                val manifestBytes = ByteArray(manifestLen)
                din.readFully(manifestBytes)
                val photos = JSONObject(String(manifestBytes, Charsets.UTF_8)).optJSONArray("photos")
                    ?: return RestoreOutcome.WrongPassphraseOrCorrupt

                for (i in 0 until photos.length()) {
                    val o = photos.getJSONObject(i)
                    val jpegLen = din.readInt()
                    if (jpegLen < 0 || jpegLen > MAX_BLOB_BYTES) break
                    val jpeg = ByteArray(jpegLen)
                    din.readFully(jpeg)

                    val uuid = o.optString("uuid")
                    if (uuid.isEmpty() || uuid in existingUuids || uuid in newlyImported) {
                        skipped++
                        continue
                    }
                    try {
                        store.importOriginal(
                            jpeg,
                            uuid,
                            o.optLong("createdAt", System.currentTimeMillis()),
                            o.optString("caption", ""),
                            o.optString("category", PhotoCategories.UNCLASSIFIED)
                        )
                        imported++
                        newlyImported.add(uuid)
                    } catch (e: Exception) {
                        skipped++ // undecodable blob — skip without aborting the restore
                    }
                }
            }
        } catch (e: Exception) {
            // The payload is authentic (doFinal passed) but its structure was unexpected;
            // report whatever we managed to restore rather than failing outright.
        }
        return RestoreOutcome.Success(imported, skipped)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    private fun parseManifest(bytes: ByteArray, into: HashMap<String, Incoming>) {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val photos = root.optJSONArray("photos") ?: return
        for (i in 0 until photos.length()) {
            val o = photos.getJSONObject(i)
            val uuid = o.optString("uuid")
            if (uuid.isEmpty()) continue
            val file = o.optString("file").ifEmpty { "$uuid.jpg" }
            into[file] = Incoming(
                uuid = uuid,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                caption = o.optString("caption", ""),
                category = o.optString("category", PhotoCategories.UNCLASSIFIED)
            )
        }
    }

    private fun buildManifest(items: List<PhotoItem>): JSONObject {
        val photos = JSONArray()
        items.forEach { item ->
            photos.put(
                JSONObject()
                    .put("uuid", item.uuid)
                    .put("createdAt", item.createdAt)
                    .put("caption", item.caption)
                    .put("category", item.category)
                    // Used by the ZIP (plaintext) importer to locate the blob; the encrypted
                    // importer ignores this and reads blobs in manifest order instead.
                    .put("file", "${item.uuid}.jpg")
            )
        }
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("count", items.size)
            .put("photos", photos)
    }
}
