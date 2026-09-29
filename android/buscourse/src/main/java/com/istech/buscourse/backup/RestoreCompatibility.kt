package com.istech.buscourse.backup

/**
 * 復元の可否ゲート（タスク指示書§2「オーナー承認済みの振る舞い（復唱3行）」・§3「判定と手順の具体」）。
 * いずれもAndroid非依存の純関数（整数の比較のみ）。
 */
object RestoreCompatibility {

    fun isSchemaAcceptable(backupDbSchemaVersion: Int, appDbSchemaVersion: Int, migrationEdges: List<Pair<Int, Int>>): Boolean {
        if (backupDbSchemaVersion == appDbSchemaVersion) return true
        val reachable = mutableSetOf(backupDbSchemaVersion)
        var changed: Boolean
        do {
            changed = false
            migrationEdges.forEach { (from, to) ->
                if (from in reachable && reachable.add(to)) changed = true
            }
        } while (changed)
        return appDbSchemaVersion in reachable
    }

    fun requiredFreeBytes(totalBytes: Long, reserveBytes: Long = 256L * 1024 * 1024): Long {
        val total = totalBytes.coerceAtLeast(0L)
        val reserve = reserveBytes.coerceAtLeast(0L)
        return if (total > Long.MAX_VALUE - reserve) Long.MAX_VALUE else total + reserve
    }

    data class StorageCheck(val requiredBytes: Long, val availableBytes: Long) {
        val enough: Boolean get() = availableBytes >= requiredBytes
        val shortageBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0L)
    }

    fun checkStorage(totalBytes: Long, availableBytes: Long): StorageCheck =
        StorageCheck(requiredFreeBytes(totalBytes), availableBytes.coerceAtLeast(0L))

    fun storageMessage(check: StorageCheck): String {
        fun gb(bytes: Long) = String.format(java.util.Locale.JAPAN, "%.1f", bytes.toDouble() / (1024.0 * 1024 * 1024))
        return "空きが ${gb(check.shortageBytes)} GB 足りません（必要 ${gb(check.requiredBytes)} GB・空き ${gb(check.availableBytes)} GB）。不要なファイルを消してから、もう一度お試しください。"
    }

    fun restoreFailureMessage(afterDatabaseClosed: Boolean, rollbackSucceeded: Boolean): String = when {
        !afterDatabaseClosed -> "端末の状態は変更されていません。もう一度お試しください。"
        rollbackSucceeded -> "途中で止まりましたが、書き戻す前の状態に戻しました。アプリを終了して開き直してから、もう一度お試しください。"
        else -> "途中で止まり、データが半端な状態です。設定からこのアプリのデータを消去してから、全量バックアップの ZIP でもう一度戻してください。"
    }

    const val CORRUPT_ZIP_MESSAGE = "ファイルが途中で切れているか、壊れています。別の ZIP を選んでください。"

    /** Accepts equal versions or versions connected by registered migration edges. */
    /**
     * 「データなし」の判定（タスク指示書§3）。主要テーブル（course・recording_session・
     * bus_stop_card）が全て空であることを指す。DBファイルの有無では判定しない――初回起動でRoomが
     * 空DBを作るため、ファイルの有無で判定するとすぐ偽になる（タスク指示書§3の注記）。
     */
    fun isDeviceEmpty(courseCount: Int, recordingSessionCount: Int, busStopCardCount: Int): Boolean =
        courseCount == 0 && recordingSessionCount == 0 && busStopCardCount == 0
}
