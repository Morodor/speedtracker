package com.speedtracker.presentation.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.ui.text.style.TextAlign
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
import com.speedtracker.presentation.video.KinoveaCircleReticleCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

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

    // Tipo de Ejercicio VBT: Suelo vs Colgante vs Sentadilla/Banca
    var vbtExerciseType by remember { mutableStateOf(VBTExerciseType.FLOOR_PULL) }

    // Plantillas de Referencia de Encuadre VBT (Suelo / Colgante / Rack)
    var framingGuideType by remember { mutableStateOf(FramingGuideType.FLOOR_BAR) }
    var isFramingGuideVisible by remember { mutableStateOf(false) }

    LaunchedEffect(vbtExerciseType) {
        framingGuideType = when (vbtExerciseType) {
            VBTExerciseType.FLOOR_PULL -> FramingGuideType.FLOOR_BAR
            VBTExerciseType.HANG_PULL -> FramingGuideType.HANG_BAR
            VBTExerciseType.ECCENTRIC_CONCENTRIC -> FramingGuideType.RACK_BAR
        }
    }

    // Métrica VBT Principal: Velocidad Media (MV) vs Velocidad Pico (PV)
    var vbtPrimaryMetric by remember { mutableStateOf(VBTVelocityMetric.MEAN_VELOCITY) }

    // Dimensiones del frame de la cámara para proyectar el tracker
    var cameraFrameWidth by remember { mutableIntStateOf(0) }
    var cameraFrameHeight by remember { mutableIntStateOf(0) }
    var livePathPoints by remember { mutableStateOf<List<BarPathPoint>>(emptyList()) }

    // Estado de Repeticiones y Umbrales VBT
    var repsList by remember { mutableStateOf<List<RepResult>>(emptyList()) }
    var isCutoffAlarmActive by remember { mutableStateOf(false) }
    var showThresholdDialog by remember { mutableStateOf(false) }
    var minVelocityThreshold by remember { mutableStateOf(0.60) }
    var isThresholdEnabled by remember { mutableStateOf(false) }

    // ── CALIBRACIÓN DE DISCO OLÍMPICO (45 CM) CON RETÍCULA TÁCTIL (OPCIÓN A) ──
    var circleCenterScreen by remember { mutableStateOf<Offset?>(null) }
    var circleRadiusPx by remember { mutableFloatStateOf(85f) }
    var isPlateCalibrated by remember { mutableStateOf(false) }
    var isCalibratingPlate by remember { mutableStateOf(true) } // Inicia visible para que el usuario encuadre el disco

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
    LaunchedEffect(isThresholdEnabled, minVelocityThreshold, vbtExerciseType, vbtPrimaryMetric) {
        VBTAnalyzer.exerciseType = vbtExerciseType
        VBTAnalyzer.primaryMetric = vbtPrimaryMetric
        VBTAnalyzer.cutoffVelocityMps = if (isThresholdEnabled) minVelocityThreshold else null

        VBTAnalyzer.onRepCompleted = { rep ->
            repsList = VBTAnalyzer.currentSetReps.toList()
            val velocityToAnnounce = if (vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY) rep.meanVelocity else rep.peakVelocity
            audioCoach.speakVelocity(velocityToAnnounce)
            if (rep.isCutoffReached) {
                isCutoffAlarmActive = true
                audioCoach.speakCutoffAlert()
            }
        }
    }

    // Función auxiliar para aplicar la calibración del disco a VBTAnalyzer
    fun applyPlateCalibration(boxW: Float, boxH: Float) {
        val center = circleCenterScreen ?: Offset(boxW / 2f, boxH * 0.65f)
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
                    livePathPoints = if (VBTAnalyzer.isConcentricActive) {
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
            // Asegurar que el disco esté calibrado al iniciar la grabación
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

            // Guía de alineación de encuadre profesional VBT (Suelo, Colgante, Rack)
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
                originBaselineY = VBTAnalyzer.originBaselineY,
                lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                exerciseType = vbtExerciseType,
                velocity = vbtVelocityResult ?: 0.0,
                trajectoryPoints = livePathPoints,
                isConcentric = VBTAnalyzer.isConcentricActive,
                isEccentric = VBTAnalyzer.isEccentricActive
            )

            // ── RETÍCULA TÁCTIL INTERACTIVA PARA CALIBRAR EL DISCO (OPCIÓN A) ──
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

                // Barra Flotante de Calibración Rápida
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 195.dp)
                        .fillMaxWidth(0.92f),
                    color = Color(0xF2121720),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, Color(0xFF00FFCC))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = Color(0xFF00FFCC).copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("🎯", fontSize = 15.sp)
                                    }
                                }
                                Column {
                                    Text(
                                        "Calibración de Disco Olímpico (45 cm)",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                    val mmPerPx = if (circleRadiusPx > 0) 450.0 / (circleRadiusPx * 2f) else 0.0
                                    Text(
                                        "Toca el disco para centrar • Escala: ${String.format(Locale.US, "%.2f", mmPerPx)} mm/px",
                                        color = Color(0xFF00FFCC),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = { isCalibratingPlate = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Ocultar", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                        }

                        // Controles de Tamaño de Círculo [-] [Slider] [+] y Botón Fijar
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
                                modifier = Modifier.size(34.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF202834),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                                modifier = Modifier.size(34.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF202834),
                                    contentColor = Color(0xFF00FFCC)
                                )
                            ) {
                                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }

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
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Fijar Disco ✓", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Se requiere permiso de cámara para continuar.", color = Color.White)
            }
        }

        // Panel Superior Unificado: Navegación, Estado y Controles Biomecánicos
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fila 1: Botón Cerrar, Badge VBT, Botón Calibrar Disco, Botón Guía, Botón Umbral
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseClick) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                }

                // Badge de Halterofilia / VBT
                Surface(
                    color = Color(0xCC111822),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("🏋️", fontSize = 14.sp)
                        Text("VBT HALTEROFILIA", fontWeight = FontWeight.Bold, color = Color(0xFF00FFCC), fontSize = 12.sp)
                    }
                }

                // Botones Acciones Superiores: Calibrar Disco, Guía de Encuadre, Umbral
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Botón para Activar/Desactivar Retícula del Disco
                    IconButton(
                        onClick = { isCalibratingPlate = !isCalibratingPlate },
                        modifier = Modifier.background(
                            if (isCalibratingPlate) Color(0xFF00FFCC).copy(alpha = 0.25f) else Color.Transparent,
                            CircleShape
                        )
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = "Calibrar Disco",
                            tint = if (isCalibratingPlate || isPlateCalibrated) Color(0xFF00FFCC) else Color.White
                        )
                    }

                    IconButton(onClick = { isFramingGuideVisible = !isFramingGuideVisible }) {
                        Icon(
                            if (isFramingGuideVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Guía de Encuadre",
                            tint = if (isFramingGuideVisible) Color(0xFF00FFCC) else Color.LightGray
                        )
                    }

                    IconButton(onClick = { showThresholdDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Umbral",
                            tint = if (isThresholdEnabled) Color(0xFF00FFCC) else Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fila 2: Banner de Estado, Detección del Atleta y Estado del Disco
            Surface(
                color = if (isQuickMode) Color(0xCCFF9800) else Color(0x99000000),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (isAthleteInFrame) Color(0xFF00FFCC) else Color(0xFFFF5252),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isQuickMode) {
                            "⚡ VBT Rápido • ${if (isPlateCalibrated) "Disco Calibrado (45 cm)" else "Toca el disco para calibrar"}"
                        } else {
                            "VBT • ${activeUser?.name ?: "Atleta"} • ${if (isPlateCalibrated) "Disco Calibrado (45 cm)" else "Toca el disco para calibrar"}"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fila 3: Selectores VBT de Ejercicio y Métrica (Scroll horizontal)
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .background(Color(0xCC1A1A1A), shape = RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ejercicio: Suelo vs Colgante vs Sentadilla/Banca
                FilterChip(
                    selected = vbtExerciseType == VBTExerciseType.FLOOR_PULL,
                    onClick = { vbtExerciseType = VBTExerciseType.FLOOR_PULL },
                    label = { Text("Suelo / Snatch", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00FFCC),
                        selectedLabelColor = Color.Black
                    )
                )
                FilterChip(
                    selected = vbtExerciseType == VBTExerciseType.HANG_PULL,
                    onClick = { vbtExerciseType = VBTExerciseType.HANG_PULL },
                    label = { Text("Colgante", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00FFCC),
                        selectedLabelColor = Color.Black
                    )
                )
                FilterChip(
                    selected = vbtExerciseType == VBTExerciseType.ECCENTRIC_CONCENTRIC,
                    onClick = { vbtExerciseType = VBTExerciseType.ECCENTRIC_CONCENTRIC },
                    label = { Text("Sentadilla / Banca", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00FFCC),
                        selectedLabelColor = Color.Black
                    )
                )

                // Métrica: MV (Media) vs PV (Pico)
                FilterChip(
                    selected = vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY,
                    onClick = {
                        vbtPrimaryMetric = if (vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY)
                            VBTVelocityMetric.PEAK_VELOCITY else VBTVelocityMetric.MEAN_VELOCITY
                    },
                    label = {
                        Text(
                            if (vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY) "MV (Media)" else "PV (Pico)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E5FF),
                        selectedLabelColor = Color.Black
                    )
                )

                // Botón Fijar Inicio (Cero)
                FilledTonalIconButton(
                    onClick = { VBTAnalyzer.setManualOrigin() },
                    modifier = Modifier.size(30.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFF00FFCC)
                    )
                ) {
                    Icon(Icons.Default.Place, contentDescription = "Fijar Cero", modifier = Modifier.size(15.dp))
                }

                // Botón Calibrar Disco
                FilledTonalIconButton(
                    onClick = { isCalibratingPlate = !isCalibratingPlate },
                    modifier = Modifier.size(30.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isCalibratingPlate) Color(0xFF00FFCC) else Color(0xFF333333),
                        contentColor = if (isCalibratingPlate) Color.Black else Color(0xFFFFD600)
                    )
                ) {
                    Text("🎯", style = MaterialTheme.typography.labelSmall)
                }

                // Botón Estimador 1RM
                FilledTonalIconButton(
                    onClick = { showOneRMDialog = true },
                    modifier = Modifier.size(30.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFFFF4081)
                    )
                ) {
                    Text("1RM", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF4081))
                }
            }

            // Alerta visual de fatiga / umbral (Cutoff) en el flujo vertical
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
                            "🚨 ¡ALTO! UMBRAL ALCANZADO (Fin de Serie)",
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

        // Panel Inferior: Mediciones en Vivo y Registro de Serie
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE60A0A0A))
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY) "Velocidad Media (MV)" else "Velocidad Pico (PV)",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${String.format("%.2f", vbtVelocityResult ?: 0.0)} m/s",
                        color = Color(0xFF00FFCC),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY) "(MV)" else "(PV)",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // Lista de Reps realizadas en la serie con desglose MV / PV / ROM
                if (repsList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(repsList) { rep ->
                            Surface(
                                color = if (rep.isCutoffReached) Color(0xFFFF1744) else Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(8.dp),
                                border = CardDefaults.outlinedCardBorder()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Rep ${rep.repNumber}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        "MV: ${String.format("%.2f", rep.meanVelocity)}",
                                        color = if (rep.isCutoffReached) Color.White else Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "PV: ${String.format("%.2f", rep.peakVelocity)}",
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        "${(rep.romMeters * 100).toInt()} cm" +
                                                (if (rep.velocityLossPercent > 0) " • -${rep.velocityLossPercent.toInt()}%" else ""),
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Acciones inferiores: Nueva Serie, Grabar REC, Guardar Sesión
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            vbtVelocityResult = null
                            isCutoffAlarmActive = false
                            VBTAnalyzer.resetSet()
                            repsList = emptyList()
                            livePathPoints = emptyList()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Nueva Serie")
                    }

                    // Botón Grabar Serie (CameraX VideoCapture)
                    Button(
                        onClick = { toggleRecording(screenBoxW, screenBoxH) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFFF1744) else Color(0xFFD32F2F),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isRecording)
                                String.format("%02d:%02d", recordingDurationSeconds / 60, recordingDurationSeconds % 60)
                            else
                                "REC",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!isQuickMode && repsList.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (activeUser != null) {
                                    val bestRep = repsList.maxByOrNull { it.peakVelocity }
                                    val res = bestRep?.peakVelocity ?: (vbtVelocityResult ?: 0.0)
                                    val meanVel = bestRep?.meanVelocity
                                    val deviation = VBTAnalyzer.getHorizontalDeviationMeters() * 100.0

                                    userRepository.saveSession(
                                        Session(
                                            id = UUID.randomUUID().toString(),
                                            userId = activeUser.id,
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                        ) {
                            Text("Guardar Serie", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Diálogo de Umbral de Velocidad
        if (showThresholdDialog) {
            AlertDialog(
                onDismissRequest = { showThresholdDialog = false },
                title = { Text("Umbral de Detención VBT") },
                text = {
                    Column {
                        Text(
                            "Detener la serie si la velocidad cae por debajo de este valor para evitar fatiga excesiva:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isThresholdEnabled,
                                onCheckedChange = { isThresholdEnabled = it }
                            )
                            Text("Activar Corte por Velocidad", color = Color.White)
                        }

                        if (isThresholdEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Velocidad mínima: ${String.format("%.2f", minVelocityThreshold)} m/s",
                                color = Color(0xFF00FFCC),
                                fontWeight = FontWeight.Bold
                            )
                            Slider(
                                value = minVelocityThreshold.toFloat(),
                                onValueChange = { minVelocityThreshold = it.toDouble() },
                                valueRange = 0.30f..1.50f,
                                steps = 23
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showThresholdDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Aceptar")
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
                                    Text("• Mejor MV: ${String.format("%.2f", best?.meanVelocity ?: 0.0)} m/s", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                    Text("• Mejor PV: ${String.format("%.2f", best?.peakVelocity ?: 0.0)} m/s", color = Color.White, style = MaterialTheme.typography.bodySmall)
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
                                val summary = buildString {
                                    append("⚡ Speed Tracker - Análisis de Video VBT\n")
                                    append("Atleta: ${activeUser?.name ?: "Atleta"}\n")
                                    append("Ejercicio: ${vbtExerciseType.name}\n")
                                    val best = repsList.maxByOrNull { it.peakVelocity }
                                    append("Reps: ${repsList.size}\n")
                                    append("Mejor MV: ${String.format("%.2f", best?.meanVelocity ?: 0.0)} m/s\n")
                                    append("Mejor PV: ${String.format("%.2f", best?.peakVelocity ?: 0.0)} m/s\n")
                                }

                                showPostRecordDialog = false
                                coroutineScope.launch {
                                    isExportingVideo = true
                                    exportProgress = 0f
                                    try {
                                        val exportDir = File(context.cacheDir, "videos").apply { mkdirs() }
                                        val outputFile = File(exportDir, "SpeedTracker_Export_${System.currentTimeMillis()}.mp4")
                                        val best = repsList.maxByOrNull { it.peakVelocity }
                                        val metricSummary = "MV: ${String.format("%.2f", best?.meanVelocity ?: 0.0)} m/s"

                                        val finalVideoFile = VideoOverlayExporter.exportVideoWithOverlay(
                                            context = context,
                                            inputUri = lastRecordedVideoUri!!,
                                            outputFile = outputFile,
                                            mode = "VBT",
                                            trackingPoints = liveRecordedTrackingPoints.toList(),
                                            exerciseName = vbtExerciseType.name,
                                            athleteName = activeUser?.name ?: "Atleta",
                                            bestMetricSummary = metricSummary,
                                            originBaselineY = VBTAnalyzer.originBaselineY,
                                            lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                                            onProgress = { p -> exportProgress = p }
                                        )
                                        shareRecordedVideo(context, Uri.fromFile(finalVideoFile), summary)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        shareRecordedVideo(context, lastRecordedVideoUri!!, summary)
                                    } finally {
                                        isExportingVideo = false
                                    }
                                }
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

                // Carga actual ingresada
                OutlinedTextField(
                    value = inputLoadText,
                    onValueChange = { inputLoadText = it },
                    label = { Text("Carga Levantada en la Serie (kg)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // Selector de Ejercicio de Referencia
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

                // Resultado Proyectado
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
                            "Intensidad: ${String.format("%.1f", percent1RM)}% 1RM (a ${String.format("%.2f", currentBestVelocity)} m/s)",
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

/**
 * Función auxiliar para compartir el video grabado
 */
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

/**
 * Lienzo en vivo que proyecta:
 * 1. El disco calibrado y el tracker actual.
 * 2. La línea de partida (Cero / Inicio).
 * 3. El punto más bajo en sentadilla o banca.
 * 4. La línea de trayectoria (Bar Path) con colores según velocidad.
 */
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

        // 1. Línea horizontal de partida (Cero / Inicio)
        originBaselineY?.let { origY ->
            val screenOrigY = origY * scale + offsetY
            drawLine(
                color = Color(0x6600FFCC),
                start = Offset(0f, screenOrigY),
                end = Offset(canvasWidth, screenOrigY),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )
        }

        // 2. Línea de fondo en sentadilla / banca / dip en colgante
        if ((exerciseType == VBTExerciseType.ECCENTRIC_CONCENTRIC || exerciseType == VBTExerciseType.HANG_PULL) && lowestInflectionY != null) {
            val screenInflectY = lowestInflectionY * scale + offsetY
            drawLine(
                color = Color(0xFFFFD600),
                start = Offset(0f, screenInflectY),
                end = Offset(canvasWidth, screenInflectY),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        }

        // 3. Dibujar la línea de trayectoria (Bar Path) coloreada por velocidad
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

        // 4. Dibujar el punto o disco actual de la barra
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
