package com.speedtracker.core.math

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

enum class JumpType {
    CMJ,        // Countermovement Jump (salto vertical con contramovimiento natural)
    SJ,         // Squat Jump (salto concéntrico puro desde flexión isométrica de 90°)
    DROP_JUMP   // Drop Jump (caída desde cajón + rebote inmediato para medir RSI y tiempo de contacto)
}

data class JumpMetrics(
    val heightMeters: Double,
    val flightTimeSeconds: Double,
    val peakPowerWatts: Double,
    val relativePowerWattsPerKg: Double,
    val jumpType: JumpType = JumpType.CMJ,
    val contactTimeMs: Long? = null,
    val rsi: Double? = null, // Reactive Strength Index = Altura(m) / TiempoContacto(s)
    val eur: Double? = null  // Eccentric Utilization Ratio = CMJ / SJ
)

object VerticalJumpAnalyzer {
    
    // Constante de gravedad en m/s^2
    private const val GRAVITY = 9.81

    var currentJumpType: JumpType = JumpType.CMJ

    // Registros históricos en memoria para calcular el ratio EUR (CMJ / SJ)
    var lastSjHeightMeters: Double? = null
    var lastCmjHeightMeters: Double? = null

    var lastJumpMetrics: JumpMetrics? = null
        private set

    var onJumpCompleted: ((JumpMetrics) -> Unit)? = null
    
    /**
     * Calcula la altura del salto vertical y potencia según ecuaciones de Sayers et al. (1999).
     */
    fun calculateJumpMetrics(
        takeoffTimeMs: Long,
        landingTimeMs: Long,
        bodyWeightKg: Double = 70.0,
        jumpType: JumpType = currentJumpType,
        contactStartTimeMs: Long? = null
    ): JumpMetrics? {
        if (landingTimeMs <= takeoffTimeMs) return null
        
        // Tiempo de vuelo (ToF) en segundos
        val flightTimeSeconds = (landingTimeMs - takeoffTimeMs) / 1000.0

        // Filtro de plausibilidad biológica (150 ms a 1250 ms)
        if (flightTimeSeconds < 0.15 || flightTimeSeconds > 1.25) {
            return null
        }
        
        // El tiempo en el que llega al vértice (punto máximo) es la mitad
        val timeToApex = flightTimeSeconds / 2.0
        
        // h = 0.5 * g * t^2
        val heightMeters = 0.5 * GRAVITY * (timeToApex * timeToApex)
        val heightCm = heightMeters * 100.0

        // Fórmula de Sayers: Peak Power (W) = 60.7 * JumpHeight(cm) + 45.3 * BodyMass(kg) - 2055
        val peakPower = (60.7 * heightCm + 45.3 * bodyWeightKg - 2055.0).coerceAtLeast(0.0)
        val relativePower = if (bodyWeightKg > 0) peakPower / bodyWeightKg else 0.0

        // Cálculo de Tiempo de Contacto y RSI en Drop Jump
        var contactTimeMs: Long? = null
        var rsi: Double? = null

        if (jumpType == JumpType.DROP_JUMP && contactStartTimeMs != null && takeoffTimeMs > contactStartTimeMs) {
            val contactDurationMs = takeoffTimeMs - contactStartTimeMs
            // Filtro de tiempo de contacto biomecánicamente válido (entre 80 ms y 600 ms)
            if (contactDurationMs in 80..600) {
                contactTimeMs = contactDurationMs
                val contactSec = contactDurationMs / 1000.0
                // RSI = Altura (m) / Tiempo de contacto (s)
                rsi = heightMeters / contactSec
            }
        }

        // Actualizar métricas para ratio EUR (CMJ / SJ)
        var eur: Double? = null
        if (jumpType == JumpType.SJ) {
            lastSjHeightMeters = heightMeters
        } else if (jumpType == JumpType.CMJ) {
            lastCmjHeightMeters = heightMeters
            if (lastSjHeightMeters != null && lastSjHeightMeters!! > 0) {
                eur = heightMeters / lastSjHeightMeters!!
            }
        }

        return JumpMetrics(
            heightMeters = heightMeters,
            flightTimeSeconds = flightTimeSeconds,
            peakPowerWatts = peakPower,
            relativePowerWattsPerKg = relativePower,
            jumpType = jumpType,
            contactTimeMs = contactTimeMs,
            rsi = rsi,
            eur = eur
        )
    }

