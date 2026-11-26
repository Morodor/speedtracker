package com.speedtracker.core.audio

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Perfiles originales de Coach VBT por voz (100% libres de derechos de autor).
 * El usuario puede elegir el estilo de retroalimentación y personalizar las frases
 * usando {vel} (velocidad medida) y {loss} (% de pérdida de velocidad).
 */
enum class VoiceCoachProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val emoji: String,
    val pitch: Float,
    val speechRate: Float,
    val defaultFastTemplate: String,
    val defaultNormalTemplate: String,
    val defaultSlowCutoffTemplate: String
) {
    COACH_OLIMPICO(
        id = "COACH",
        displayName = "Coach Olímpico",
        subtitle = "Técnico y directo",
        emoji = "🏋️",
        pitch = 0.96f,
        speechRate = 1.08f,
        defaultFastTemplate = "¡Excelente explosividad! {vel}",
        defaultNormalTemplate = "{vel}",
        defaultSlowCutoffTemplate = "{vel}. ¡Atención, umbral del {loss} por ciento alcanzado, fin de serie!"
    ),
    MOTIVADOR_INTENSO(
        id = "MOTIVADOR",
        displayName = "Coach Exigente",
        subtitle = "Feedback intenso y enérgico",
        emoji = "🔥",
        pitch = 0.85f,
        speechRate = 1.12f,
        defaultFastTemplate = "¡Potencia pura! {vel}, ¡esa barra voló!",
        defaultNormalTemplate = "¡{vel}! ¡Mantén la aceleración hasta el final!",
        defaultSlowCutoffTemplate = "¡{vel}! ¡Bajaste {loss} por ciento de velocidad, corta la serie ahora!"
    ),
    SOLO_NUMEROS(
        id = "NUMEROS",
        displayName = "Solo Velocidad",
        subtitle = "Lectura rápida de competencia",
        emoji = "⚡",
        pitch = 1.0f,
        speechRate = 1.15f,
        defaultFastTemplate = "{vel}",
        defaultNormalTemplate = "{vel}",
        defaultSlowCutoffTemplate = "{vel}, menos {loss} por ciento"
    ),
    MUTED(
        id = "MUTED",
        displayName = "Silencio",
        subtitle = "Solo lectura visual en pantalla",
        emoji = "🔇",
        pitch = 1.0f,
        speechRate = 1.0f,
        defaultFastTemplate = "",
        defaultNormalTemplate = "",
        defaultSlowCutoffTemplate = ""
    );

    companion object {
        fun fromId(id: String?): VoiceCoachProfile {
            return values().find { it.id == id } ?: COACH_OLIMPICO
        }
    }
}

