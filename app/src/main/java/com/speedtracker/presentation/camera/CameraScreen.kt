package com.speedtracker.presentation.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedtracker.core.export.VideoFrameTrackingPoint
import com.speedtracker.core.export.VideoOverlayExporter
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.pose.PoseLandmark
import com.speedtracker.core.audio.SpeedAudioCoach
import com.speedtracker.core.math.*
import com.speedtracker.core.ml.PoseAnalyzer
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    userRepository: UserRepository,
    isQuickMode: Boolean = false,
    onCloseClick: () -> Unit,
    onNavigateToVideoAnalysis: ((String) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

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
    ) { isGranted ->
        hasPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
        VerticalJumpAnalyzer.reset()
        VBTAnalyzer.reset()
    }

    var currentMode by remember { mutableStateOf("JUMP") }
    var jumpHeightResult by remember { mutableStateOf<Double?>(null) }
    var vbtVelocityResult by remember { mutableStateOf<Double?>(null) }
    var isAthleteInFrame by remember { mutableStateOf(false) }

    // Saltos Avanzados Fase 1: CMJ, SJ, Drop Jump (RSI)
    var jumpType by remember { mutableStateOf(JumpType.CMJ) }

    // Tipo de Ejercicio VBT: Suelo vs Sentadilla/Banca
    var vbtExerciseType by remember { mutableStateOf(VBTExerciseType.FLOOR_PULL) }

    // Plantillas de Referencia de Encuadre (Suelo / Rack / Parado / Saltos)
    var framingGuideType by remember { mutableStateOf(FramingGuideType.FLOOR_BAR) }
    var isFramingGuideVisible by remember { mutableStateOf(false) }

    // Sincronizar automáticamente la guía al cambiar el modo
    LaunchedEffect(currentMode, vbtExerciseType) {
        if (currentMode == "JUMP") {
            framingGuideType = FramingGuideType.VERTICAL_JUMP
        } else if (currentMode == "VBT") {
            framingGuideType = when (vbtExerciseType) {
                VBTExerciseType.FLOOR_PULL -> FramingGuideType.FLOOR_BAR
                VBTExerciseType.HANG_PULL -> FramingGuideType.HANG_BAR
                VBTExerciseType.ECCENTRIC_CONCENTRIC -> FramingGuideType.RACK_BAR
            }
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

    // Modales y herramientas de la Fase 1: Calibrador de Disco y Perfil 1RM
    var showPlateCalibrator by remember { mutableStateOf(false) }
    var plateCaliperTopFraction by remember { mutableFloatStateOf(0.40f) }
    var plateCaliperBottomFraction by remember { mutableFloatStateOf(0.65f) }
    var showOneRMDialog by remember { mutableStateOf(false) }
    var currentSetLoadKg by remember { mutableStateOf("70") }

    // Grabación de Series en Vivo y Slow-Motion (Fase 2)
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

    // Callbacks del motor de análisis
    LaunchedEffect(currentMode, isThresholdEnabled, minVelocityThreshold, vbtExerciseType, vbtPrimaryMetric, jumpType) {
        VBTAnalyzer.exerciseType = vbtExerciseType
        VBTAnalyzer.primaryMetric = vbtPrimaryMetric
        VBTAnalyzer.cutoffVelocityMps = if (isThresholdEnabled) minVelocityThreshold else null
        VerticalJumpAnalyzer.currentJumpType = jumpType

        VBTAnalyzer.onRepCompleted = { rep ->
            repsList = VBTAnalyzer.currentSetReps.toList()
            val velocityToAnnounce = if (vbtPrimaryMetric == VBTVelocityMetric.MEAN_VELOCITY) rep.meanVelocity else rep.peakVelocity
            audioCoach.speakVelocity(velocityToAnnounce)
            if (rep.isCutoffReached) {
                isCutoffAlarmActive = true
                audioCoach.speakCutoffAlert()
            }
        }

        VerticalJumpAnalyzer.onJumpCompleted = { metrics ->
            jumpHeightResult = metrics.heightMeters
            if (metrics.jumpType == JumpType.DROP_JUMP && metrics.rsi != null && metrics.contactTimeMs != null) {
                audioCoach.speakDropJump(metrics.heightMeters * 100.0, metrics.contactTimeMs, metrics.rsi)
            } else {
                audioCoach.speakJumpHeight(metrics.heightMeters * 100.0)
            }
        }
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

                    val keyLandmark = if (currentMode == "JUMP") {
                        pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
                            ?: pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)
                            ?: pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                            ?: pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
                    } else {
                        pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                            ?: pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                    }
                    isAthleteInFrame = keyLandmark != null && keyLandmark.inFrameLikelihood > 0.30f

                    if (currentMode == "JUMP") {
                        val height = VerticalJumpAnalyzer.processPose(
                            pose,
                            System.currentTimeMillis(),
                            activeUser?.bodyWeightKg ?: 70.0
                        )
                        if (height != null && height > 0.0) jumpHeightResult = height

                        if (isRecording) {
                            val relTimeMs = System.currentTimeMillis() - recordingStartTimestampMs
                            val lf = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX) ?: pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                            val rf = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX) ?: pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
                            val trackingX: Float?
                            val trackingY: Float?
                            val label: String
                            if (lf != null && rf != null && (lf.inFrameLikelihood > 0.25f || rf.inFrameLikelihood > 0.25f)) {
                                trackingX = (lf.position.x + rf.position.x) / 2f
                                trackingY = (lf.position.y + rf.position.y) / 2f
                                label = "Pies (Despegue)"
                            } else {
                                val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                                val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                                if (lh != null && rh != null) {
                                    trackingX = (lh.position.x + rh.position.x) / 2f
                                    trackingY = (lh.position.y + rh.position.y) / 2f
                                    label = "Centro de Masa"
                                } else {
                                    trackingX = null
                                    trackingY = null
                                    label = ""
                                }
                            }
                            if (trackingX != null && trackingY != null) {
                                liveRecordedTrackingPoints.add(
                                    VideoFrameTrackingPoint(
                                        timestampMs = relTimeMs,
                                        x = trackingX,
                                        y = trackingY,
                                        velocityMps = 0.0,
                                        labelText = label
                                    )
                                )
                            }
                        }
                    } else if (currentMode == "VBT") {
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

    fun toggleRecording() {
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
        } else {
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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

            // Guía de alineación y referencia de encuadre profesional (Suelo, Rack, Parado, Saltos)
            FramingGuideOverlay(
                selectedType = framingGuideType,
                isVisible = isFramingGuideVisible,
                onTypeSelected = { framingGuideType = it },
                onToggleVisibility = { isFramingGuideVisible = !isFramingGuideVisible }
            )

            // Tracker en Vivo de la Barra, Punto de Origen y Línea de Trayectoria (Bar Path)
            if (currentMode == "VBT") {
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
            }

            // Calibrador Interactivo de Disco Olímpico (45 cm)
            if (showPlateCalibrator) {
                PlateCaliperOverlay(
                    topFraction = plateCaliperTopFraction,
                    bottomFraction = plateCaliperBottomFraction,
                    onTopChange = { plateCaliperTopFraction = it },
                    onBottomChange = { plateCaliperBottomFraction = it },
                    onConfirm = { heightPx ->
                        VBTAnalyzer.calibrateWithPlate(0.45, heightPx)
                        showPlateCalibrator = false
                    },
                    onDismiss = { showPlateCalibrator = false }
                )
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
            // Fila 1: Botón Cerrar, Selector SALTO / VBT, Botón Guía, Botón Umbral
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseClick) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                }

                // Selector SALTO / VBT
                Surface(
                    color = Color(0x99111111),
                    shape = RoundedCornerShape(24.dp),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        TextButton(
                            onClick = {
                                currentMode = "JUMP"
                                jumpHeightResult = null
                                isCutoffAlarmActive = false
                                VerticalJumpAnalyzer.reset()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (currentMode == "JUMP") Color(0xFF00FFCC) else Color.Transparent,
                                contentColor = if (currentMode == "JUMP") Color.Black else Color.Gray
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("SALTO (PIES)", fontWeight = FontWeight.Bold)
                        }

                        TextButton(
                            onClick = {
                                currentMode = "VBT"
                                vbtVelocityResult = null
                                isCutoffAlarmActive = false
                                VBTAnalyzer.reset()
                                repsList = emptyList()
                                livePathPoints = emptyList()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (currentMode == "VBT") Color(0xFF00FFCC) else Color.Transparent,
                                contentColor = if (currentMode == "VBT") Color.Black else Color.Gray
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("VBT (DISCO)", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Botones Acciones Superiores: Guía de Encuadre & Configuración de Umbral
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isFramingGuideVisible = !isFramingGuideVisible }) {
                        Icon(
                            if (isFramingGuideVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Guía de Encuadre",
                            tint = if (isFramingGuideVisible) Color(0xFF00FFCC) else Color.LightGray
                        )
                    }

                    if (currentMode == "VBT") {
                        IconButton(onClick = { showThresholdDialog = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Umbral",
                                tint = if (isThresholdEnabled) Color(0xFF00FFCC) else Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fila 2: Banner de Estado, Perspectiva Biomecánica y Atleta
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
                            if (currentMode == "VBT") "⚡ VBT Rápido • Vista Lateral 90° (Disco)" else "⚡ Salto Rápido • Plano Frontal (Pies)"
                        } else {
                            if (currentMode == "VBT") "VBT • Vista Lateral 90° • ${activeUser?.name ?: "Invitado"}"
                            else "Salto • Plano Frontal (Pies) • ${activeUser?.name ?: "Invitado"}"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fila 3: Selectores Específicos según el Modo (Scroll horizontal para evitar desbordes)
            if (currentMode == "JUMP") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .background(Color(0xCC1A1A1A), shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = jumpType == JumpType.CMJ,
                        onClick = { jumpType = JumpType.CMJ },
                        label = { Text("CMJ (Contramovimiento)", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00FFCC),
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = jumpType == JumpType.SJ,
                        onClick = { jumpType = JumpType.SJ },
                        label = { Text("SJ (Pausa 90°)", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00FFCC),
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = jumpType == JumpType.DROP_JUMP,
                        onClick = {
                            jumpType = JumpType.DROP_JUMP
                            VerticalJumpAnalyzer.prepareDropJump()
                        },
                        label = { Text("Drop Jump (RSI)", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFD600),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            } else if (currentMode == "VBT") {
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
                        label = { Text("Suelo", style = MaterialTheme.typography.labelSmall) },
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
                        label = { Text("Sentadilla/Banca", style = MaterialTheme.typography.labelSmall) },
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
                        onClick = { showPlateCalibrator = true },
                        modifier = Modifier.size(30.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF333333),
                            contentColor = Color(0xFFFFD600)
                        )
                    ) {
                        Text("📐", style = MaterialTheme.typography.labelSmall)
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

        // Panel Inferior: Mediciones en Vivo y Tarjetas Científicas
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE60A0A0A))
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (currentMode == "JUMP") {
                    val metrics = VerticalJumpAnalyzer.lastJumpMetrics
                    if (jumpHeightResult != null && metrics != null) {
                        Text("¡Salto Registrado!", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${String.format("%.1f", jumpHeightResult!! * 100)} cm",
                            color = Color(0xFF00FFCC),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black
                        )

                        // Desglose Científico según Tipo de Salto
                        if (metrics.jumpType == JumpType.DROP_JUMP && metrics.rsi != null && metrics.contactTimeMs != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = if (metrics.contactTimeMs < 250) Color(0x3300FFCC) else Color(0x33FFA726),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "Tc: ${metrics.contactTimeMs} ms (${if (metrics.contactTimeMs < 250) "Rápido" else "Lento"})",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = if (metrics.contactTimeMs < 250) Color(0xFF00FFCC) else Color(0xFFFFA726),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Surface(
                                    color = Color(0x33FFD600),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "RSI: ${String.format("%.2f", metrics.rsi)}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = Color(0xFFFFD600),
                                        fontWeight = FontWeight.Black,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Potencia: ${metrics.peakPowerWatts.toInt()} W • ${String.format("%.1f", metrics.relativePowerWattsPerKg)} W/kg" +
                                        (if (metrics.eur != null) " • EUR: ${String.format("%.2f", metrics.eur)}" else ""),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        val prompt = when (jumpType) {
                            JumpType.CMJ -> "Párese erguido y realice contramovimiento natural"
                            JumpType.SJ -> "Baje a 90°, mantenga pausa 2s y salte sin rebotar"
                            JumpType.DROP_JUMP -> "Cae del cajón y rebota en el suelo lo más rápido posible"
                            JumpType.ASYMMETRY_TEST -> "Salte unipodalmente con pierna izquierda y derecha para evaluar asimetría"
                        }
                        Text(prompt, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                } else if (currentMode == "VBT") {
                    // Modo VBT: Velocidad en Vivo y Métricas MV / PV
                    val statusText = when {
                        VBTAnalyzer.isConcentricActive -> "¡Subida Concéntrica!"
                        VBTAnalyzer.isEccentricActive -> "Bajada Excéntrica..."
                        else -> "En Reposo / Listo"
                    }

                    Text(
                        statusText,
                        color = if (VBTAnalyzer.isConcentricActive) Color(0xFF00FFCC) else Color.LightGray,
                        style = MaterialTheme.typography.labelSmall
                    )

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${String.format("%.2f", vbtVelocityResult ?: 0.0)} m/s",
                            color = if ((vbtVelocityResult ?: 0.0) > 0.0) Color(0xFF00FFCC) else Color.Gray,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black
                        )
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
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Acciones inferiores
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (currentMode == "JUMP") {
                                jumpHeightResult = null
                                VerticalJumpAnalyzer.reset()
                                if (jumpType == JumpType.DROP_JUMP) {
                                    VerticalJumpAnalyzer.prepareDropJump()
                                }
                            } else {
                                vbtVelocityResult = null
                                isCutoffAlarmActive = false
                                VBTAnalyzer.resetSet()
                                repsList = emptyList()
                                livePathPoints = emptyList()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (currentMode == "JUMP") "Siguiente" else "Nueva Serie")
                    }

                    // Botón Grabar Serie (CameraX VideoCapture)
                    Button(
                        onClick = { toggleRecording() },
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

                    if (!isQuickMode && (jumpHeightResult != null || repsList.isNotEmpty())) {
                        Button(
                            onClick = {
                                if (activeUser != null) {
                                    val jumpMetrics = if (currentMode == "JUMP") VerticalJumpAnalyzer.lastJumpMetrics else null
                                    val bestRep = repsList.maxByOrNull { it.peakVelocity }
                                    val res = if (currentMode == "JUMP") {
                                        jumpHeightResult!!
                                    } else {
                                        bestRep?.peakVelocity ?: (vbtVelocityResult ?: 0.0)
                                    }
                                    val meanVel = if (currentMode == "VBT") bestRep?.meanVelocity else null
                                    val deviation = if (currentMode == "VBT") VBTAnalyzer.getHorizontalDeviationMeters() * 100.0 else null

                                    userRepository.saveSession(
                                        Session(
                                            id = UUID.randomUUID().toString(),
                                            userId = activeUser.id,
                                            activityType = currentMode,
                                            result = res,
                                            timestamp = System.currentTimeMillis(),
                                            powerWatts = jumpMetrics?.peakPowerWatts,
                                            powerWattsPerKg = jumpMetrics?.relativePowerWattsPerKg,
                                            horizontalDeviationCm = deviation,
                                            meanVelocity = meanVel,
                                            loadKg = currentSetLoadKg.toDoubleOrNull(),
                                            jumpType = if (currentMode == "JUMP") jumpType.name else null,
                                            contactTimeMs = jumpMetrics?.contactTimeMs,
                                            rsi = jumpMetrics?.rsi
                                        )
                                    )
                                    onCloseClick()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                        ) {
                            Text("Guardar Sesión", fontWeight = FontWeight.Bold)
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
                currentLoadInput = currentSetLoadKg,
                onLoadChange = { currentSetLoadKg = it },
                onDismiss = { showOneRMDialog = false }
            )
        }

        // Diálogo Post-Grabación de Serie
        if (showPostRecordDialog && lastRecordedVideoUri != null) {
            AlertDialog(
                onDismissRequest = { showPostRecordDialog = false },
                title = {
                    Text("🎥 ¡Serie Grabada con Éxito!", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "El video ha sido guardado. ¿Qué deseas hacer con la serie?",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )

                        if (currentMode == "VBT" && repsList.isNotEmpty()) {
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
                        } else if (currentMode == "JUMP" && jumpHeightResult != null) {
                            val m = VerticalJumpAnalyzer.lastJumpMetrics
                            Surface(
                                color = Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Resumen del Salto:", fontWeight = FontWeight.Bold, color = Color(0xFF00FFCC), style = MaterialTheme.typography.labelSmall)
                                    Text("• Altura: ${String.format("%.1f", jumpHeightResult!! * 100)} cm", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                    if (m != null) {
                                        Text("• Potencia: ${m.peakPowerWatts.toInt()} W (${String.format("%.1f", m.relativePowerWattsPerKg)} W/kg)", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                        if (m.rsi != null) {
                                            Text("• RSI: ${String.format("%.2f", m.rsi)} • Tc: ${m.contactTimeMs} ms", color = Color(0xFFFFD600), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
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
                                    append("⚡ Speed Tracker - Análisis de Video\n")
                                    append("Atleta: ${activeUser?.name ?: "Invitado"}\n")
                                    if (currentMode == "VBT") {
                                        append("Ejercicio: ${vbtExerciseType.name}\n")
                                        val best = repsList.maxByOrNull { it.peakVelocity }
                                        append("Reps: ${repsList.size}\n")
                                        append("Mejor MV: ${String.format("%.2f", best?.meanVelocity ?: 0.0)} m/s\n")
                                        append("Mejor PV: ${String.format("%.2f", best?.peakVelocity ?: 0.0)} m/s\n")
                                    } else {
                                        append("Tipo: ${jumpType.name}\n")
                                        append("Altura: ${String.format("%.1f", (jumpHeightResult ?: 0.0) * 100)} cm\n")
                                        val m = VerticalJumpAnalyzer.lastJumpMetrics
                                        if (m?.rsi != null) append("RSI: ${String.format("%.2f", m.rsi)}\n")
                                    }
                                }

                                showPostRecordDialog = false
                                coroutineScope.launch {
                                    isExportingVideo = true
                                    exportProgress = 0f
                                    try {
                                        val exportDir = File(context.cacheDir, "videos").apply { mkdirs() }
                                        val outputFile = File(exportDir, "SpeedTracker_Export_${System.currentTimeMillis()}.mp4")
                                        val best = repsList.maxByOrNull { it.peakVelocity }
                                        val metricSummary = if (currentMode == "VBT") {
                                            "MV: ${String.format("%.2f", best?.meanVelocity ?: 0.0)} m/s"
                                        } else {
                                            "Altura: ${String.format("%.1f", (jumpHeightResult ?: 0.0) * 100)} cm"
                                        }

                                        val finalVideoFile = VideoOverlayExporter.exportVideoWithOverlay(
                                            context = context,
                                            inputUri = lastRecordedVideoUri!!,
                                            outputFile = outputFile,
                                            mode = currentMode,
                                            trackingPoints = liveRecordedTrackingPoints.toList(),
                                            exerciseName = if (currentMode == "VBT") vbtExerciseType.name else jumpType.name,
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
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Compartir Video + Informe", color = Color.White)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPostRecordDialog = false }) {
                        Text("Cerrar", color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Diálogo de exportación y quemado de video con trayectoria
        if (isExportingVideo) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color(0xFF00FFCC),
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Exportando Video", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                text = {
                    Column {
                        Text(
                            "Incrustando trayectoria de la barra y telemetría HUD directamente en el video...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = exportProgress,
                            color = Color(0xFF00FFCC),
                            trackColor = Color(0xFF333333),
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "${(exportProgress * 100).toInt()}% procesado",
                            color = Color(0xFF00FFCC),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                },
                confirmButton = {},
                containerColor = Color(0xFF1E1E1E)
            )
        }
    }
}

fun shareRecordedVideo(context: Context, videoUri: Uri, summary: String) {
    val contentUri: Uri = try {
        if (videoUri.scheme == "file") {
            val file = File(videoUri.path ?: "")
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            videoUri
        }
    } catch (e: Exception) {
        videoUri
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_TEXT, summary)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(Intent.createChooser(shareIntent, "Compartir Video & Métricas").apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}

/**
 * Calibrador de Disco Olímpico (45 cm):
 * Muestra dos líneas horizontales que el usuario puede ajustar para que coincidan
 * con el diámetro superior e inferior del disco en pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateCaliperOverlay(
    topFraction: Float,
    bottomFraction: Float,
    onTopChange: (Float) -> Unit,
    onBottomChange: (Float) -> Unit,
    onConfirm: (heightPx: Float) -> Unit,
    onDismiss: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalHeight = maxHeight
        val totalHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { totalHeight.toPx() }
        val topPx = totalHeightPx * topFraction
        val bottomPx = totalHeightPx * bottomFraction
        val plateHeightPx = kotlin.math.abs(bottomPx - topPx)
        val mmPerPx = if (plateHeightPx > 0) (450.0 / plateHeightPx) else 0.0

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width

            // Línea superior del disco
            drawLine(
                color = Color(0xFFFFD600),
                start = Offset(0f, topPx),
                end = Offset(width, topPx),
                strokeWidth = 3.dp.toPx()
            )

            // Línea inferior del disco
            drawLine(
                color = Color(0xFFFFD600),
                start = Offset(0f, bottomPx),
                end = Offset(width, bottomPx),
                strokeWidth = 3.dp.toPx()
            )

            // Círculo guía centrado
            val centerY = (topPx + bottomPx) / 2f
            val radius = plateHeightPx / 2f
            drawCircle(
                color = Color(0x44FFD600),
                radius = radius,
                center = Offset(width / 2f, centerY),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f)))
            )
        }

        // Tarjeta flotante de ajuste de calibración
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            color = Color(0xF21E1E1E),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "📐 Calibración por Disco Olímpico (45 cm)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD600),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Ajusta los deslizadores hasta que las líneas toquen el borde superior e inferior del disco:",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text("Borde Superior", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = topFraction,
                    onValueChange = onTopChange,
                    valueRange = 0.10f..0.50f
                )

                Text("Borde Inferior", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = bottomFraction,
                    onValueChange = onBottomChange,
                    valueRange = 0.50f..0.90f
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Altura: ${plateHeightPx.toInt()} px • Escala: ${String.format("%.2f", mmPerPx)} mm/px",
                    color = Color(0xFF00FFCC),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancelar", color = Color.White)
                    }
                    Button(
                        onClick = { onConfirm(plateHeightPx) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD600), contentColor = Color.Black)
                    ) {
                        Text("Aplicar 45 cm", fontWeight = FontWeight.Bold)
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
    currentLoadInput: String,
    onLoadChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedExerciseMVT by remember { mutableDoubleStateOf(OneRMCalculator.MVT_SQUAT) }
    var exerciseLabel by remember { mutableStateOf("Sentadilla (0.30 m/s)") }
    var pointsUpdated by remember { mutableIntStateOf(0) }

    val profile = remember(pointsUpdated, selectedExerciseMVT) {
        OneRMCalculator.calculateOneRM(selectedExerciseMVT)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("🏋️ Estimador de 1RM (Perfil Carga-Velocidad)", fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "Selecciona el ejercicio para usar su velocidad mínima (MVT):",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedExerciseMVT == OneRMCalculator.MVT_SQUAT,
                            onClick = {
                                selectedExerciseMVT = OneRMCalculator.MVT_SQUAT
                                exerciseLabel = "Sentadilla (0.30 m/s)"
                            },
                            label = { Text("Sentadilla", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = selectedExerciseMVT == OneRMCalculator.MVT_BENCH,
                            onClick = {
                                selectedExerciseMVT = OneRMCalculator.MVT_BENCH
                                exerciseLabel = "Banca (0.15 m/s)"
                            },
                            label = { Text("Banca", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = selectedExerciseMVT == OneRMCalculator.MVT_DEADLIFT,
                            onClick = {
                                selectedExerciseMVT = OneRMCalculator.MVT_DEADLIFT
                                exerciseLabel = "P. Muerto (0.15 m/s)"
                            },
                            label = { Text("P. Muerto", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                item {
                    Divider(color = Color(0x33FFFFFF))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Registrar Serie Actual:", fontWeight = FontWeight.Bold, color = Color.White)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = currentLoadInput,
                            onValueChange = onLoadChange,
                            label = { Text("Carga (kg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val load = currentLoadInput.toDoubleOrNull() ?: 0.0
                                val vel = if (currentBestVelocity > 0.0) currentBestVelocity else 0.80
                                if (load > 0.0) {
                                    OneRMCalculator.addPoint(load, vel)
                                    pointsUpdated++
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                        ) {
                            Text("+ Añadir", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Lista de Puntos Cargados
                if (OneRMCalculator.sessionPoints.isNotEmpty()) {
                    item {
                        Text("Series Registradas:", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                    }
                    itemsIndexed(OneRMCalculator.sessionPoints) { index, pt ->
                        Surface(
                            color = Color(0xFF222222),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Serie ${index + 1}: ${pt.loadKg.toInt()} kg  @  ${String.format("%.2f", pt.velocityMps)} m/s",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                IconButton(
                                    onClick = {
                                        OneRMCalculator.removePoint(index)
                                        pointsUpdated++
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Tarjeta de Resultado 1RM
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = if (profile.isValid) Color(0xFF1B382B) else Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "1RM ESTIMADO HOY",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (profile.isValid) "${String.format("%.1f", profile.estimatedOneRM)} kg" else "-- kg",
                                color = if (profile.isValid) Color(0xFF00FFCC) else Color.Gray,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                profile.message,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
            ) {
                Text("Listo")
            }
        },
        dismissButton = {
            if (OneRMCalculator.sessionPoints.isNotEmpty()) {
                TextButton(onClick = {
                    OneRMCalculator.clearPoints()
                    pointsUpdated++
                }) {
                    Text("Limpiar", color = Color(0xFFFF5252))
                }
            }
        }
    )
}

/**
 * Superpone en tiempo real sobre la cámara:
 * 1. La línea de partida / base (origen calibrado o suelo).
 * 2. El punto más bajo (fondo en sentadilla).
 * 3. La mira / tracker en el centro exacto de la barra.
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

        // 2. Línea de fondo en sentadilla / banca / dip en colgante (Punto de inflexión inferior)
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
                    p2.velocity < 0.60 -> Color(0xFF00E5FF) // Azul/Cyan fase controlada
                    p2.velocity < 1.10 -> Color(0xFF00E676) // Verde Neón fase media
                    else -> Color(0xFFFF3D00)               // Rojo/Naranja máxima aceleración
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

        // 4. Dibujar el Tracker / Mira holográfica en el centro de la barra
        if (barX != null && barY != null) {
            val screenPos = mapToScreen(barX, barY)

            val trackerColor = when {
                isConcentric && velocity >= 1.10 -> Color(0xFFFF3D00)
                isConcentric && velocity >= 0.60 -> Color(0xFF00E676)
                isConcentric -> Color(0xFF00E5FF)
                isEccentric -> Color(0xFFFFD600) // Amarillo en bajada
                else -> Color(0x99AAAAAA)       // Reposo
            }

            // Anillo exterior con pulso
            drawCircle(
                color = trackerColor,
                radius = if (isConcentric) 22.dp.toPx() else 16.dp.toPx(),
                center = screenPos,
                style = Stroke(width = 3.dp.toPx())
            )

            // Punto central sólido
            drawCircle(
                color = trackerColor,
                radius = 5.dp.toPx(),
                center = screenPos
            )

            // Eje horizontal de la barra
            drawLine(
                color = trackerColor.copy(alpha = 0.8f),
                start = Offset(screenPos.x - 26.dp.toPx(), screenPos.y),
                end = Offset(screenPos.x + 26.dp.toPx(), screenPos.y),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
fun AlignmentOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        drawRect(
            color = Color(0x3300FFCC),
            topLeft = Offset(width * 0.1f, height * 0.2f),
            size = Size(width * 0.8f, height * 0.6f),
            style = Stroke(width = 2.dp.toPx())
        )
        drawLine(
            color = Color(0x3300FFCC),
            start = Offset(width / 2, height * 0.2f),
            end = Offset(width / 2, height * 0.8f),
            strokeWidth = 1.dp.toPx()
        )
    }
}
