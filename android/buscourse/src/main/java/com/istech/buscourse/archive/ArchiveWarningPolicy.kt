package com.istech.buscourse.archive

/** 初期画面に保管庫への退避案内を出す条件。 */
fun shouldShowArchiveWarning(
    nonProtectedBytes: Long,
    quotaBytes: Long,
    hasUnreceivedRuns: Boolean,
): Boolean = nonProtectedBytes > quotaBytes && hasUnreceivedRuns
