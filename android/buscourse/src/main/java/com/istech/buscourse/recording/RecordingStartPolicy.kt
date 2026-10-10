package com.istech.buscourse.recording

object RecordingStartPolicy {
    fun canStart(usableBytes: Long): Boolean = usableBytes >= RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES
}
