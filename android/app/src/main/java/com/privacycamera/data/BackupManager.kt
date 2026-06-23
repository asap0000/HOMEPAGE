package com.privacycamera.data

import com.privacycamera.crypto.BackupCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.CipherOutputStream

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
    ) {
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
            )
        }
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("count", items.size)
            .put("photos", photos)
    }
}
