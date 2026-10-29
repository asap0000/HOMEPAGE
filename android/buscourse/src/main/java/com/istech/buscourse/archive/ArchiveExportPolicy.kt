package com.istech.buscourse.archive

import com.istech.buscourse.recording.RecordingConfigRepository

object ArchiveExportPolicy {
    /**
     * 書き出しが端末に作る物（run.json・目録・指紋表・DONE）の見積もり。写真を写さなくなった（2026-10-29 委託指示書 M）ので、
     * 空きの確かめには走行の大きさではなくこの小さな定数を使う。
     */
    const val EXPORT_RESERVE_BYTES: Long = 8L * 1024 * 1024

    fun hasSpace(usableBytes: Long, runBytes: Long): Boolean =
        runBytes >= 0 && usableBytes >= RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES &&
            runBytes <= usableBytes - RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES
}
