package com.speedtracker.domain.model

data class Session(
    val id: String,
    val userId: String,
    val activityType: String, // "JUMP" or "VBT"
    val result: Double, // Altura en metros o Velocidad Pico en m/s
    val timestamp: Long,
    val powerWatts: Double? = null,
    val powerWattsPerKg: Double? = null,
    val horizontalDeviationCm: Double? = null,
    // Nuevas métricas científicas comerciales:
    val meanVelocity: Double? = null,
    val loadKg: Double? = null,
    val jumpType: String? = null, // "CMJ", "SJ", "DROP_JUMP"
    val contactTimeMs: Long? = null,
    val rsi: Double? = null,
    val vmpVelocity: Double? = null,
    val concentricDurationMs: Long? = null,
    val timeToPeakMs: Long? = null,
    val rfdNPerSec: Int? = null,
    val pdfReportPath: String? = null,
    val videoExportPath: String? = null
)
