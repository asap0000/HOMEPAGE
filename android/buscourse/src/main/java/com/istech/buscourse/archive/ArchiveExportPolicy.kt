package com.istech.buscourse.archive

import com.istech.buscourse.recording.RecordingConfigRepository

object ArchiveExportPolicy {
    fun hasSpace(usableBytes: Long, runBytes: Long): Boolean =
        runBytes >= 0 && usableBytes >= RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES &&
            runBytes <= usableBytes - RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES
}
