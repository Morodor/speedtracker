package com.speedtracker.presentation.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.speedtracker.core.update.AppReleaseInfo
import com.speedtracker.core.update.AppUpdateManager
import com.speedtracker.core.update.UpdateCheckResult
import com.speedtracker.presentation.components.AppUpdateDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.pose.PoseLandmark
import com.speedtracker.core.audio.SpeedAudioCoach
import com.speedtracker.core.export.VideoFrameTrackingPoint
import com.speedtracker.core.export.VideoOverlayExporter
import com.speedtracker.core.math.*
import com.speedtracker.core.ml.PoseAnalyzer
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
import com.speedtracker.presentation.components.ExportReportDataDialog
import com.speedtracker.presentation.video.KinoveaCircleReticleCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    userRepository: UserRepository,
    isQuickMode: Boolean = false,
    onCloseClick: () -> Unit,
    onNavigateToVideoAnalysis: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val audioCoach = remember { SpeedAudioCoach(context) }
    DisposableEffect(Unit) {
        onDispose {
            audioCoach.shutdown()
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
        VBTAnalyzer.reset()
    }

    var vbtVelocityResult by remember { mutableStateOf<Double?>(null) }
    var isAthleteInFrame by remember { mutableStateOf(false) }

    // Plantillas de Referencia de Encuadre VBT (Opcional)
    var framingGuideType by remember { mutableStateOf(FramingGuideType.FLOOR_BAR) }
    var isFramingGuideVisible by remember { mutableStateOf(false) }

    // Métrica Principal: En Halterofilia la métrica clave es Velocidad Pico (PV)
    val vbtPrimaryMetric = VBTVelocityMetric.PEAK_VELOCITY

    // Dimensiones del frame de la cámara para proyectar el tracker
    var cameraFrameWidth by remember { mutableIntStateOf(0) }
    var cameraFrameHeight by remember { mutableIntStateOf(0) }
    var livePathPoints by remember { mutableStateOf<List<BarPathPoint>>(emptyList()) }

    // Estado de Repeticiones y Umbrales VBT (% Pérdida de Velocidad y Corte Absoluto)
    var repsList by remember { mutableStateOf<List<RepResult>>(emptyList()) }
    var isCutoffAlarmActive by remember { mutableStateOf(false) }
    var cutoffAlertMessage by remember { mutableStateOf("🚨 ¡ALTO! UMBRAL DE FATIGA ALCANZADO") }
    var showThresholdDialog by remember { mutableStateOf(false) }

    // Sistema de Actualización de la App (In-App OTA via GitHub Releases)
    var availableAppUpdate by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var showRepoEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val updateRes = AppUpdateManager.checkForUpdate(context)
        if (updateRes is UpdateCheckResult.UpdateAvailable) {
            availableAppUpdate = updateRes.release
        }
    }

    // Control por % de Pérdida de Velocidad (Fatiga VBT Intraserie)
    var isLossThresholdEnabled by remember { mutableStateOf(true) } // Activo por defecto en 20%
    var targetLossPercent by remember { mutableDoubleStateOf(20.0) } // 10%, 15%, 20%, 30%

    // Control adicional por Velocidad Mínima Absoluta (m/s)
    var minVelocityThreshold by remember { mutableDoubleStateOf(0.60) }
    var isMinVelocityEnabled by remember { mutableStateOf(false) }

    // ── RETÍCULA TÁCTIL PARA CALIBRAR EL DISCO (45 CM) ──
    var circleCenterScreen by remember { mutableStateOf<Offset?>(null) }
    var circleRadiusPx by remember { mutableFloatStateOf(85f) }
    var isPlateCalibrated by remember { mutableStateOf(false) }
    var isCalibratingPlate by remember { mutableStateOf(true) } // Visible al inicio para ubicar el disco

    var showOneRMDialog by remember { mutableStateOf(false) }
    var currentSetLoadKg by remember { mutableStateOf("70") }

    // Grabación de Series en Vivo (CameraX VideoCapture)
    var isRecording by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var recordingDurationSeconds by remember { mutableIntStateOf(0) }
    var recordingStartTimestampMs by remember { mutableLongStateOf(0L) }
    val liveRecordedTrackingPoints = remember { mutableListOf<VideoFrameTrackingPoint>() }
    var isExportingVideo by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    var lastRecordedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var showPostRecordDialog by remember { mutableStateOf(false) }
    var showExportDataDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSeconds = 0
            while (isRecording) {
                delay(1000)
                recordingDurationSeconds++
            }
        }
    }

    val activeUser = remember { userRepository.getActiveUser() }

    // Sincronización de parámetros con VBTAnalyzer
    LaunchedEffect(isLossThresholdEnabled, targetLossPercent, isMinVelocityEnabled, minVelocityThreshold) {
        VBTAnalyzer.exerciseType = VBTExerciseType.FLOOR_PULL
        VBTAnalyzer.primaryMetric = vbtPrimaryMetric
        VBTAnalyzer.cutoffVelocityMps = if (isMinVelocityEnabled) minVelocityThreshold else null
        VBTAnalyzer.cutoffLossPercent = if (isLossThresholdEnabled) targetLossPercent else null

        VBTAnalyzer.onRepCompleted = { rep ->
            repsList = VBTAnalyzer.currentSetReps.toList()
            if (rep.isCutoffReached) {
                isCutoffAlarmActive = true
                cutoffAlertMessage = if (isLossThresholdEnabled && rep.velocityLossPercent >= targetLossPercent) {
                    "🚨 ¡LÍMITE ALCANZADO! Pérdida del ${rep.velocityLossPercent.toInt()}% (Objetivo: ${targetLossPercent.toInt()}%)"
                } else {
                    "🚨 ¡ALTO! VELOCIDAD MÍNIMA ALCANZADA (${String.format(Locale.US, "%.2f", minVelocityThreshold)} m/s)"
                }
                audioCoach.speakRepFeedback(
                    velocity = rep.peakVelocity,
                    lossPercent = rep.velocityLossPercent,
                    isCutoffReached = true,
                    targetLossPercent = if (isLossThresholdEnabled) targetLossPercent else null
                )
            } else {
                audioCoach.speakRepFeedback(
                    velocity = rep.peakVelocity,
                    lossPercent = rep.velocityLossPercent,
                    isCutoffReached = false
                )
            }
        }
    }

    // Función auxiliar para calibrar disco de 45 cm
    fun applyPlateCalibration(boxW: Float, boxH: Float) {
        val center = circleCenterScreen ?: Offset(boxW / 2f, boxH * 0.62f)
        if (cameraFrameWidth > 0 && cameraFrameHeight > 0) {
            val scale = maxOf(boxW / cameraFrameWidth.toFloat(), boxH / cameraFrameHeight.toFloat())
            val offX = (boxW - cameraFrameWidth * scale) / 2f
            val offY = (boxH - cameraFrameHeight * scale) / 2f
            val frameX = (center.x - offX) / scale
            val frameY = (center.y - offY) / scale
            val frameR = circleRadiusPx / scale
            VBTAnalyzer.setCalibratedPlate(Offset(frameX, frameY), frameR)
        } else {
            VBTAnalyzer.setCalibratedPlate(center, circleRadiusPx)
        }
        isPlateCalibrated = true
    }

    val cameraController = remember {
        androidx.camera.view.LifecycleCameraController(context).apply {
            setEnabledUseCases(
                CameraController.IMAGE_ANALYSIS or
                CameraController.IMAGE_CAPTURE or
                CameraController.VIDEO_CAPTURE
            )
            setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(context),
                PoseAnalyzer { pose, frameWidth, frameHeight ->
                    cameraFrameWidth = frameWidth
                    cameraFrameHeight = frameHeight

                    val keyLandmark = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                        ?: pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                    isAthleteInFrame = keyLandmark != null && keyLandmark.inFrameLikelihood > 0.30f

                    val velocity = VBTAnalyzer.processPose(pose, System.currentTimeMillis())
                    vbtVelocityResult = velocity ?: 0.0
                    livePathPoints = if (VBTAnalyzer.isConcentricActive || VBTAnalyzer.isEccentricActive || VBTAnalyzer.currentRepPath.isNotEmpty()) {
                        VBTAnalyzer.currentRepPath.toList()
                    } else {
                        VBTAnalyzer.lastCompletedRepPath.toList()
                    }

                    if (isRecording && VBTAnalyzer.currentBarX != null && VBTAnalyzer.currentBarY != null) {
                        val relTimeMs = System.currentTimeMillis() - recordingStartTimestampMs
                        liveRecordedTrackingPoints.add(
                            VideoFrameTrackingPoint(
                                timestampMs = relTimeMs,
                                x = VBTAnalyzer.currentBarX!!,
                                y = VBTAnalyzer.currentBarY!!,
                                velocityMps = velocity ?: 0.0,
                                isConcentric = VBTAnalyzer.isConcentricActive,
                                isEccentric = VBTAnalyzer.isEccentricActive,
                                currentMeanVelocity = VBTAnalyzer.currentMeanVelocity,
                                currentPeakVelocity = VBTAnalyzer.currentPeakVelocity
                            )
                        )
                    }
                }
            )
        }
    }

    DisposableEffect(hasPermission) {
        if (hasPermission) {
            cameraController.bindToLifecycle(lifecycleOwner)
        }
        onDispose {
            cameraController.unbind()
        }
    }

    fun toggleRecording(boxW: Float = 0f, boxH: Float = 0f) {
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
        } else {
            if (boxW > 0 && boxH > 0 && !isPlateCalibrated) {
                applyPlateCalibration(boxW, boxH)
            }
            isCalibratingPlate = false

            val videoDir = File(context.cacheDir, "videos").apply { mkdirs() }
            val videoFile = File(videoDir, "serie_${System.currentTimeMillis()}.mp4")
            val outputOptions = FileOutputOptions.Builder(videoFile).build()
            val audioConfig = AudioConfig.AUDIO_DISABLED

            recordingStartTimestampMs = System.currentTimeMillis()
            liveRecordedTrackingPoints.clear()

            try {
                activeRecording = cameraController.startRecording(
                    outputOptions,
                    audioConfig,
                    ContextCompat.getMainExecutor(context)
                ) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            if (!recordEvent.hasError()) {
                                lastRecordedVideoUri = Uri.fromFile(videoFile)
                                showPostRecordDialog = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val screenBoxW = constraints.maxWidth.toFloat()
        val screenBoxH = constraints.maxHeight.toFloat()

        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        controller = cameraController
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Guía de alineación opcional
            FramingGuideOverlay(
                selectedType = framingGuideType,
                isVisible = isFramingGuideVisible,
                onTypeSelected = { framingGuideType = it },
                onToggleVisibility = { isFramingGuideVisible = !isFramingGuideVisible }
            )

            // Tracker en Vivo de la Barra, Disco Calibrado y Línea de Trayectoria (Bar Path)
            BarPathLiveOverlay(
                frameWidth = cameraFrameWidth,
                frameHeight = cameraFrameHeight,
                barX = VBTAnalyzer.currentBarX,
                barY = VBTAnalyzer.currentBarY,
                originBaselineY = VBTAnalyzer.lowestInflectionY ?: VBTAnalyzer.originBaselineY,
                lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                exerciseType = VBTExerciseType.FLOOR_PULL,
                velocity = vbtVelocityResult ?: 0.0,
                trajectoryPoints = livePathPoints,
                isConcentric = VBTAnalyzer.isConcentricActive,
                isEccentric = VBTAnalyzer.isEccentricActive
            )

            // ── RETÍCULA TÁCTIL PARA CALIBRAR EL DISCO EN VIVO (OPCIÓN A) ──
            if (isCalibratingPlate && !isRecording) {
                val activeCenter = circleCenterScreen ?: Offset(screenBoxW / 2f, screenBoxH * 0.62f)

                KinoveaCircleReticleCanvas(
                    center = activeCenter,
                    radius = circleRadiusPx,
                    onCenterChange = {
                        circleCenterScreen = it
                        applyPlateCalibration(screenBoxW, screenBoxH)
                    },
                    onRadiusChange = {
                        circleRadiusPx = it
                        applyPlateCalibration(screenBoxW, screenBoxH)
                    }
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Se requiere permiso de cámara para continuar.", color = Color.White)
            }
        }

        // Panel Superior Limpio: Botón Cerrar, Estado y Acciones sin superposición
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Botón Cerrar (Izquierda)
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x880D1219), CircleShape)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 2. Chip Central Compacto de Calibración (Al pulsar abre/cierra el ajuste en el dock)
                Surface(
                    onClick = { isCalibratingPlate = !isCalibratingPlate },
                    color = if (isCalibratingPlate) Color(0x3300FFCC) else Color(0xCC0D1219),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isCalibratingPlate) Color(0xFF00FFCC) else Color(0xFF00FFCC).copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("🎯", fontSize = 12.sp)
                        Text(
                            text = if (isCalibratingPlate) "Ajustando Disco..." else if (isPlateCalibrated) "Disco 45cm ✓" else "Calibrar Disco",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FFCC),
                            fontSize = 11.5.sp
                        )
                    }
                }

                // 3. Acciones Derecha (Umbral y 1RM)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { showThresholdDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (isLossThresholdEnabled || isMinVelocityEnabled) Color(0xFF00FFCC).copy(alpha = 0.22f) else Color(0x880D1219),
                                CircleShape
                            )
                    ) {
                        if (isLossThresholdEnabled) {
                            Text(
                                "-${targetLossPercent.toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF00FFCC)
                            )
                        } else {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Umbral",
                                tint = if (isMinVelocityEnabled) Color(0xFF00FFCC) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = { showOneRMDialog = true },
                        color = Color(0x880D1219),
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF4081).copy(alpha = 0.6f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("1RM", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFFFF4081))
                        }
                    }
                }
            }

            // Alerta visual de fatiga / umbral (Cutoff)
            if (isCutoffAlarmActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    color = Color(0xEEFF1744),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cutoffAlertMessage,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { isCutoffAlarmActive = false }) {
                            Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ── PANEL INFERIOR UNIFICADO: OCUPA EL MISMO ESPACIO PARA CALIBRACIÓN O TELEMETRÍA/REC ──
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            color = Color(0xD90D1219),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.2.dp, if (isCalibratingPlate && !isRecording) Color(0xFF00FFCC) else Color(0xFF263342))
        ) {
            if (isCalibratingPlate && !isRecording) {
                // ── MODO 1: AJUSTE Y CALIBRACIÓN DE DISCO EN EL MISMO DOCK INFERIOR ──
                val mmPerPx = if (circleRadiusPx > 0) 450.0 / (circleRadiusPx * 2f) else 0.0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("🎯", fontSize = 15.sp)
                            Column {
                                Text(
                                    "Calibración de Disco (45 cm)",
                                    color = Color(0xFF00FFCC),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Escala: ${String.format(Locale.US, "%.2f", mmPerPx)} mm/px",
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = { isCalibratingPlate = false },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF202834), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = Color.LightGray,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Controles de Tamaño de Círculo [-] [Slider] [+]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                circleRadiusPx = (circleRadiusPx - 4f).coerceAtLeast(20f)
                                applyPlateCalibration(screenBoxW, screenBoxH)
                            },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF202834),
                                contentColor = Color.White
                            )
                        ) {
                            Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        Slider(
                            value = circleRadiusPx.coerceIn(20f, 220f),
                            onValueChange = {
                                circleRadiusPx = it
                                applyPlateCalibration(screenBoxW, screenBoxH)
                            },
                            valueRange = 20f..220f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00FFCC),
                                activeTrackColor = Color(0xFF00FFCC)
                            )
                        )

                        FilledTonalIconButton(
                            onClick = {
                                circleRadiusPx = (circleRadiusPx + 4f).coerceAtMost(220f)
                                applyPlateCalibration(screenBoxW, screenBoxH)
                            },
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF202834),
                                contentColor = Color(0xFF00FFCC)
                            )
                        ) {
                            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Botón Fijar y pasar a Grabar
                    Button(
                        onClick = {
                            applyPlateCalibration(screenBoxW, screenBoxH)
                            isCalibratingPlate = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Text("Fijar y Grabar ✓", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }
            } else {
                // ── MODO 2: TELEMETRÍA EN VIVO Y CONTROL REC ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Fila Superior: Métrica Principal en Grande (Velocidad Pico PV) + Velocidad Media (VMP)
                    val displayPeak = VBTAnalyzer.currentPeakVelocity.takeIf { it > 0.05 } ?: (vbtVelocityResult ?: 0.0)
                    val displayMean = VBTAnalyzer.currentMeanVelocity

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Velocidad Pico (PV)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "%.2f", displayPeak),
                                color = Color(0xFF00FFCC),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                lineHeight = 34.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.padding(bottom = 2.dp)) {
                                Text("m/s", color = Color(0xFF00FFCC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Surface(
                                    color = Color(0xFF00FFCC).copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        "PICO (PV)",
                                        color = Color(0xFF00FFCC),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        // Velocidad Media Propulsiva (VMP) y Repeticiones
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "VMP: ${String.format(Locale.US, "%.2f", displayMean)} m/s",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Reps en serie: ${repsList.size}",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Chips horizontales compactos de repeticiones realizadas con % de Fatiga
                    if (repsList.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(repsList) { rep ->
                                Surface(
                                    color = if (rep.isCutoffReached) Color(0xCCFF1744) else Color(0xFF18202A),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(0.8.dp, if (rep.isCutoffReached) Color(0xFFFF1744) else Color(0xFF2C394A))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("R${rep.repNumber}:", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            "${String.format(Locale.US, "%.2f", rep.peakVelocity)} m/s",
                                            color = if (rep.isCutoffReached) Color.White else Color(0xFF00FFCC),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        if (rep.repNumber > 1) {
                                            val lossVal = rep.velocityLossPercent.roundToInt()
                                            Text(
                                                text = if (lossVal > 0) "-$lossVal%" else "0%",
                                                color = when {
                                                    rep.isCutoffReached -> Color.Yellow
                                                    lossVal >= 15 -> Color(0xFFFF9800)
                                                    else -> Color(0xFF81C784)
                                                },
                                                fontWeight = FontWeight.Black,
                                                fontSize = 10.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fila Inferior de Acciones: [Nueva] | [● REC Compacto] | [Guardar]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botón Nueva Serie
                        OutlinedButton(
                            onClick = {
                                vbtVelocityResult = null
                                isCutoffAlarmActive = false
                                VBTAnalyzer.resetSet()
                                repsList = emptyList()
                                livePathPoints = emptyList()
                            },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                            border = BorderStroke(1.dp, Color(0xFF334050))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Nueva", fontSize = 11.5.sp)
                        }

                        // Botón REC Más Pequeño y Estilizado
                        Button(
                            onClick = { toggleRecording(screenBoxW, screenBoxH) },
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFFF1744) else Color(0xFFD32F2F),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(19.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(if (isRecording) Color.White else Color.Red, if (isRecording) RoundedCornerShape(2.dp) else CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRecording)
                                    String.format(Locale.US, "%02d:%02d", recordingDurationSeconds / 60, recordingDurationSeconds % 60)
                                else
                                    "REC",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        // Botón Guardar Serie
                        if (!isQuickMode && repsList.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val targetUser = activeUser ?: userRepository.getUsers().firstOrNull()
                                    if (targetUser != null) {
                                        val bestRep = repsList.maxByOrNull { it.peakVelocity }
                                        val res = bestRep?.peakVelocity ?: (vbtVelocityResult ?: 0.0)
                                        val meanVel = bestRep?.meanVelocity
                                        val deviation = VBTAnalyzer.getHorizontalDeviationMeters() * 100.0

                                        userRepository.saveSession(
                                            Session(
                                                id = UUID.randomUUID().toString(),
                                                userId = targetUser.id,
                                                activityType = "VBT",
                                                result = res,
                                                timestamp = System.currentTimeMillis(),
                                                horizontalDeviationCm = deviation,
                                                meanVelocity = meanVel,
                                                loadKg = currentSetLoadKg.toDoubleOrNull(),
                                                vmpVelocity = meanVel
                                            )
                                        )
                                        onCloseClick()
                                    }
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Guardar", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(65.dp))
                        }
                    }
                }
            }
        }

        // Diálogo de Control de Fatiga y Umbrales VBT (% Pérdida de Velocidad y Velocidad Mínima)
        if (showThresholdDialog) {
            AlertDialog(
                onDismissRequest = { showThresholdDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚡", fontSize = 18.sp)
                        Text("Control de Fatiga VBT", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            "Optimiza la serie deteniéndote antes del fallo neuromuscular cuando caiga la velocidad respecto a la mejor repetición.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )

                        // 1. Control por % de Pérdida de Velocidad (Fatiga Intraserie)
                        Surface(
                            color = Color(0xFF1B232D),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isLossThresholdEnabled) Color(0xFF00FFCC) else Color(0xFF2A3644))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Pérdida de Velocidad (% Fatiga)",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            if (isLossThresholdEnabled) "Alcanzar -${targetLossPercent.toInt()}% detiene la serie" else "Desactivado",
                                            color = if (isLossThresholdEnabled) Color(0xFF00FFCC) else Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Switch(
                                        checked = isLossThresholdEnabled,
                                        onCheckedChange = { isLossThresholdEnabled = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFF00FFCC)
                                        )
                                    )
                                }

                                if (isLossThresholdEnabled) {
                                    // Presets científicos recomendados
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            10.0 to "10% Fuerza",
                                            15.0 to "15% Óptimo",
                                            20.0 to "20% Estándar",
                                            30.0 to "30% Máx"
                                        ).forEach { (pct, label) ->
                                            val isSel = targetLossPercent == pct
                                            Surface(
                                                onClick = { targetLossPercent = pct },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSel) Color(0xFF00FFCC) else Color(0xFF26323F),
                                                modifier = Modifier.weight(1f).height(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        label,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSel) Color.Black else Color.LightGray
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Slider para ajuste fino
                                    Text(
                                        "Límite permitido: -${targetLossPercent.toInt()}%",
                                        color = Color(0xFF00FFCC),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )

                                    Slider(
                                        value = targetLossPercent.toFloat(),
                                        onValueChange = { targetLossPercent = it.toDouble().roundToInt().toDouble() },
                                        valueRange = 5f..40f,
                                        steps = 34,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00FFCC),
                                            activeTrackColor = Color(0xFF00FFCC)
                                        )
                                    )
                                }
                            }
                        }

                        // 2. Corte adicional por Velocidad Mínima Absoluta (m/s)
                        Surface(
                            color = Color(0xFF1B232D),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isMinVelocityEnabled) Color(0xFF00E5FF) else Color(0xFF2A3644))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Corte por Velocidad Mínima",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            if (isMinVelocityEnabled) "Detener si cae de ${String.format(Locale.US, "%.2f", minVelocityThreshold)} m/s" else "Desactivado",
                                            color = if (isMinVelocityEnabled) Color(0xFF00E5FF) else Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Switch(
                                        checked = isMinVelocityEnabled,
                                        onCheckedChange = { isMinVelocityEnabled = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFF00E5FF)
                                        )
                                    )
                                }

                                if (isMinVelocityEnabled) {
                                    Text(
                                        "Velocidad mínima: ${String.format(Locale.US, "%.2f", minVelocityThreshold)} m/s",
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )

                                    Slider(
                                        value = minVelocityThreshold.toFloat(),
                                        onValueChange = { minVelocityThreshold = it.toDouble() },
                                        valueRange = 0.30f..1.50f,
                                        steps = 23,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00E5FF),
                                            activeTrackColor = Color(0xFF00E5FF)
                                        )
                                    )
                                }
                            }
                        }

                        // 3. Sistema de Auto-Actualización (GitHub Releases)
                        Surface(
                            color = Color(0xFF1B232D),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF2A3644))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Actualización de la App",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            "Versión actual: v${AppUpdateManager.getAppVersionName(context)}",
                                            color = Color(0xFF00FFCC),
                                            fontSize = 11.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { showRepoEditDialog = true }
                                        ) {
                                            Text(
                                                "GitHub: ${AppUpdateManager.getRepoOwner(context)}/${AppUpdateManager.getRepoName(context)}",
                                                color = Color(0xFF64748B),
                                                fontSize = 9.5.sp
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Editar repositorio",
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                isCheckingUpdates = true
                                                val res = AppUpdateManager.checkForUpdate(context)
                                                isCheckingUpdates = false
                                                when (res) {
                                                    is UpdateCheckResult.UpdateAvailable -> {
                                                        availableAppUpdate = res.release
                                                        showThresholdDialog = false
                                                    }
                                                    is UpdateCheckResult.UpToDate -> {
                                                        Toast.makeText(context, "¡Tienes la última versión instalada (v${AppUpdateManager.getAppVersionName(context)})!", Toast.LENGTH_SHORT).show()
                                                    }
                                                    is UpdateCheckResult.Error -> {
                                                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        enabled = !isCheckingUpdates
                                    ) {
                                        if (isCheckingUpdates) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Buscar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showThresholdDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Guardar Configuración", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Diálogo y Calculador de 1RM por Perfil Carga-Velocidad
        if (showOneRMDialog) {
            OneRMCalculatorDialog(
                currentBestVelocity = repsList.map { it.meanVelocity }.average().takeIf { !it.isNaN() } ?: (vbtVelocityResult ?: 0.0),
                currentLoadKg = currentSetLoadKg.toDoubleOrNull() ?: 70.0,
                onDismiss = { showOneRMDialog = false },
                onApplyLoad = { newLoad ->
                    currentSetLoadKg = newLoad.toInt().toString()
                    showOneRMDialog = false
                }
            )
        }

        // Diálogo de Auto-Actualización de la App
        if (availableAppUpdate != null) {
            AppUpdateDialog(
                release = availableAppUpdate!!,
                onDismiss = { availableAppUpdate = null }
            )
        }

        // Diálogo para Configurar Repositorio de GitHub
        if (showRepoEditDialog) {
            var ownerInput by remember { mutableStateOf(AppUpdateManager.getRepoOwner(context)) }
            var repoInput by remember { mutableStateOf(AppUpdateManager.getRepoName(context)) }

            AlertDialog(
                onDismissRequest = { showRepoEditDialog = false },
                title = { Text("Configurar Repositorio de GitHub", color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Indica el usuario/organización y el nombre del repositorio donde subes los releases de la app:",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = ownerInput,
                            onValueChange = { ownerInput = it },
                            label = { Text("Usuario / Organización (ej: bayron)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FFCC),
                                focusedLabelColor = Color(0xFF00FFCC),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        OutlinedTextField(
                            value = repoInput,
                            onValueChange = { repoInput = it },
                            label = { Text("Nombre del Repositorio (ej: SpeedTracker)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FFCC),
                                focusedLabelColor = Color(0xFF00FFCC),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            AppUpdateManager.setRepoInfo(context, ownerInput, repoInput)
                            showRepoEditDialog = false
                            Toast.makeText(context, "Repositorio guardado: $ownerInput/$repoInput", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Guardar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRepoEditDialog = false }) {
                        Text("Cancelar", color = Color.Gray)
                    }
                }
            )
        }

        // Diálogo Posterior a la Grabación: Exportar o Analizar Cuadro a Cuadro
        if (showPostRecordDialog && lastRecordedVideoUri != null) {
            AlertDialog(
                onDismissRequest = { showPostRecordDialog = false },
                title = {
                    Text("¡Serie Grabada con Éxito!", fontWeight = FontWeight.Bold, color = Color(0xFF00FFCC))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Video guardado (${recordingDurationSeconds} s). ¿Qué deseas hacer con la serie?",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (repsList.isNotEmpty()) {
                            val best = repsList.maxByOrNull { it.peakVelocity }
                            Surface(
                                color = Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Resumen de la Serie:", fontWeight = FontWeight.Bold, color = Color(0xFF00FFCC), style = MaterialTheme.typography.labelSmall)
                                    Text("• Repeticiones: ${repsList.size}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                    Text("• Mejor PV (Pico): ${String.format(Locale.US, "%.2f", best?.peakVelocity ?: 0.0)} m/s", color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    Text("• Mejor MV (Media): ${String.format(Locale.US, "%.2f", best?.meanVelocity ?: 0.0)} m/s", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                showPostRecordDialog = false
                                onNavigateToVideoAnalysis?.invoke(lastRecordedVideoUri.toString())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("✂️ Recortar, Calibrar y Analizar Cuadro a Cuadro", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                showPostRecordDialog = false
                                showExportDataDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Exportar y Compartir Video con Telemetría", color = Color.White)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPostRecordDialog = false }) {
                        Text("Cerrar", color = Color.LightGray)
                    }
                }
            )
        }

        // Diálogo para solicitar datos del informe antes de exportar el video
        if (showExportDataDialog && lastRecordedVideoUri != null) {
            ExportReportDataDialog(
                initialAthleteName = activeUser?.name ?: "",
                initialLoadKg = 70.0,
                initialExerciseName = "Clean Suelo",
                availableAthletes = remember { userRepository.getUsers() },
                onDismiss = { showExportDataDialog = false },
                onConfirmExport = { athleteName, loadKg, exerciseName ->
                    showExportDataDialog = false
                    coroutineScope.launch {
                        isExportingVideo = true
                        exportProgress = 0f
                        try {
                            val exportDir = File(context.cacheDir, "videos").apply { mkdirs() }
                            val outputFile = File(exportDir, "SpeedTracker_Export_${System.currentTimeMillis()}.mp4")
                            val best = repsList.maxByOrNull { it.peakVelocity }
                            val bestPeak = best?.peakVelocity ?: 0.0
                            val bestMean = best?.meanVelocity ?: 0.0
                            val metricSummary = "$exerciseName • ${loadKg.toInt()} kg • VP: ${String.format(Locale.US, "%.2f", bestPeak)} m/s"

                            val finalVideoFile = VideoOverlayExporter.exportVideoWithOverlay(
                                context = context,
                                inputUri = lastRecordedVideoUri!!,
                                outputFile = outputFile,
                                mode = "VBT",
                                trackingPoints = liveRecordedTrackingPoints.toList(),
                                exerciseName = exerciseName,
                                athleteName = athleteName,
                                bestMetricSummary = metricSummary,
                                originBaselineY = VBTAnalyzer.lowestInflectionY ?: VBTAnalyzer.originBaselineY,
                                lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                                calibratedPlateRadius = VBTAnalyzer.calibratedPlateRadius,
                                sourceFrameWidth = cameraFrameWidth,
                                sourceFrameHeight = cameraFrameHeight,
                                onProgress = { p -> exportProgress = p }
                            )

                            // Guardar en el perfil del atleta
                            val targetUser = userRepository.getUsers().find { it.name.equals(athleteName, ignoreCase = true) }
                                ?: userRepository.saveUser(athleteName, 75.0)

                            userRepository.saveSession(
                                Session(
                                    id = UUID.randomUUID().toString(),
                                    userId = targetUser.id,
                                    activityType = "VBT ($exerciseName)",
                                    result = bestPeak,
                                    timestamp = System.currentTimeMillis(),
                                    meanVelocity = bestMean,
                                    loadKg = loadKg,
                                    videoExportPath = finalVideoFile.absolutePath
                                )
                            )

                            val summary = buildString {
                                append("⚡ Speed Tracker - Informe de Video VBT\n")
                                append("Atleta: $athleteName\n")
                                append("Ejercicio: $exerciseName\n")
                                append("Carga en Barra: ${loadKg.toInt()} kg\n")
                                append("Repeticiones: ${repsList.size}\n")
                                append("Mejor VP (Pico): ${String.format(Locale.US, "%.2f", bestPeak)} m/s\n")
                                append("Mejor VM (Media): ${String.format(Locale.US, "%.2f", bestMean)} m/s\n")
                            }

                            shareRecordedVideo(context, Uri.fromFile(finalVideoFile), summary)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            android.widget.Toast.makeText(context, "Aviso exportación: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                            shareRecordedVideo(context, lastRecordedVideoUri!!, "Speed Tracker VBT")
                        } finally {
                            isExportingVideo = false
                        }
                    }
                }
            )
        }

        // Overlay de Progreso al Exportar Video con Telemetría
        if (isExportingVideo) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xCC000000)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = exportProgress,
                            color = Color(0xFF00FFCC),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Exportando Video con Telemetría...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${(exportProgress * 100).toInt()}% completado",
                            color = Color(0xFF00FFCC),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Diálogo de Perfil Carga-Velocidad y Estimación del 1RM
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneRMCalculatorDialog(
    currentBestVelocity: Double,
    currentLoadKg: Double,
    onDismiss: () -> Unit,
    onApplyLoad: (Double) -> Unit
) {
    var selectedExercise by remember { mutableStateOf(VBTExerciseModel.SNATCH_FLOOR) }
    var inputLoadText by remember { mutableStateOf(currentLoadKg.toInt().toString()) }

    val load = inputLoadText.toDoubleOrNull() ?: currentLoadKg
    val estimated1RM = selectedExercise.estimateOneRM(load, currentBestVelocity, currentBestVelocity)
    val percent1RM = selectedExercise.calculatePercentOneRM(currentBestVelocity, currentBestVelocity)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Calculador 1RM & Perfil VBT", fontWeight = FontWeight.Bold, color = Color(0xFF00FFCC))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Estimación científica de 1RM basada en la velocidad de la serie actual:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )

                OutlinedTextField(
                    value = inputLoadText,
                    onValueChange = { inputLoadText = it },
                    label = { Text("Carga Levantada en la Serie (kg)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Ejercicio:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        VBTExerciseModel.SNATCH_FLOOR,
                        VBTExerciseModel.POWER_SNATCH,
                        VBTExerciseModel.CLEAN_FLOOR,
                        VBTExerciseModel.SQUAT,
                        VBTExerciseModel.BENCH_PRESS,
                        VBTExerciseModel.DEADLIFT
                    ).forEach { ex ->
                        FilterChip(
                            selected = selectedExercise == ex,
                            onClick = { selectedExercise = ex },
                            label = { Text(ex.displayName, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00FFCC),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }

                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("1RM Estimado", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${estimated1RM.toInt()} kg",
                            color = Color(0xFF00FFCC),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Intensidad: ${String.format(Locale.US, "%.1f", percent1RM)}% 1RM (a ${String.format(Locale.US, "%.2f", currentBestVelocity)} m/s)",
                            color = Color(0xFF00E5FF),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApplyLoad(load) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
            ) {
                Text("Usar Esta Carga", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Color.Gray)
            }
        }
    )
}

fun shareRecordedVideo(context: Context, videoUri: Uri, summary: String) {
    val shareUri = if (videoUri.scheme == "file") {
        val file = File(videoUri.path ?: "")
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    } else {
        videoUri
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        putExtra(Intent.EXTRA_TEXT, summary)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, "Compartir Video de la Serie").apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}

@Composable
fun BarPathLiveOverlay(
    frameWidth: Int,
    frameHeight: Int,
    barX: Float?,
    barY: Float?,
    originBaselineY: Float?,
    lowestInflectionY: Float?,
    exerciseType: VBTExerciseType,
    velocity: Double,
    trajectoryPoints: List<BarPathPoint>,
    isConcentric: Boolean,
    isEccentric: Boolean
) {
    if (frameWidth <= 0 || frameHeight <= 0) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val scale = maxOf(canvasWidth / frameWidth.toFloat(), canvasHeight / frameHeight.toFloat())
        val offsetX = (canvasWidth - frameWidth * scale) / 2f
        val offsetY = (canvasHeight - frameHeight * scale) / 2f

        fun mapToScreen(x: Float, y: Float): Offset {
            return Offset(x * scale + offsetX, y * scale + offsetY)
        }

        // Línea horizontal dinámica de inicio / despegue (Cero universal de salida)
        val dynamicBaselineY = lowestInflectionY ?: originBaselineY
        dynamicBaselineY?.let { origY ->
            val screenOrigY = origY * scale + offsetY
            drawLine(
                color = Color(0x6600FFCC),
                start = Offset(0f, screenOrigY),
                end = Offset(canvasWidth, screenOrigY),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )
        }

        // Trayectoria coloreada por velocidad
        if (trajectoryPoints.size > 1) {
            for (i in 0 until trajectoryPoints.size - 1) {
                val p1 = trajectoryPoints[i]
                val p2 = trajectoryPoints[i + 1]

                val s1 = mapToScreen(p1.x, p1.y)
                val s2 = mapToScreen(p2.x, p2.y)

                val color = when {
                    p2.velocity < 0.60 -> Color(0xFF00E5FF)
                    p2.velocity < 1.10 -> Color(0xFF00E676)
                    else -> Color(0xFFFF3D00)
                }

                drawLine(
                    color = color,
                    start = s1,
                    end = s2,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Punto o disco actual de la barra
        if (barX != null && barY != null) {
            val screenBar = mapToScreen(barX, barY)
            val trackerColor = when {
                isConcentric -> Color(0xFF00FFCC)
                isEccentric -> Color(0xFFFF9800)
                else -> Color(0xFF00E5FF)
            }

            drawCircle(
                color = trackerColor.copy(alpha = 0.25f),
                radius = 28.dp.toPx(),
                center = screenBar
            )

            drawCircle(
                color = trackerColor,
                radius = 16.dp.toPx(),
                center = screenBar,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
            )

            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = screenBar
            )
        }
    }
}
