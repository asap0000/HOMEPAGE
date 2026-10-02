package com.istech.buscourse.guidance

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NaviSpeechStatus {
    private val mutableAvailable = MutableStateFlow<Boolean?>(null)
    val available = mutableAvailable.asStateFlow()
    internal fun update(value: Boolean?) { mutableAvailable.value = value }
}

/** ナビ画面専用の日本語TTS。端末内合成のみを使い、画面を離れると停止・解放する。 */
class NaviSpeechGuide(context: Context, private val onAvailable: (Boolean) -> Unit) : OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var available = false
    private var focusRequest: AudioFocusRequest? = null

    override fun onInit(status: Int) {
        val engine = tts ?: return
        available = status == TextToSpeech.SUCCESS && engine.setLanguage(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE
        NaviSpeechStatus.update(available)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { abandonFocus() }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { abandonFocus() }
        })
        onAvailable(available)
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!available || text.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) engine.stop() }
                .build()
            focusRequest = request
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navi-guidance")
    }

    fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
    }

    private fun abandonFocus(): Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }
}