    /**
     * Calcula las métricas del salto con precisión sub-frame (Double ms) e integración de cámara lenta (slowMoFactor),
     * combinando el método My Jump Lab con interpolación cinemática sub-fotograma.
     */
    fun calculateJumpMetricsPrecise(
        takeoffTimeMs: Double,
        landingTimeMs: Double,
        bodyWeightKg: Double = 70.0,
        jumpType: JumpType = currentJumpType,
        contactStartTimeMs: Double? = null,
        slowMoFactor: Double = 1.0
    ): JumpMetrics? {
        if (landingTimeMs <= takeoffTimeMs) return null
        val safeSlowMo = slowMoFactor.coerceIn(0.25, 16.0)

        // Tiempo de vuelo real (ToF) en segundos corregido por factor de cámara lenta e interpolación sub-frame
        val flightTimeSeconds = ((landingTimeMs - takeoffTimeMs) / 1000.0) / safeSlowMo

        // Filtro de plausibilidad biológica (100 ms a 1350 ms)
        if (flightTimeSeconds < 0.10 || flightTimeSeconds > 1.35) {
            return null
        }

        val timeToApex = flightTimeSeconds / 2.0
        val heightMeters = 0.5 * GRAVITY * (timeToApex * timeToApex)
        val heightCm = heightMeters * 100.0

        // Ecuación de Sayers et al. (1999): Peak Power (W) = 60.7 * JumpHeight(cm) + 45.3 * BodyMass(kg) - 2055
        val peakPower = (60.7 * heightCm + 45.3 * bodyWeightKg - 2055.0).coerceAtLeast(0.0)
        val relativePower = if (bodyWeightKg > 0) peakPower / bodyWeightKg else 0.0

        var contactTimeMs: Long? = null
        var rsi: Double? = null

        if (jumpType == JumpType.DROP_JUMP && contactStartTimeMs != null && takeoffTimeMs > contactStartTimeMs) {
            val contactDurationRealMs = ((takeoffTimeMs - contactStartTimeMs) / safeSlowMo).toLong()
            if (contactDurationRealMs in 60..800) {
                contactTimeMs = contactDurationRealMs
                val contactSec = contactDurationRealMs / 1000.0
                rsi = heightMeters / contactSec
            }
        }

        var eur: Double? = null
        if (jumpType == JumpType.SJ) {
            lastSjHeightMeters = heightMeters
        } else if (jumpType == JumpType.CMJ) {
            lastCmjHeightMeters = heightMeters
            if (lastSjHeightMeters != null && lastSjHeightMeters!! > 0) {
                eur = heightMeters / lastSjHeightMeters!!
            }
        }

        val metrics = JumpMetrics(
            heightMeters = heightMeters,
            flightTimeSeconds = flightTimeSeconds,
            peakPowerWatts = peakPower,
            relativePowerWattsPerKg = relativePower,
            jumpType = jumpType,
            contactTimeMs = contactTimeMs,
            rsi = rsi,
            eur = eur
        )
        lastJumpMetrics = metrics
        return metrics
    }

    private var baselineY: Float? = null
    private var takeoffTimeMs: Long? = null
    private var isAirborne: Boolean = false

    // Variables de estado específicas para Drop Jump (caída previa desde cajón)
    private var isDropFalling: Boolean = false
    private var contactStartTimeMs: Long? = null
    
    // Tolerancia para detectar que el pie se separó significativamente del piso
    private const val TAKEOFF_THRESHOLD_PX = 15f 

    fun reset() {
        baselineY = null
        takeoffTimeMs = null
        isAirborne = false
        isDropFalling = false
        contactStartTimeMs = null
        lastJumpMetrics = null
    } 

