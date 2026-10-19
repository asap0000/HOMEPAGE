package com.istech.buscourse.recording

/** 新しい運行をどこへ記録するか（[RecordingDestinationPolicy.decide] の結果）。 */
enum class RecordingDestination {
    /** microSD が挿さっている。選ぶ画面は出さず、自動で microSD に書く。 */
    SD_CARD,

    /** microSD を使ったことのない端末。今までどおり本体へ（窓は出さない）。 */
    BODY,

    /** microSD を使ったことがあるのに今は抜けている。記録の開始前に「本体に記録するか」を聞く。 */
    ASK_BODY,
}

/**
 * 記録の開始時の置き場所の判断（2026-10-20 委託指示書 K・純関数）。
 * 本体に記録した運行は、あとから microSD へは移さない（窓の注記にも書く）。
 */
object RecordingDestinationPolicy {
    fun decide(sdMounted: Boolean, usedSdBefore: Boolean): RecordingDestination = when {
        sdMounted -> RecordingDestination.SD_CARD
        usedSdBefore -> RecordingDestination.ASK_BODY
        else -> RecordingDestination.BODY
    }
}
