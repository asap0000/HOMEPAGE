package com.alcoholchecker.audio

import android.media.AudioManager
import android.media.ToneGenerator

object BeepPlayer {
    private const val VOLUME = 85  // 0–100

    /** カウントダウン中の短いビープ */
    fun countdown() = play(ToneGenerator.TONE_PROP_BEEP, 250)

    /** 計測開始（長め） */
    fun start() = play(ToneGenerator.TONE_PROP_BEEP2, 500)

    /** 計測完了 */
    fun complete() = play(ToneGenerator.TONE_PROP_ACK, 600)

    /** 合格 */
    fun pass() {
        repeat(3) {
            play(ToneGenerator.TONE_DTMF_5, 200)
            Thread.sleep(250)
        }
    }

    /** 不合格 */
    fun fail() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)

    private fun play(tone: Int, durationMs: Int) {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME).use {
                it.startTone(tone, durationMs)
                Thread.sleep(durationMs.toLong())
            }
        }
    }
}

// ToneGenerator は AutoCloseable ではないので拡張で対応
private fun ToneGenerator.use(block: (ToneGenerator) -> Unit) {
    try { block(this) } finally { release() }
}
