package com.privacycamera.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, passphrase-based encryption for the encrypted-backup ("ferry") container.
 *
 * This is deliberately separate from [CryptoManager]: that key lives in the
 * AndroidKeyStore and is bound to a single app+device, so a backup encrypted with it
 * could never be opened by the Pro app (a different applicationId == a different
 * keystore). Backups instead derive their key from a user passphrase via PBKDF2, so the
 * same file can be exported by Lite and imported by Pro on the same — or another — device.
 *
 * Container layout (a single file):
 *   MAGIC (4 bytes, "PCB1") | salt (16) | iv (12) | AES-256-GCM ciphertext (incl. tag)
 * The header (magic|salt|iv) is authenticated as GCM AAD so tampering is detected.
 */
object BackupCrypto {

    val MAGIC = "PCB1".toByteArray(Charsets.US_ASCII)
    const val SALT_SIZE = 16
    const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Derives a 256-bit AES key from [passphrase] and [salt] using PBKDF2-HMAC-SHA256. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Cipher ready to encrypt; its freshly generated [Cipher.getIV] becomes the header iv. */
    fun newEncryptCipher(key: SecretKey, salt: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(salt, cipher.iv))
        return cipher
    }

    /** Cipher ready to decrypt the payload that followed [iv] in the container. */
    fun newDecryptCipher(key: SecretKey, salt: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(salt, iv))
        return cipher
    }

    private fun aad(salt: ByteArray, iv: ByteArray): ByteArray = MAGIC + salt + iv
}
