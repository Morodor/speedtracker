package com.speedtracker.core.math

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class LoadVelocityPoint(
    val loadKg: Double,
    val velocityMps: Double,
    val repCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

data class OneRMProfile(
    val estimatedOneRM: Double,
    val slope: Double,
    val intercept: Double,
    val rSquared: Double,
    val mvt: Double,
    val isValid: Boolean,
    val message: String
)

object OneRMCalculator {

    // Velocidades mínimas de levantamiento típicas (Minimal Velocity Thresholds - MVT)
    // según estudios de González-Badillo & Sánchez-Medina (2010), Balsalobre et al., Suchomel et al.
    val MVT_SQUAT = 0.30        // Sentadilla (VM)
    val MVT_BENCH = 0.15        // Press de Banca (VM)
    val MVT_DEADLIFT = 0.15     // Peso Muerto (VM)
    val MVT_HIP_THRUST = 0.25   // Hip Thrust (VM)
    val MVT_PULL_UP = 0.20      // Dominadas (VM)

    // DLP (Derivados del Levantamiento de Pesas) - Velocidad Pico Mínima (PV)
    val MVT_SNATCH_FLOOR = 1.65 // Snatch Suelo (PV)
    val MVT_SNATCH_HANG = 1.60  // Hang Snatch (PV)
    val MVT_POWER_SNATCH = 1.78 // Power Snatch (PV)
    val MVT_CLEAN_FLOOR = 1.38  // Clean Suelo (PV)
    val MVT_CLEAN_HANG = 1.35   // Hang Clean (PV)
    val MVT_POWER_CLEAN = 1.52  // Power Clean (PV)
    val MVT_OLYMPIC_PULL = 1.45 // Tirones Olímpicos (PV)
    val MVT_JERK = 1.38         // Jerk / Push Press (PV)

    // Puntos cargados en la sesión actual
    val sessionPoints = mutableListOf<LoadVelocityPoint>()

    fun addPoint(loadKg: Double, velocityMps: Double, repCount: Int = 1) {
        if (loadKg > 0.0 && velocityMps > 0.0) {
            sessionPoints.add(LoadVelocityPoint(loadKg, velocityMps, repCount))
        }
    }

    fun removePoint(index: Int) {
        if (index in sessionPoints.indices) {
            sessionPoints.removeAt(index)
        }
    }

    fun clearPoints() {
        sessionPoints.clear()
    }

    /**
     * Calcula el Perfil Carga-Velocidad y estima el 1RM por regresión lineal:
     * V = slope * Carga + intercept
     * 1RM = (MVT - intercept) / slope
     */
    fun calculateOneRM(mvt: Double = MVT_SQUAT): OneRMProfile {
        if (sessionPoints.size < 2) {
            // Si solo hay 1 punto, usamos la fórmula general de estimación de carga única
            if (sessionPoints.size == 1) {
                val pt = sessionPoints.first()
                // Estimación aproximada para 1 punto asumiendo pendiente estándar (-0.008 m/s por kg)
                val estimated = pt.loadKg / (1.0 - (pt.velocityMps - mvt) * 0.45)
                return OneRMProfile(
                    estimatedOneRM = max(pt.loadKg, estimated),
                    slope = -0.008,
                    intercept = pt.velocityMps + 0.008 * pt.loadKg,
                    rSquared = 1.0,
                    mvt = mvt,
                    isValid = true,
                    message = "Estimación con 1 serie (Se recomiendan al menos 2 cargas distintas)"
                )
            }
            return OneRMProfile(0.0, 0.0, 0.0, 0.0, mvt, false, "Ingresa al menos 2 series con cargas distintas")
        }

        val n = sessionPoints.size
        val xValues = sessionPoints.map { it.loadKg }
        val yValues = sessionPoints.map { it.velocityMps }

        val xMean = xValues.average()
        val yMean = yValues.average()

        var numerator = 0.0
        var denominator = 0.0

        for (i in 0 until n) {
            val xDiff = xValues[i] - xMean
            val yDiff = yValues[i] - yMean
            numerator += xDiff * yDiff
            denominator += xDiff * xDiff
        }

        // Pendiente negativa esperada (a mayor carga, menor velocidad)
        if (denominator == 0.0 || numerator >= 0.0) {
            return OneRMProfile(
                estimatedOneRM = xValues.maxOrNull() ?: 0.0,
                slope = 0.0,
                intercept = 0.0,
                rSquared = 0.0,
                mvt = mvt,
                isValid = false,
                message = "Las cargas deben mostrar disminución de velocidad al aumentar el peso"
            )
        }

        val slope = numerator / denominator
        val intercept = yMean - slope * xMean

        // Cálculo de R^2
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in 0 until n) {
            val yActual = yValues[i]
            val yPred = slope * xValues[i] + intercept
            ssTot += (yActual - yMean).pow(2)
            ssRes += (yActual - yPred).pow(2)
        }

        val rSquared = if (ssTot > 0.0) (1.0 - (ssRes / ssTot)).coerceIn(0.0, 1.0) else 0.0

        // 1RM proyectado en MVT
        val estimated1RM = (mvt - intercept) / slope

        return OneRMProfile(
            estimatedOneRM = max(0.0, estimated1RM),
            slope = slope,
            intercept = intercept,
            rSquared = rSquared,
            mvt = mvt,
            isValid = estimated1RM > 0.0,
            message = if (rSquared >= 0.90) "Perfil excelente (R² = ${String.format("%.2f", rSquared)})"
                      else "Perfil moderado (R² = ${String.format("%.2f", rSquared)}). Añade cargas más diferenciadas."
        )
    }

    /**
     * Estima las repeticiones en reserva (RIR) a partir de la pérdida de velocidad en la serie:
     * - < 10% de pérdida: RIR 4+
     * - 10-19%: RIR 2-3
     * - 20-29%: RIR 1-2
     * - 30-39%: RIR 0-1
     * - >= 40%: RIR 0 (Fallo muscular)
     */
    fun estimateRIR(velocityLossPercent: Double): String {
        return when {
            velocityLossPercent < 10.0 -> "RIR 4+ (Muy lejos del fallo)"
            velocityLossPercent < 20.0 -> "RIR 2-3 (Fuerza / Potencia ideal)"
            velocityLossPercent < 30.0 -> "RIR 1-2 (Hipertrofia óptima)"
            velocityLossPercent < 40.0 -> "RIR 0-1 (Cerca del fallo)"
            else -> "RIR 0 (Fallo alcanzado)"
        }
    }
}
