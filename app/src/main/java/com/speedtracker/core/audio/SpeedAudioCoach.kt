package com.speedtracker.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.roundToInt

class SpeedAudioCoach(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Configurar voz en Español Latinoamericano (México / América Latina)
            val latamLocale = Locale("es", "MX")
            var result = tts?.setLanguage(latamLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val usLatam = Locale("es", "US")
                result = tts?.setLanguage(usLatam)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val genericEs = Locale("es")
                    result = tts?.setLanguage(genericEs)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.getDefault()
                    }
                }
            }

            // Tono y velocidad natural para entrenamiento en vivo
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.08f)
            isReady = true
        }
    }

    /**
     * Anuncia la velocidad en español latino natural:
     * Ej: 1.85 m/s -> "1 punto 85"
     * Ej: 0.90 m/s -> "cero punto 90"
     */
    fun speakVelocity(velocity: Double, label: String? = null) {
        if (!isReady || velocity <= 0.05) return
        val whole = velocity.toInt()
        val decimals = ((velocity - whole) * 100.0).roundToInt().coerceIn(0, 99)
        val decStr = if (decimals < 10) "cero $decimals" else "$decimals"
        val spokenNumber = if (whole == 0) {
            "cero punto $decStr"
        } else {
            "$whole punto $decStr"
        }

        val text = if (label != null) "$label $spokenNumber" else spokenNumber
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vbt_speed")
    }

    /**
     * Anuncia la velocidad y el porcentaje de pérdida / fatiga en español latino:
     * Ej Rep 1: "1 punto 85"
     * Ej Rep 2 (con 6% fatiga): "1 punto 74, fatiga 6 por ciento"
     * Ej Rep con corte de fatiga: "1 punto 48. ¡Atención, umbral del 20 por ciento alcanzado, fin de serie!"
     */
    fun speakRepFeedback(
        velocity: Double,
        lossPercent: Double = 0.0,
        isCutoffReached: Boolean = false,
        targetLossPercent: Double? = null
    ) {
        if (!isReady || velocity <= 0.05) return
        val whole = velocity.toInt()
        val decimals = ((velocity - whole) * 100.0).roundToInt().coerceIn(0, 99)
        val decStr = if (decimals < 10) "cero $decimals" else "$decimals"
        val spokenVelocity = if (whole == 0) "cero punto $decStr" else "$whole punto $decStr"

        val text = if (isCutoffReached) {
            val lossVal = targetLossPercent?.roundToInt() ?: lossPercent.roundToInt()
            "$spokenVelocity. ¡Atención, umbral del $lossVal por ciento alcanzado, fin de serie!"
        } else if (lossPercent >= 4.0) {
            val lossVal = lossPercent.roundToInt()
            "$spokenVelocity, fatiga $lossVal por ciento"
        } else {
            spokenVelocity
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vbt_speed")
    }

    /**
     * Alerta de fatiga en español latino
     */
    fun speakCutoffAlert() {
        if (!isReady) return
        tts?.speak("Atención, umbral de fatiga alcanzado", TextToSpeech.QUEUE_ADD, null, "vbt_cutoff")
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
