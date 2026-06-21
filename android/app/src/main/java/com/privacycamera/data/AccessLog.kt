package com.privacycamera.data

/** A single access-log entry recording an action performed on a stored photo. */
data class AccessEntry(
    val timestamp: Long,
    val action: String,
    val photoId: String,
    val caption: String
)

/** Action codes used in the access log, with human-readable Japanese labels. */
object AccessActions {
    const val OPEN = "OPEN"       // viewer opened (masked view)
    const val REVEAL = "REVEAL"   // original decrypted & shown
    const val EXPORT = "EXPORT"   // masked copy exported to gallery
    const val DELETE = "DELETE"   // photo deleted

    fun label(code: String): String = when (code) {
        OPEN -> "閲覧（マスク）"
        REVEAL -> "正規表示（復号）"
        EXPORT -> "マスク版を書き出し"
        DELETE -> "削除"
        else -> code
    }
}
