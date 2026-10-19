package com.istech.buscourse.storage

import android.content.Context
import com.istech.buscourse.backup.BackupFileInfo
import com.istech.buscourse.backup.BackupFileRegistry
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.StorageSettings
import com.istech.buscourse.recording.StorageWarningPolicy

/** いまの空き容量（バイト）。[sdFreeBytes] は microSD が挿さっていなければ null。 */
data class StorageFacts(val bodyFreeBytes: Long, val bodyTotalBytes: Long, val sdFreeBytes: Long?) {
    companion object {
        /** ディスクを見るので、メインスレッドでは呼ばない。 */
        fun read(context: Context): StorageFacts = StorageFacts(
            bodyFreeBytes = context.filesDir.usableSpace,
            bodyTotalBytes = context.filesDir.totalSpace,
            sdFreeBytes = BusCourseStorage.sdFilesDirFresh(context)?.usableSpace,
        )
    }
}

/**
 * 空き容量の窓（トップの「設計」「データ出力」を押したとき）に出す中身（2026-10-20 委託指示書 K）。
 * [backups] は、この端末で作った全量バックアップのうち、まだ在るものだけ。
 */
data class StorageNotice(
    val check: StorageWarningPolicy.Check,
    val facts: StorageFacts,
    val backups: List<BackupFileInfo>,
) {
    companion object {
        /**
         * 窓が要るときだけ [StorageNotice] を返し、足りているときは null（窓は出さない）。
         * ディスクと保存先への問い合わせを含むので、メインスレッドでは呼ばない。
         */
        fun load(context: Context): StorageNotice? {
            val facts = StorageFacts.read(context)
            val check = StorageWarningPolicy.check(
                bodyFreeBytes = facts.bodyFreeBytes,
                bodyTotalBytes = facts.bodyTotalBytes,
                sdFreeBytes = facts.sdFreeBytes,
                deviceWarningLineBytes = StorageWarningPolicy.gbToBytes(StorageSettings(context).deviceWarningLineGb),
            )
            if (!check.shouldWarn) return null
            return StorageNotice(check, facts, BackupFileRegistry(context).listExisting())
        }
    }
}