    fun prepareDropJump() {
        isDropFalling = true
        contactStartTimeMs = null
        takeoffTimeMs = null
        isAirborne = false
    }
    
    /**
     * Procesa cada frame del analizador de Pose de ML Kit.
     * Retorna la altura del salto en metros si acaba de aterrizar, o null si está en el aire/reposo.
     */
    fun processPose(
        pose: Pose,
        currentTimeMs: Long,
        bodyWeightKg: Double = 70.0
    ): Double? {
        // En plano frontal enfocado exclusivamente en ambos pies en el suelo,
        // priorizamos la punta del pie (FOOT_INDEX) o tobillo (ANKLE) con umbral tolerante (0.30f)
        val leftFoot = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
            ?: pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
            ?: pose.getPoseLandmark(PoseLandmark.LEFT_HEEL)
        val rightFoot = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)
            ?: pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
            ?: pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL)
        
        if (leftFoot == null || rightFoot == null) return null
        if (leftFoot.inFrameLikelihood < 0.30f || rightFoot.inFrameLikelihood < 0.30f) return null
        
        // Promediamos la posición Y de ambos pies (baja Y = elevación en el aire)
        val currentAvgY = (leftFoot.position.y + rightFoot.position.y) / 2f
        
        if (baselineY == null) {
            baselineY = currentAvgY
            return null
        }
        
        val baseline = baselineY!!

        // Manejo específico para DROP JUMP
        if (currentJumpType == JumpType.DROP_JUMP) {
            // 1. Fase de caída del cajón: el pie está por encima o cayendo, y toca el suelo (baseline)
            if (contactStartTimeMs == null && currentAvgY >= baseline - TAKEOFF_THRESHOLD_PX) {
                contactStartTimeMs = currentTimeMs
                baselineY = currentAvgY
            } else if (contactStartTimeMs != null && !isAirborne && (baseline - currentAvgY) > TAKEOFF_THRESHOLD_PX) {
                // 2. Rebote / Despegue explosivo tras tocar el suelo
                isAirborne = true
                takeoffTimeMs = currentTimeMs
            } else if (isAirborne && currentAvgY >= baseline - (TAKEOFF_THRESHOLD_PX / 2)) {
                // 3. Aterrizaje final del salto
                isAirborne = false
                val landingTime = currentTimeMs
                if (takeoffTimeMs != null) {
                    val metrics = calculateJumpMetrics(
                        takeoffTimeMs = takeoffTimeMs!!,
                        landingTimeMs = landingTime,
                        bodyWeightKg = bodyWeightKg,
                        jumpType = JumpType.DROP_JUMP,
                        contactStartTimeMs = contactStartTimeMs
                    )
                    takeoffTimeMs = null
                    contactStartTimeMs = null
                    if (metrics != null) {
                        lastJumpMetrics = metrics
                        onJumpCompleted?.invoke(metrics)
                        return metrics.heightMeters
                    }
                }
            }
            return null
        }

        // Manejo estándar para CMJ y SJ
        // Cuando Y decrece significa que el usuario está saltando hacia arriba
        if (!isAirborne && (baseline - currentAvgY) > TAKEOFF_THRESHOLD_PX) {
            // Despegue
            isAirborne = true
            takeoffTimeMs = currentTimeMs
        } else if (isAirborne && currentAvgY >= baseline - (TAKEOFF_THRESHOLD_PX / 2)) {
            // Aterrizaje
            isAirborne = false
            val landingTime = currentTimeMs
            if (takeoffTimeMs != null) {
                val metrics = calculateJumpMetrics(
                    takeoffTimeMs = takeoffTimeMs!!,
                    landingTimeMs = landingTime,
                    bodyWeightKg = bodyWeightKg,
                    jumpType = currentJumpType
                )
                takeoffTimeMs = null
                if (metrics != null) {
                    lastJumpMetrics = metrics
                    onJumpCompleted?.invoke(metrics)
                    return metrics.heightMeters
                }
            }
        } else if (!isAirborne) {
            // Actualización suave de la línea base en reposo
            baselineY = baseline * 0.9f + currentAvgY * 0.1f
        }
        
        return null
    }
}
