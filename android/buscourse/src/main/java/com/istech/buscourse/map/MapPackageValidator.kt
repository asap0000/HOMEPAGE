package com.istech.buscourse.map

import java.io.File
import java.security.MessageDigest

/**
 * `manifest.json`のschemaVersion検証と、展開済み`region.mbtiles`/`style.json`のSHA-256照合を担う
 * （設計書§5.6.3手順2・4、§5.6.4 `MapPackageValidator`）。
 *
 * グリフ（PBF）はfontstack×256コードポイント単位で数百ファイルに及ぶため、1件ずつのSHA-256照合は
 * 行わない（依頼の指示どおり）。`ZipInputStream`自体がエントリ読み出し終端でCRC-32を検証し、
 * 不一致時は`ZipException`を送出するため（[MapPackageImporter]の展開処理参照）、グリフの完全性検証は
 * そちらに委ねる。
 */
object MapPackageValidator {

    /** schemaVersionが本インポータの対応範囲内かを検証する（現状は完全一致のみ許容）。 */
    fun validateSchemaVersion(pkg: MapDataPackage) {
        if (pkg.schemaVersion != MapDataPackage.SUPPORTED_SCHEMA_VERSION) {
            throw MapPackageValidationException(
                "対応していないschemaVersionです（対応: ${MapDataPackage.SUPPORTED_SCHEMA_VERSION}, " +
                    "実際: ${pkg.schemaVersion}）"
            )
        }
    }

    /** [file]のSHA-256が[expectedHex]（大文字小文字を区別しない16進文字列）と一致するか検証する。 */
    fun verifySha256(file: File, expectedHex: String, label: String) {
        if (!file.exists()) {
            throw MapPackageValidationException("$label が展開先に見つかりません: ${file.path}")
        }
        val actual = sha256Hex(file)
        if (!actual.equals(expectedHex, ignoreCase = true)) {
            throw MapPackageValidationException(
                "$label のSHA-256が一致しません（manifest記載: $expectedHex, 実ファイル: $actual）"
            )
        }
    }

    /** ファイルのSHA-256を16進文字列（小文字）で返す。ストリーミング計算のため大きなmbtilesでも全体をメモリに載せない。 */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(SHA256_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val SHA256_BUFFER_SIZE = 8192
}
