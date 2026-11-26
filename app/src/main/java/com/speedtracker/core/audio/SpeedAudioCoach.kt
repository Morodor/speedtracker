package com.speedtracker.core.audio

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Perfiles de Voz para el Coach de Velocidad en Vivo (Anime Latino + Coach Olímpico + Silencio).
 * Cada personaje tiene su propio timbre (pitch), cadencia (speechRate) y repertorio de frases
 * personalizables para:
 *  - Repetición Explosiva / Rápida (Alta velocidad)
 *  - Repetición Normal (Con o sin % de pérdida leve)
 *  - Bajo el Umbral / Límite de Fatiga Alcanzado (ej. Vegeta: "Eres muy lento, ¡INSECTO! Bajaste la velocidad.")
 */
enum class VoiceCoachProfile(
    val id: String,
    val displayName: String,
    val animeOrigin: String,
    val emoji: String,
    val pitch: Float,
    val speechRate: Float,
    val defaultFastTemplate: String,
    val defaultNormalTemplate: String,
    val defaultSlowCutoffTemplate: String
) {
    GOKU_LATINO(
        id = "GOKU",
        displayName = "Goku (Latino)",
        animeOrigin = "Dragon Ball Z",
        emoji = "🟠",
        pitch = 1.02f,
        speechRate = 1.12f,
        defaultFastTemplate = "¡Increíble! {vel}. ¡Tu Ki está al máximo, nivel Super Saiyajin!",
        defaultNormalTemplate = "¡Bien hecho! {vel}. ¡Sigamos entrenando!",
        defaultSlowCutoffTemplate = "¡Oye, bajaste a {vel}! Perdiste {loss} por ciento, ¡necesitas una Semilla del Ermitaño!"
    ),
    VEGETA_LATINO(
        id = "VEGETA",
        displayName = "Vegeta (Príncipe Saiyajin)",
        animeOrigin = "Dragon Ball Z",
        emoji = "👑",
        pitch = 0.80f,
        speechRate = 1.06f,
        defaultFastTemplate = "¡Nada mal para un guerrero de clase baja! {vel}, ¡ese es el orgullo Saiyajin!",
        defaultNormalTemplate = "{vel}. ¡No te confíes, sigue empujando esa barra, insecto!",
        defaultSlowCutoffTemplate = "¡{vel}! Eres muy lento, ¡INSECTO! Bajaste la velocidad {loss} por ciento."
    ),
    HANAMICHI_SAKURAGI(
        id = "SAKURAGI",
        displayName = "Hanamichi Sakuragi",
        animeOrigin = "Slam Dunk",
        emoji = "🏀",
        pitch = 0.90f,
        speechRate = 1.15f,
        defaultFastTemplate = "¡Ja, ja, ja! ¡{vel}! ¡Soy un talentoso, el Rey del Levantamiento!",
        defaultNormalTemplate = "¡{vel}! ¡El talentoso Sakuragi apenas está calentando!",
        defaultSlowCutoffTemplate = "¡Oye gorila, bajaste a {vel}! ¡{loss} por ciento de fatiga, hasta un genio necesita descansar!"
    ),
    ROCK_LEE(
        id = "ROCK_LEE",
        displayName = "Rock Lee / Might Guy",
        animeOrigin = "Naruto",
        emoji = "🟢",
        pitch = 1.08f,
        speechRate = 1.18f,
        defaultFastTemplate = "¡{vel}! ¡Las Ocho Puertas Internas abiertas! ¡Esa es la llama de la juventud!",
        defaultNormalTemplate = "¡{vel}! ¡El trabajo duro supera al talento natural!",
        defaultSlowCutoffTemplate = "¡{vel}! ¡Tu juventud perdió {loss} por ciento de velocidad! ¡Descansa y vuelve más fuerte!"
    ),
    ALL_MIGHT(
        id = "ALL_MIGHT",
        displayName = "All Might (Plus Ultra)",
        animeOrigin = "My Hero Academia",
        emoji = "💪",
        pitch = 0.72f,
        speechRate = 1.04f,
        defaultFastTemplate = "¡Ya estoy aquí! ¡{vel}! ¡Un tirón digno del Símbolo de la Paz, Plus Ultra!",
        defaultNormalTemplate = "¡{vel}! ¡Mantén la sonrisa y aprieta esa barra, joven héroe!",
        defaultSlowCutoffTemplate = "¡Cuidado joven! Bajaste a {vel}, {loss} por ciento de fatiga. ¡Detén la serie por hoy!"
    ),
    LUFFY_LATINO(
        id = "LUFFY",
        displayName = "Monkey D. Luffy",
        animeOrigin = "One Piece",
        emoji = "🏴‍☠️",
        pitch = 1.16f,
        speechRate = 1.18f,
        defaultFastTemplate = "¡Gear Second! ¡{vel}! ¡Seré el Rey de la Halterofilia!",
        defaultNormalTemplate = "¡{vel}! ¡Estuvo genial, sigamos levantando!",
        defaultSlowCutoffTemplate = "¡Qué hambre, bajaste a {vel}! ¡Perdiste {loss} por ciento de fuerza, hora de comer carne!"
    ),
    RORONOA_ZORO(
        id = "ZORO",
        displayName = "Roronoa Zoro",
        animeOrigin = "One Piece",
        emoji = "⚔️",
        pitch = 0.76f,
        speechRate = 1.02f,
        defaultFastTemplate = "{vel}. Un corte limpio y veloz. Nada mal.",
        defaultNormalTemplate = "{vel}. Si no puedes con este peso, jamás serás el mejor.",
        defaultSlowCutoffTemplate = "{vel}. Eres demasiado débil, perdiste {loss} por ciento de velocidad. Suelta la barra."
    ),
    SATORU_GOJO(
        id = "GOJO",
        displayName = "Satoru Gojo",
        animeOrigin = "Jujutsu Kaisen",
        emoji = "🤞",
        pitch = 0.94f,
        speechRate = 1.10f,
        defaultFastTemplate = "¡Expansión de Dominio! {vel}. No te preocupes, eres el más fuerte.",
        defaultNormalTemplate = "{vel}. Bastante limpio, pero aún puedes ir más rápido.",
        defaultSlowCutoffTemplate = "Uy, {vel}, caíste {loss} por ciento. Te quedaste sin energía maldita, descansa."
    ),
    EREN_JAEGER(
        id = "EREN",
        displayName = "Eren Jaeger / Levi",
        animeOrigin = "Attack on Titan",
        emoji = "⚡",
        pitch = 0.82f,
        speechRate = 1.08f,
        defaultFastTemplate = "¡Tatakae! ¡{vel}! ¡Entrega tu corazón en cada levantamiento!",
        defaultNormalTemplate = "{vel}. Sigue avanzando, pelea, pelea.",
        defaultSlowCutoffTemplate = "¡{vel}! ¡Perdiste {loss} por ciento de velocidad! ¡No mueras en vano, corta la serie ahora!"
    ),
    COACH_OLIMPICO(
        id = "COACH",
        displayName = "Coach Olímpico (Técnico)",
        animeOrigin = "Modo Competencia VBT",
        emoji = "🏋️",
        pitch = 0.96f,
        speechRate = 1.08f,
        defaultFastTemplate = "¡Excelente explosividad! {vel}",
        defaultNormalTemplate = "{vel}",
        defaultSlowCutoffTemplate = "{vel}. ¡Atención, umbral del {loss} por ciento alcanzado, fin de serie!"
    ),
    MUTED(
        id = "MUTED",
        displayName = "Silencio (Sin Voz)",
        animeOrigin = "Solo visual en pantalla",
        emoji = "🔇",
        pitch = 1.0f,
        speechRate = 1.0f,
        defaultFastTemplate = "",
        defaultNormalTemplate = "",
        defaultSlowCutoffTemplate = ""
    );

    companion object {
        fun fromId(id: String?): VoiceCoachProfile {
            return values().find { it.id == id } ?: VEGETA_LATINO
        }
    }
}

