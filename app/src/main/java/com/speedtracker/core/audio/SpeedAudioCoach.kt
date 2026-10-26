package com.speedtracker.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpeedAudioCoach(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("es", "ES"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            isReady = true
        }
    }

    fun speakVelocity(velocity: Double, label: String? = null) {
        if (!isReady) return
        val formattedNumber = String.format(Locale.US, "%.2f", velocity)
        val text = if (label != null) "$label $formattedNumber" else formattedNumber
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vbt_speed")
    }

    fun speakCutoffAlert() {
        if (!isReady) return
        tts?.speak("¡Alto! Umbral de fatiga alcanzado", TextToSpeech.QUEUE_ADD, null, "vbt_cutoff")
    }

    fun speakJumpHeight(heightCm: Double) {
        if (!isReady) return
        val formatted = "${heightCm.toInt()} centímetros"
        tts?.speak(formatted, TextToSpeech.QUEUE_FLUSH, null, "jump_height")
    }

    fun speakDropJump(heightCm: Double, contactTimeMs: Long, rsi: Double) {
        if (!isReady) return
        val rsiFormatted = String.format(Locale.US, "%.1f", rsi)
        val text = "${heightCm.toInt()} centímetros. Contacto $contactTimeMs milisegundos. RSI $rsiFormatted"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drop_jump_metrics")
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