class SpeedAudioCoach(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val prefs = context.getSharedPreferences("speed_audio_coach_prefs", Context.MODE_PRIVATE)

    var fastRepThresholdMps: Double
        get() = prefs.getFloat("fast_rep_threshold_mps", 1.15f).toDouble()
        set(value) {
            prefs.edit().putFloat("fast_rep_threshold_mps", value.toFloat()).apply()
        }

    var currentProfile: VoiceCoachProfile
        get() = VoiceCoachProfile.fromId(prefs.getString("selected_voice_profile", VoiceCoachProfile.COACH_OLIMPICO.id))
        set(profile) {
            prefs.edit().putString("selected_voice_profile", profile.id).apply()
            applyVoiceProfileSettings(profile)
        }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val candidateLocales = listOf(
                Locale("es", "MX"),
                Locale("es", "US"),
                Locale("es", "419"),
                Locale("es", "CL"),
                Locale("es", "AR"),
                Locale("es", "CO"),
                Locale("es")
            )
            var appliedLocale: Locale? = null
            for (loc in candidateLocales) {
                val res = tts?.setLanguage(loc)
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    appliedLocale = loc
                    break
                }
            }
            if (appliedLocale == null) {
                tts?.language = Locale.getDefault()
            }

            selectBestSpanishVoice()
            applyVoiceProfileSettings(currentProfile)
            isReady = true
        }
    }

    private fun selectBestSpanishVoice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val voices: Set<Voice>? = tts?.voices
                if (!voices.isNullOrEmpty()) {
                    val esVoices = voices.filter { v ->
                        v.locale.language.equals("es", ignoreCase = true) &&
                            (v.locale.country.equals("MX", ignoreCase = true) ||
                             v.locale.country.equals("US", ignoreCase = true) ||
                             v.name.contains("es-us", ignoreCase = true) ||
                             v.name.contains("es-mx", ignoreCase = true))
                    }
                    val preferredMale = esVoices.firstOrNull { v ->
                        v.name.contains("male", ignoreCase = true) ||
                        v.name.contains("-eed", ignoreCase = true) ||
                        v.name.contains("-sfb", ignoreCase = true) ||
                        v.name.contains("-b-", ignoreCase = true) ||
                        v.name.contains("-d-", ignoreCase = true)
                    } ?: esVoices.firstOrNull()

                    if (preferredMale != null) {
                        tts?.voice = preferredMale
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun applyVoiceProfileSettings(profile: VoiceCoachProfile) {
        tts?.setPitch(profile.pitch)
        tts?.setSpeechRate(profile.speechRate)
    }

    fun getFastTemplate(profile: VoiceCoachProfile = currentProfile): String {
        return prefs.getString("tpl_fast_${profile.id}", profile.defaultFastTemplate) ?: profile.defaultFastTemplate
    }

    fun getNormalTemplate(profile: VoiceCoachProfile = currentProfile): String {
        return prefs.getString("tpl_normal_${profile.id}", profile.defaultNormalTemplate) ?: profile.defaultNormalTemplate
    }

    fun getSlowCutoffTemplate(profile: VoiceCoachProfile = currentProfile): String {
        return prefs.getString("tpl_slow_${profile.id}", profile.defaultSlowCutoffTemplate) ?: profile.defaultSlowCutoffTemplate
    }

    fun saveCustomTemplates(
        profile: VoiceCoachProfile,
        fastTemplate: String,
        normalTemplate: String,
        slowCutoffTemplate: String
    ) {
        prefs.edit()
            .putString("tpl_fast_${profile.id}", fastTemplate.trim().ifEmpty { profile.defaultFastTemplate })
            .putString("tpl_normal_${profile.id}", normalTemplate.trim().ifEmpty { profile.defaultNormalTemplate })
            .putString("tpl_slow_${profile.id}", slowCutoffTemplate.trim().ifEmpty { profile.defaultSlowCutoffTemplate })
            .apply()
    }

    fun resetProfileToDefault(profile: VoiceCoachProfile) {
        prefs.edit()
            .remove("tpl_fast_${profile.id}")
            .remove("tpl_normal_${profile.id}")
            .remove("tpl_slow_${profile.id}")
            .apply()
    }

    private fun formatVelocityForSpeech(velocity: Double): String {
        val whole = velocity.toInt()
        val decimals = ((velocity - whole) * 100.0).roundToInt().coerceIn(0, 99)
        val decStr = if (decimals < 10) "cero $decimals" else "$decimals"
        return if (whole == 0) "cero punto $decStr" else "$whole punto $decStr"
    }

    fun speakPreview(profile: VoiceCoachProfile, previewType: String = "SLOW") {
        if (!isReady || profile == VoiceCoachProfile.MUTED) return
        applyVoiceProfileSettings(profile)
        val sampleVel = when (previewType) {
            "FAST" -> formatVelocityForSpeech(1.75)
            "NORMAL" -> formatVelocityForSpeech(1.10)
            else -> formatVelocityForSpeech(0.55)
        }
        val template = when (previewType) {
            "FAST" -> getFastTemplate(profile)
            "NORMAL" -> getNormalTemplate(profile)
            else -> getSlowCutoffTemplate(profile)
        }
        val finalSpeech = template
            .replace("{vel}", sampleVel)
            .replace("{loss}", "22")
        tts?.speak(finalSpeech, TextToSpeech.QUEUE_FLUSH, null, "vbt_preview_${Random.nextInt(1000)}")
    }

    fun speakVelocity(velocity: Double, label: String? = null) {
        val profile = currentProfile
        if (!isReady || profile == VoiceCoachProfile.MUTED || velocity <= 0.15) return
        applyVoiceProfileSettings(profile)
        val spokenNumber = formatVelocityForSpeech(velocity)
        val text = if (label != null) "$label $spokenNumber" else spokenNumber
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vbt_speed")
    }

    fun speakRepFeedback(
        velocity: Double,
        lossPercent: Double = 0.0,
        isCutoffReached: Boolean = false,
        targetLossPercent: Double? = null
    ) {
        val profile = currentProfile
        if (!isReady || profile == VoiceCoachProfile.MUTED || velocity <= 0.15) return
        applyVoiceProfileSettings(profile)

        val spokenVelocity = formatVelocityForSpeech(velocity)
        val lossVal = (if (lossPercent > 0.5) lossPercent else (targetLossPercent ?: 20.0)).roundToInt()

        val rawTemplate = when {
            isCutoffReached -> getSlowCutoffTemplate(profile)
            velocity >= fastRepThresholdMps -> getFastTemplate(profile)
            else -> {
                val baseNormal = getNormalTemplate(profile)
                if (profile == VoiceCoachProfile.COACH_OLIMPICO && lossPercent >= 5.0 && !baseNormal.contains("{loss}")) {
                    "$baseNormal, fatiga $lossVal por ciento"
                } else {
                    baseNormal
                }
            }
        }

        val text = rawTemplate
            .replace("{vel}", spokenVelocity)
            .replace("{loss}", lossVal.toString())

        if (text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vbt_speed")
        }
    }

    fun speakCutoffAlert() {
        val profile = currentProfile
        if (!isReady || profile == VoiceCoachProfile.MUTED) return
        applyVoiceProfileSettings(profile)
        val text = getSlowCutoffTemplate(profile)
            .replace("{vel}", "")
            .replace("{loss}", "20")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "vbt_cutoff")
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