class SpeedAudioCoach(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val prefs = context.getSharedPreferences("speed_audio_coach_prefs", Context.MODE_PRIVATE)

    // Umbral (m/s) a partir del cual una repetición se celebra como "Explosiva / Rápida"
    var fastRepThresholdMps: Double
        get() = prefs.getFloat("fast_rep_threshold_mps", 1.15f).toDouble()
        set(value) {
            prefs.edit().putFloat("fast_rep_threshold_mps", value.toFloat()).apply()
        }

    var currentProfile: VoiceCoachProfile
        get() = VoiceCoachProfile.fromId(prefs.getString("selected_voice_profile", VoiceCoachProfile.VEGETA_LATINO.id))
        set(profile) {
            prefs.edit().putString("selected_voice_profile", profile.id).apply()
            applyVoiceProfileSettings(profile)
        }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Priorizar Español Latinoamericano (México / EE.UU. / Latinoamérica)
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

            // Intentar seleccionar una voz masculina en Español Latino si el motor de Google TTS la tiene disponible
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
                    // Buscar voces en español latino (es-US o es-MX), priorizando timbres masculinos o de red local
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

    // ── OBTENCIÓN Y PERSONALIZACIÓN DE FRASES POR PERSONAJE ──

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

    /**
     * Prueba en vivo la voz del personaje seleccionado con una frase de demostración.
     */
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

    /**
     * Anuncia el resultado de la repetición usando la voz y frase personalizada del personaje activo.
     * Reemplaza los comodines:
     *  - {vel} -> velocidad hablada (ej. "1 punto 45")
     *  - {loss} -> porcentaje de pérdida de velocidad (ej. "20")
     */
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
