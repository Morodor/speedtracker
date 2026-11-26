package com.speedtracker.core.math

import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.LinkedList
import kotlin.math.abs

data class BarPathPoint(
    val x: Float,
    val y: Float,
    val velocity: Double,
    val timestampMs: Long
)

data class RepResult(
    val repNumber: Int,
    val peakVelocity: Double,
    val meanVelocity: Double,
    val romMeters: Double,
    val isCutoffReached: Boolean = false,
    val velocityLossPercent: Double = 0.0
)

enum class VBTExerciseType {
    FLOOR_PULL,           // Snatch, Clean, Peso Muerto (parte desde el suelo/abajo sin excéntrica previa)
    HANG_PULL,            // Colgante: Hang Clean, Hang Snatch, Tirones, RDL (parte con la barra en suspensión a nivel muslo/rodilla)
    ECCENTRIC_CONCENTRIC  // Sentadilla, Press Banca, Jerk (bajada previa, punto de inflexión en el fondo, subida)
}

enum class VBTVelocityMetric {
    MEAN_VELOCITY, // MV: Estándar para fuerza (Sentadilla, Banca, Peso Muerto)
    PEAK_VELOCITY  // PV: Estándar para balísticos y halterofilia (Snatch, Clean)
}

enum class VBTExerciseFamily(val label: String, val icon: String) {
    DLP_OLYMPIC("DLP (Olímpicos)", "⚡"),
    HILL_TRADITIONAL("Ley de Hill (Fuerza)", "🏋️")
}

enum class VBTExerciseModel(
    val displayName: String,
    val family: VBTExerciseFamily,
    val usesPeakVelocity: Boolean,
    val mvt: Double,
    val formulaDescription: String
) {
    // ── DLP: DERIVADOS DEL LEVANTAMIENTO OLÍMPICO (35 Ejercicios + Dos Tiempos / Clean & Jerk) ──
    SNATCH_FLOOR("Arranque (Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.85, "%1RM = 145 - 28.5 * PV"),
    HANG_SNATCH("Arranque Colgante (Hang Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 142 - 27.0 * PV"),
    BELOW_KNEE_HANG_SNATCH("Arranque Colgante por Debajo de Rodillas (Below Knee Hang Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.82, "%1RM = 143 - 27.5 * PV"),
    POWER_SNATCH("Arranque de Potencia (Power Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 150 - 29.0 * PV"),
    HANG_POWER_SNATCH("Arranque de Potencia Colgante (Hang Power Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 146 - 28.0 * PV"),
    BLOCK_SNATCH("Arranque desde Bloques (Block Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.82, "%1RM = 143 - 27.5 * PV"),
    BLOCK_POWER_SNATCH("Arranque de Potencia desde Bloques (Block Power Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 147 - 28.5 * PV"),
    MUSCLE_SNATCH("Arranque de Fuerza (Muscle Snatch)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.85, "%1RM = 140 - 26.5 * PV"),

    CLEAN_FLOOR("Cargada (Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 140 - 29.0 * PV"),
    HANG_CLEAN("Cargada Colgante (Hang Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 138 - 28.0 * PV"),
    BELOW_KNEE_HANG_CLEAN("Cargada Colgante por Debajo de Rodillas (Below Knee Hang Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.78, "%1RM = 139 - 28.5 * PV"),
    POWER_CLEAN("Cargada de Potencia (Power Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 145 - 29.5 * PV"),
    HANG_POWER_CLEAN("Cargada de Potencia Colgante (Hang Power Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 142 - 28.5 * PV"),
    BLOCK_CLEAN("Cargada desde Bloques (Block Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.78, "%1RM = 139 - 28.0 * PV"),
    BLOCK_POWER_CLEAN("Cargada de Potencia desde Bloques (Block Power Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 143 - 29.0 * PV"),
    MUSCLE_CLEAN("Cargada de Fuerza (Muscle Clean)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 136 - 27.0 * PV"),

    // Único Complex Oficial en Catálogo Base
    CLEAN_AND_JERK("Dos Tiempos (Clean & Jerk)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.78, "%1RM = 140 - 28.5 * PV"),

    JERK("Envión (Jerk)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 142 - 28.0 * PV"),
    PUSH_JERK("Push Jerk", VBTExerciseFamily.DLP_OLYMPIC, true, 0.70, "%1RM = 144 - 28.5 * PV"),
    SPLIT_JERK("Split Jerk", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 140 - 28.0 * PV"),
    POWER_JERK("Power Jerk", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 143 - 28.5 * PV"),
    PUSH_PRESS("Push Press", VBTExerciseFamily.DLP_OLYMPIC, true, 0.40, "%1RM = 136 - 27.5 * PV"),
    BTN_PUSH_PRESS("Push Press tras Nuca (Behind the Neck Push Press)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.40, "%1RM = 136 - 27.5 * PV"),
    BTN_JERK("Envión tras Nuca (Behind the Neck Jerk)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.75, "%1RM = 141 - 28.0 * PV"),

    OVERHEAD_SQUAT("Sentadilla de Arranque (Overhead Squat)", VBTExerciseFamily.DLP_OLYMPIC, false, 0.35, "%1RM = -65.0 * VM + 120.0"),
    SNATCH_BALANCE("Balance de Arranque (Snatch Balance)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 142 - 28.0 * PV"),
    DROP_SNATCH("Drop Snatch", VBTExerciseFamily.DLP_OLYMPIC, true, 0.80, "%1RM = 142 - 28.0 * PV"),
    SOTS_PRESS("Sots Press", VBTExerciseFamily.DLP_OLYMPIC, false, 0.35, "%1RM = -68.0 * VM + 118.0"),

    SNATCH_PULL("Tirones de Arranque (Snatch Pulls)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.65, "%1RM = 140 - 27.5 * PV"),
    SNATCH_HIGH_PULL("Tirón Alto de Arranque (Snatch High Pull)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.70, "%1RM = 144 - 28.0 * PV"),
    HANG_SNATCH_PULL("Tirón de Arranque Colgante (Hang Snatch Pull)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.65, "%1RM = 139 - 27.0 * PV"),
    BLOCK_SNATCH_PULL("Tirón de Arranque desde Bloques (Block Snatch Pull)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.65, "%1RM = 139 - 27.0 * PV"),

    CLEAN_PULL("Tirones de Cargada (Clean Pulls)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.60, "%1RM = 138 - 27.0 * PV"),
    CLEAN_HIGH_PULL("Tirón Alto de Cargada (Clean High Pull)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.65, "%1RM = 142 - 27.5 * PV"),
    HANG_CLEAN_PULL("Tirón de Cargada Colgante (Hang Clean Pull)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.60, "%1RM = 137 - 27.0 * PV"),
    BLOCK_CLEAN_PULL("Tirón de Cargada desde Bloques (Block Clean Pull)", VBTExerciseFamily.DLP_OLYMPIC, true, 0.60, "%1RM = 137 - 27.0 * PV"),

    // ── LEY DE HILL (Fuerza Tradicional) -> Métrica: Velocidad Media (VM) ──
    SQUAT("Sentadilla", VBTExerciseFamily.HILL_TRADITIONAL, false, 0.30, "%1RM = -59.93 * VM + 128.2"),
    BENCH_PRESS("Press Banca", VBTExerciseFamily.HILL_TRADITIONAL, false, 0.15, "%1RM = -84.07 * VM + 115.6"),
    DEADLIFT("Peso Muerto", VBTExerciseFamily.HILL_TRADITIONAL, false, 0.15, "%1RM = -56.34 * VM + 110.2"),
    HIP_THRUST("Hip Thrust / Militar", VBTExerciseFamily.HILL_TRADITIONAL, false, 0.22, "%1RM = -62.50 * VM + 118.0");

    val shortName: String
        get() = displayName.substringBefore(" (").trim()

    /**
     * Calcula el porcentaje del 1RM (%1RM) según la métrica correspondiente (PV para DLP, VM para Hill).
     */
    fun calculatePercentOneRM(peakVelocity: Double, meanVelocity: Double): Double {
        return when (this) {
            SNATCH_FLOOR -> (145.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)
            HANG_SNATCH -> (142.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)
            BELOW_KNEE_HANG_SNATCH -> (143.0 - (27.5 * peakVelocity)).coerceIn(35.0, 98.0)
            POWER_SNATCH -> (150.0 - (29.0 * peakVelocity)).coerceIn(35.0, 98.0)
            HANG_POWER_SNATCH -> (146.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            BLOCK_SNATCH -> (143.0 - (27.5 * peakVelocity)).coerceIn(35.0, 98.0)
            BLOCK_POWER_SNATCH -> (147.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)
            MUSCLE_SNATCH -> (140.0 - (26.5 * peakVelocity)).coerceIn(35.0, 98.0)

            CLEAN_FLOOR -> (140.0 - (29.0 * peakVelocity)).coerceIn(35.0, 98.0)
            HANG_CLEAN -> (138.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            BELOW_KNEE_HANG_CLEAN -> (139.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)
            POWER_CLEAN -> (145.0 - (29.5 * peakVelocity)).coerceIn(35.0, 98.0)
            HANG_POWER_CLEAN -> (142.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)
            BLOCK_CLEAN -> (139.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            BLOCK_POWER_CLEAN -> (143.0 - (29.0 * peakVelocity)).coerceIn(35.0, 98.0)
            MUSCLE_CLEAN -> (136.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)

            CLEAN_AND_JERK -> (140.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)

            JERK -> (142.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            PUSH_JERK -> (144.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)
            SPLIT_JERK -> (140.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            POWER_JERK -> (143.0 - (28.5 * peakVelocity)).coerceIn(35.0, 98.0)
            PUSH_PRESS -> (136.0 - (27.5 * peakVelocity)).coerceIn(35.0, 98.0)
            BTN_PUSH_PRESS -> (136.0 - (27.5 * peakVelocity)).coerceIn(35.0, 98.0)
            BTN_JERK -> (141.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)

            OVERHEAD_SQUAT -> ((-65.0 * meanVelocity) + 120.0).coerceIn(30.0, 95.0)
            SNATCH_BALANCE -> (142.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            DROP_SNATCH -> (142.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            SOTS_PRESS -> ((-68.0 * meanVelocity) + 118.0).coerceIn(30.0, 95.0)

            SNATCH_PULL -> (140.0 - (27.5 * peakVelocity)).coerceIn(35.0, 98.0)
            SNATCH_HIGH_PULL -> (144.0 - (28.0 * peakVelocity)).coerceIn(35.0, 98.0)
            HANG_SNATCH_PULL -> (139.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)
            BLOCK_SNATCH_PULL -> (139.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)

            CLEAN_PULL -> (138.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)
            CLEAN_HIGH_PULL -> (142.0 - (27.5 * peakVelocity)).coerceIn(35.0, 98.0)
            HANG_CLEAN_PULL -> (137.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)
            BLOCK_CLEAN_PULL -> (137.0 - (27.0 * peakVelocity)).coerceIn(35.0, 98.0)

            SQUAT -> ((-59.93 * meanVelocity) + 128.2).coerceIn(30.0, 95.0)
            BENCH_PRESS -> ((-84.07 * meanVelocity) + 115.6).coerceIn(30.0, 95.0)
            DEADLIFT -> ((-56.34 * meanVelocity) + 110.2).coerceIn(30.0, 95.0)
            HIP_THRUST -> ((-62.50 * meanVelocity) + 118.0).coerceIn(30.0, 95.0)
        }
    }

    /**
     * Proyecta el 1RM estimado en Kilogramos a partir de la carga levantada y las velocidades medidas.
     */
    fun estimateOneRM(loadKg: Double, peakVelocity: Double, meanVelocity: Double): Double {
        val pct = calculatePercentOneRM(peakVelocity, meanVelocity)
        if (pct <= 0.0) return loadKg
        return (loadKg / (pct / 100.0)).coerceAtLeast(loadKg)
    }
}

object VBTAnalyzer {

    private var previousY: Float? = null
    private var previousTime: Long? = null
    private var previousDisplacementY: Float = 25f
    private var handsFarFrameCount: Int = 0
    
    // Suavizado de velocidad para mitigar el ruido de IA a altos FPS
    private val velocityHistory = LinkedList<Double>()
    private const val WINDOW_SIZE = 5

    // Ratio de calibración (metros por píxel):
    const val DEFAULT_PX_TO_METERS = 0.0024
    var pxToMetersRatio: Double = DEFAULT_PX_TO_METERS

    var currentPeakVelocity: Double = 0.0
        private set
    var currentMeanVelocity: Double = 0.0
        private set

    // Métrica principal seleccionada
    var primaryMetric: VBTVelocityMetric = VBTVelocityMetric.MEAN_VELOCITY

    // Tipo de ejercicio configurado
    var exerciseType: VBTExerciseType = VBTExerciseType.FLOOR_PULL

    // Punto de partida / Origen del movimiento
    var originBaselineX: Float? = null
        private set
    var originBaselineY: Float? = null
        private set

    // Punto de inflexión más bajo (el fondo de la sentadilla o pecho en banca)
    var lowestInflectionX: Float? = null
        private set
    var lowestInflectionY: Float? = null
        private set

    // Posición actual de la barra en coordenadas del frame
    var currentBarX: Float? = null
        private set
    var currentBarY: Float? = null
        private set

    // Trayectoria completa y del rep actual
    val barPath = mutableListOf<BarPathPoint>()
    val currentRepPath = mutableListOf<BarPathPoint>()
    val lastCompletedRepPath = mutableListOf<BarPathPoint>()
    
    var maxHorizontalDeviationPx: Float = 0f
        private set

    // Detección de repeticiones en vivo y umbrales (Cutoff & % Loss)
    val currentSetReps = mutableListOf<RepResult>()
    var cutoffVelocityMps: Double? = null
    var cutoffLossPercent: Double? = null
    var onRepCompleted: ((RepResult) -> Unit)? = null

    // Filtros biomecánicos estrictos para evitar que acomodos de barra o movimientos suaves canten velocidad:
    // - VELOCITY_DEADBAND = 0.25 m/s (ignora ajustes lentos de postura o manos)
    // - MIN_REP_ROM_METERS = 0.26 m (26 cm mínimos de recorrido vertical real para contar repetición)
    // - MIN_REP_PEAK_VELOCITY = 0.42 m/s (velocidad pico mínima de un levantamiento real)
    // - MIN_CONCENTRIC_DURATION_MS = 240 ms (evita chispazos o cruces rápidos frente al lente)
    private const val VELOCITY_DEADBAND = 0.25
    private const val MIN_REP_ROM_METERS = 0.26
    private const val MIN_REP_PEAK_VELOCITY = 0.42
    private const val MIN_CONCENTRIC_DURATION_MS = 240L
    private var consecutiveUpwardFrames = 0

    var isConcentricActive = false
        private set
    var isEccentricActive = false
        private set

    private var concentricStartY = 0f
    private var concentricMinY = Float.MAX_VALUE
    private var concentricStartTimeMs = 0L
    private var currentRepPeakVelocity = 0.0
    private val currentRepConcentricVelocities = mutableListOf<Double>()
    private var lastUpwardTimeMs = 0L

    fun setManualOrigin() {
        originBaselineX = currentBarX
        originBaselineY = currentBarY
        lowestInflectionX = currentBarX
        lowestInflectionY = currentBarY
    }

    // Calibración y Anclaje Óptico del Disco Olímpico (Kinovea)
    var calibratedPlateCenter: Offset? = null
        private set
    var calibratedPlateRadius: Float? = null
        private set
    private var initialHandsPos: Offset? = null
    private var plateOffset: Offset? = null

    /**
     * Fija el disco olímpico calibrado como punto de anclaje de la trayectoria.
     */
    fun setCalibratedPlate(center: Offset, radius: Float) {
        calibratedPlateCenter = center
        calibratedPlateRadius = radius
        val diameterPx = radius * 2f
        if (diameterPx > 5f) {
            pxToMetersRatio = 0.45 / diameterPx.toDouble()
        }
        // No congelamos la línea de salida en la posición de calibración,
        // para permitir que el levantador inicie desde el suelo, colgante o rack libremente.
        originBaselineX = null
        originBaselineY = null
        lowestInflectionX = null
        lowestInflectionY = null
        currentBarX = center.x
        currentBarY = center.y
        initialHandsPos = null
        plateOffset = null
        previousDisplacementY = 25f
        handsFarFrameCount = 0
    }

    /**
     * Calibra la relación de píxeles a metros a partir del diámetro medido de un disco olímpico
     * (Estándar internacional = 0.45 metros / 450 mm)
     */
    fun calibrateWithPlate(plateDiameterMeters: Double = 0.45, plateHeightPx: Float): Double {
        if (plateHeightPx > 10f) {
            pxToMetersRatio = plateDiameterMeters / plateHeightPx.toDouble()
        }
        return pxToMetersRatio
    }

    fun getHorizontalDeviationMeters(): Double {
        return maxHorizontalDeviationPx * pxToMetersRatio
    }

    fun reset() {
        previousY = null
        previousTime = null
        velocityHistory.clear()
        currentPeakVelocity = 0.0
        currentMeanVelocity = 0.0
        barPath.clear()
        currentRepPath.clear()
        lastCompletedRepPath.clear()
        maxHorizontalDeviationPx = 0f
        currentSetReps.clear()
        isConcentricActive = false
        isEccentricActive = false
        currentRepPeakVelocity = 0.0
        currentRepConcentricVelocities.clear()
        lastUpwardTimeMs = 0L
        consecutiveUpwardFrames = 0
        initialHandsPos = null
        plateOffset = null
        previousDisplacementY = 25f
        handsFarFrameCount = 0
        calibratedPlateCenter = null
        calibratedPlateRadius = null
        currentBarX = null
        currentBarY = null
        originBaselineX = null
        originBaselineY = null
        lowestInflectionX = null
        lowestInflectionY = null
    }

    fun resetSet() {
        currentSetReps.clear()
        isConcentricActive = false
        isEccentricActive = false
        currentRepPeakVelocity = 0.0
        currentRepConcentricVelocities.clear()
        lastUpwardTimeMs = 0L
        barPath.clear()
        currentRepPath.clear()
        lastCompletedRepPath.clear()
        maxHorizontalDeviationPx = 0f
        lowestInflectionX = null
        lowestInflectionY = null
    }

    /**
     * Procesa la pose en tiempo real con invariantes biomecánicas de halterofilia.
     * Incorpora:
     * 1. Invariante de bloqueo sobre la cabeza (Catch Overhead).
     * 2. Invariante antigravedad de ascenso concéntrico (impide caídas a rodillas en tirones rápidos).
     * 3. Continuación balística por inercia física ante desenfoque de movimiento (Motion Blur).
     */
    fun processPose(
        pose: Pose,
        timestampMs: Long
    ): Double? {
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)

        val lConf = leftWrist?.inFrameLikelihood ?: 0f
        val rConf = rightWrist?.inFrameLikelihood ?: 0f

        // 1. Invariante de Bloqueo Sobre la Cabeza (Catch de Snatch / Jerk):
        // Si los codos están más altos que los hombros, el atleta tiene los brazos extendidos arriba.
        val isLeftOverhead = leftElbow != null && leftShoulder != null &&
                leftElbow.inFrameLikelihood > 0.35f && leftShoulder.inFrameLikelihood > 0.35f &&
                leftElbow.position.y < leftShoulder.position.y
        val isRightOverhead = rightElbow != null && rightShoulder != null &&
                rightElbow.inFrameLikelihood > 0.35f && rightShoulder.inFrameLikelihood > 0.35f &&
                rightElbow.position.y < rightShoulder.position.y

        var rawHandsPos: Offset? = null

        if (isLeftOverhead || isRightOverhead) {
            // El atleta está sosteniendo la barra arriba de la cabeza.
            // Es anatómicamente imposible que las manos estén en la cadera o rodilla.
            val overheadY: Float
            val overheadX: Float
            if (isLeftOverhead && isRightOverhead) {
                val avgElbowY = (leftElbow!!.position.y + rightElbow!!.position.y) / 2f
                val avgShoulderY = (leftShoulder!!.position.y + rightShoulder!!.position.y) / 2f
                val armLen = abs(avgShoulderY - avgElbowY).coerceAtLeast(35f)
                overheadY = avgElbowY - (armLen * 0.85f)
                overheadX = (leftElbow.position.x + rightElbow.position.x) / 2f
            } else if (isLeftOverhead) {
                val armLen = abs(leftShoulder!!.position.y - leftElbow!!.position.y).coerceAtLeast(35f)
                overheadY = leftElbow.position.y - (armLen * 0.85f)
                overheadX = leftElbow.position.x
            } else {
                val armLen = abs(rightShoulder!!.position.y - rightElbow!!.position.y).coerceAtLeast(35f)
                overheadY = rightElbow!!.position.y - (armLen * 0.85f)
                overheadX = rightElbow.position.x
            }

            val leftWristUp = leftWrist != null && lConf > 0.30f && leftWrist.position.y < (leftShoulder?.position?.y ?: Float.MAX_VALUE)
            val rightWristUp = rightWrist != null && rConf > 0.30f && rightWrist.position.y < (rightShoulder?.position?.y ?: Float.MAX_VALUE)

            rawHandsPos = when {
                leftWristUp && rightWristUp -> Offset((leftWrist!!.position.x + rightWrist!!.position.x) / 2f, (leftWrist.position.y + rightWrist.position.y) / 2f)
                leftWristUp -> Offset(leftWrist!!.position.x, leftWrist.position.y)
                rightWristUp -> Offset(rightWrist!!.position.x, rightWrist.position.y)
                else -> Offset(overheadX, overheadY)
            }
        } else {
            // Fase de suelo, primer tirón o segundo tirón
            val isLeftValid = isLimbPlausible(leftWrist, leftElbow, leftShoulder, leftHip, leftKnee)
            val isRightValid = isLimbPlausible(rightWrist, rightElbow, rightShoulder, rightHip, rightKnee)

            rawHandsPos = when {
                isLeftValid && isRightValid -> {
                    if (lConf > rConf + 0.3f) Offset(leftWrist!!.position.x, leftWrist.position.y)
                    else if (rConf > lConf + 0.3f) Offset(rightWrist!!.position.x, rightWrist.position.y)
                    else Offset((leftWrist!!.position.x + rightWrist!!.position.x) / 2f, (leftWrist.position.y + rightWrist.position.y) / 2f)
                }
                isLeftValid -> Offset(leftWrist!!.position.x, leftWrist.position.y)
                isRightValid -> Offset(rightWrist!!.position.x, rightWrist.position.y)
                else -> null
            }
        }

        // 2. Continuación por Inercia Cinemática si las manos sufren desenfoque por velocidad
        if (rawHandsPos == null) {
            val cY = currentBarY
            val pY = previousY
            val pT = previousTime
            if (cY != null && pY != null && pT != null) {
                val dt = (timestampMs - pT) / 1000.0
                if (dt in 0.005..0.25 && isConcentricActive) {
                    val prevVyPx = (pY - cY) / dt.toFloat()
                    val upwardStep = (prevVyPx * dt.toFloat()).coerceIn(12f, 75f)
                    val nextY = (cY - upwardStep).coerceAtLeast(10f)
                    currentBarY = nextY
                    val point = BarPathPoint(currentBarX ?: 0f, nextY, currentRepPeakVelocity, timestampMs)
                    barPath.add(point)
                    currentRepPath.add(point)
                    previousDisplacementY = upwardStep
                    previousY = nextY
                    previousTime = timestampMs
                    return currentRepPeakVelocity
                }
            }
            return null
        }

        // 3. Sincronización y Anclaje con el Disco Calibrado
        if (calibratedPlateCenter != null) {
            val disc = calibratedPlateCenter!!
            val radius = calibratedPlateRadius ?: 100f
            if (initialHandsPos == null) {
                val dyToDisc = abs(rawHandsPos.y - disc.y)
                val dxToDisc = abs(rawHandsPos.x - disc.x)
                if (dyToDisc < radius * 3.0f && dxToDisc < radius * 5.5f) {
                    initialHandsPos = rawHandsPos
                    plateOffset = Offset(disc.x - rawHandsPos.x, disc.y - rawHandsPos.y)
                } else {
                    handsFarFrameCount++
                    if (handsFarFrameCount > 30) {
                        initialHandsPos = rawHandsPos
                        plateOffset = Offset(disc.x - rawHandsPos.x, 0f)
                    } else {
                        currentBarX = disc.x
                        currentBarY = disc.y
                        return 0.0
                    }
                }
            }
        }

        var candidateBarX = if (plateOffset != null) rawHandsPos.x + plateOffset!!.x else rawHandsPos.x
        var candidateBarY = if (plateOffset != null) rawHandsPos.y + plateOffset!!.y else rawHandsPos.y

        // 4. Invariante Cinemática de Continuidad:
        // Si el atleta está bajando la barra deliberadamente (ej. descendiendo al piso para un clean),
        // liberamos la fase concéntrica previa para permitir todo el rango de movimiento hacia abajo.
        val prevY = previousY
        val prevT = previousTime
        if (prevY != null && prevT != null) {
            val dt = (timestampMs - prevT) / 1000.0
            if (dt in 0.005..0.30) {
                val dy = candidateBarY - prevY
                if (dy > 12f) {
                    isConcentricActive = false
                    isEccentricActive = true
                }
            }
        }

        if (prevY != null && prevY > candidateBarY) {
            previousDisplacementY = prevY - candidateBarY
        }

        val barX = candidateBarX
        val barY = candidateBarY

        return processBarPosition(barX, barY, timestampMs)
    }

    /**
     * Finaliza y registra la repetición concéntrica activa si cumple los umbrales biomecánicos mínimos
     * (ROM >= 12 cm, Velocidad Pico >= 0.25 m/s, Duración >= 180 ms).
     */
    private fun finalizeActiveConcentricRep(timestampMs: Long) {
        if (!isConcentricActive) return
        val activeDuration = maxOf(lastUpwardTimeMs - concentricStartTimeMs, timestampMs - concentricStartTimeMs)
        val romMeters = (concentricStartY - concentricMinY) * pxToMetersRatio

        if (romMeters >= MIN_REP_ROM_METERS &&
            currentRepPeakVelocity >= MIN_REP_PEAK_VELOCITY &&
            activeDuration >= 180L
        ) {
            val repNum = currentSetReps.size + 1
            val meanVelocity = if (currentRepConcentricVelocities.isNotEmpty()) {
                currentRepConcentricVelocities.average()
            } else {
                currentRepPeakVelocity * 0.72
            }
            currentMeanVelocity = meanVelocity

            val primaryMetricValue = if (primaryMetric == VBTVelocityMetric.MEAN_VELOCITY) {
                meanVelocity
            } else {
                currentRepPeakVelocity
            }

            val bestRepMetric = currentSetReps.maxOfOrNull {
                if (primaryMetric == VBTVelocityMetric.MEAN_VELOCITY) it.meanVelocity else it.peakVelocity
            } ?: primaryMetricValue

            val loss = if (bestRepMetric > 0) ((bestRepMetric - primaryMetricValue) / bestRepMetric) * 100.0 else 0.0

            var isCutoff = false
            if (cutoffVelocityMps != null && primaryMetricValue < cutoffVelocityMps!!) {
                isCutoff = true
            }
            if (cutoffLossPercent != null && loss >= cutoffLossPercent!! && currentSetReps.isNotEmpty()) {
                isCutoff = true
            }

            val rep = RepResult(
                repNumber = repNum,
                peakVelocity = currentRepPeakVelocity,
                meanVelocity = meanVelocity,
                romMeters = romMeters,
                isCutoffReached = isCutoff,
                velocityLossPercent = loss.coerceAtLeast(0.0)
            )
            currentSetReps.add(rep)

            lastCompletedRepPath.clear()
            lastCompletedRepPath.addAll(currentRepPath)

            onRepCompleted?.invoke(rep)
        }

        isConcentricActive = false
        currentRepPeakVelocity = 0.0
        currentRepConcentricVelocities.clear()
        currentRepPath.clear()
        consecutiveUpwardFrames = 0
        lowestInflectionY = null
        lowestInflectionX = null
    }

    /**
     * Procesa la posición de la barra/disco en coordenadas del video (Offset nativo).
     * Calcula cinemática, velocidad media/pico, repeticiones concéntricas/excéntricas y trayectoria.
     */
    fun processBarPosition(
        barX: Float,
        barY: Float,
        timestampMs: Long
    ): Double? {
        currentBarX = barX
        currentBarY = barY

        // Auto-establecer o expandir el origen dinámicamente si la barra baja más en el encuadre
        if (originBaselineY == null || (!isConcentricActive && barY > originBaselineY!!)) {
            originBaselineX = barX
            originBaselineY = barY
        }

        if (previousY != null && previousTime != null) {
            val deltaSeconds = (timestampMs - previousTime!!) / 1000.0
            if (deltaSeconds < 0.005) {
                return 0.0
            }

            // Si la barra SUBE, pxMoved > 0. Si BAJA, pxMoved < 0.
            val pxMoved = previousY!! - barY
            val rawDisplacementMeters = pxMoved * pxToMetersRatio
            val rawVelocity = (rawDisplacementMeters / deltaSeconds).coerceIn(-3.5, 4.0)

            // Si veníamos bajando y empezamos a subir con claridad, limpiar muestras negativas del filtro
            // para no amortiguar artificialmente el inicio explosivo del tirón concéntrico
            if (!isConcentricActive && pxMoved > 2.0f && rawVelocity > VELOCITY_DEADBAND) {
                velocityHistory.removeAll { it < 0.0 }
            }

            velocityHistory.addLast(rawVelocity)
            if (velocityHistory.size > WINDOW_SIZE) {
                velocityHistory.removeFirst()
            }
            val smoothedVelocity = velocityHistory.average()
            val filteredVelocity = if (smoothedVelocity > VELOCITY_DEADBAND) smoothedVelocity else 0.0

            // 1. Manejo universal de fase concéntrica (subida / tirón)
            // Exigir al menos 3 frames consecutivos subiendo por encima del deadband antes de activar CONCÉNTRICA
            // para que micro-movimientos o ajustes de posición en el suelo no se marquen como tirón
            if (pxMoved > 2.2f && filteredVelocity > 0.0) {
                consecutiveUpwardFrames++
                if (!isConcentricActive && consecutiveUpwardFrames >= 3) {
                    isConcentricActive = true
                    concentricStartY = lowestInflectionY ?: previousY ?: barY
                    concentricMinY = barY
                    originBaselineY = concentricStartY
                    originBaselineX = lowestInflectionX ?: barX

                    if (currentRepPath.isEmpty()) {
                        currentRepPath.add(BarPathPoint(lowestInflectionX ?: barX, concentricStartY, 0.0, timestampMs))
                    }

                    concentricStartTimeMs = timestampMs
                    currentRepPeakVelocity = 0.0
                    currentRepConcentricVelocities.clear()
                    isEccentricActive = false
                }

                if (!isConcentricActive) {
                    previousY = barY
                    previousTime = timestampMs
                    return 0.0
                }
                lastUpwardTimeMs = timestampMs

                if (barY < concentricMinY) {
                    concentricMinY = barY
                }

                if (filteredVelocity > currentPeakVelocity) {
                    currentPeakVelocity = filteredVelocity
                }
                if (filteredVelocity > currentRepPeakVelocity) {
                    currentRepPeakVelocity = filteredVelocity
                }

                currentRepConcentricVelocities.add(filteredVelocity)
                currentMeanVelocity = currentRepConcentricVelocities.average()

                val point = BarPathPoint(barX, barY, filteredVelocity, timestampMs)
                barPath.add(point)
                currentRepPath.add(point)

                val minX = currentRepPath.minOfOrNull { it.x } ?: barX
                val maxX = currentRepPath.maxOfOrNull { it.x } ?: barX
                maxHorizontalDeviationPx = maxX - minX

                previousY = barY
                previousTime = timestampMs
                return filteredVelocity
            }

            // 2. Manejo universal de fase descendente / excéntrica (bajada al piso, recepción o descenso entre reps)
            if (pxMoved < -1.5f) {
                consecutiveUpwardFrames = 0
                if (isConcentricActive) {
                    // Verificar si ya alcanzó el ápice y comenzó a bajar de verdad (>= 3.5 cm desde el punto más alto o > 150 ms sin subir)
                    val dropFromApexMeters = (barY - concentricMinY) * pxToMetersRatio
                    if (dropFromApexMeters >= 0.035 || (timestampMs - lastUpwardTimeMs) >= 150L) {
                        finalizeActiveConcentricRep(timestampMs)
                    }
                }

                if (!isConcentricActive) {
                    if (!isEccentricActive) {
                        currentRepPath.clear()
                    }
                    isEccentricActive = true
                    if (lowestInflectionY == null || barY > lowestInflectionY!!) {
                        lowestInflectionY = barY
                        lowestInflectionX = barX
                        originBaselineY = barY
                        originBaselineX = barX
                    }
                    val eccentricVelocity = kotlin.math.abs(smoothedVelocity)
                    val point = BarPathPoint(barX, barY, eccentricVelocity, timestampMs)
                    barPath.add(point)
                    currentRepPath.add(point)
                }
            }

            // 3. Finalización por pausa / bloqueo arriba (sin descenso inmediato)
            if (isConcentricActive && (timestampMs - lastUpwardTimeMs >= 220L)) {
                finalizeActiveConcentricRep(timestampMs)
                isEccentricActive = false
            }
        }

        previousY = barY
        previousTime = timestampMs
        return 0.0
    }

    /**
     * Valida si un landmark de muñeca es anatómicamente plausible para evitar
     * que la IA confunda manos con rodillas o caderas durante movimientos explosivos.
     */
    private fun isLimbPlausible(
        wrist: PoseLandmark?,
        elbow: PoseLandmark?,
        shoulder: PoseLandmark?,
        hip: PoseLandmark?,
        knee: PoseLandmark?
    ): Boolean {
        if (wrist == null || wrist.inFrameLikelihood < 0.28f) return false

        // Validación biomecánica de proporciones del brazo (antebrazo vs brazo superior)
        if (elbow != null && shoulder != null && elbow.inFrameLikelihood > 0.30f && shoulder.inFrameLikelihood > 0.30f) {
            val upperArm = kotlin.math.hypot(elbow.position.x - shoulder.position.x, elbow.position.y - shoulder.position.y)
            val forearm = kotlin.math.hypot(wrist.position.x - elbow.position.x, wrist.position.y - elbow.position.y)
            if (upperArm > 25f) {
                // Si la distancia muñeca-codo excede 1.8x o es menor a 0.15x del brazo, es una alucinación
                if (forearm > upperArm * 1.80f || forearm < upperArm * 0.15f) {
                    return false
                }
            }
        }

        // Si el codo está por encima de la cadera (tirón alto o barra en el pecho)
        if (elbow != null && hip != null && elbow.inFrameLikelihood > 0.30f && hip.inFrameLikelihood > 0.30f) {
            if (elbow.position.y < hip.position.y) {
                val kneeY = knee?.position?.y ?: (hip.position.y + 110f)
                // Es anatómicamente imposible que la muñeca esté debajo de la rodilla si el codo está en el pecho
                if (wrist.position.y > kneeY) {
                    return false
                }
            }
        }

        return true
    }
}
