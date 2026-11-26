package com.speedtracker.presentation.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.speedtracker.R
import com.speedtracker.core.update.AppReleaseInfo
import com.speedtracker.core.update.AppUpdateManager
import com.speedtracker.core.update.UpdateCheckResult
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
import com.speedtracker.presentation.components.AppUpdateDialog
import com.speedtracker.presentation.history.SavedPdfReportDialog
import com.speedtracker.presentation.history.SavedVideoPlayerDialog
import com.speedtracker.presentation.video.TutorialGuideDialog
import androidx.compose.material.icons.filled.SystemUpdate
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    userRepository: UserRepository,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToQuickCamera: () -> Unit,
    onNavigateToVideoAnalysis: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(userRepository.getSessions().sortedByDescending { it.timestamp }) }
    val usersMap = remember(sessions) { userRepository.getUsers().associateBy { it.id } }

    var showTutorialModal by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    var selectedTutorialTopic by remember { mutableStateOf("VBT") }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    // Estado de comprobación de actualización desde GitHub (Morodor/speedtracker)
    var availableUpdate by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val currentVersionName = remember { AppUpdateManager.getAppVersionName(context) }

    // Comprobar automáticamente al abrir el Home si hay una versión nueva publicada en GitHub
    LaunchedEffect(Unit) {
        when (val result = AppUpdateManager.checkForUpdate(context)) {
            is UpdateCheckResult.UpdateAvailable -> {
                availableUpdate = result.release
            }
            else -> {}
        }
    }

    // 1. Selector de Video desde Galería -> pasa directo al Trim de Video
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onNavigateToVideoAnalysis(uri.toString())
        }
    }

    // 2. Grabador de Video Normal desde Cámara -> pasa directo al Trim de Video
    val cameraVideoCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        val recordedFile = pendingCaptureFile
        if (success && recordedFile != null && recordedFile.exists() && recordedFile.length() > 0L) {
            onNavigateToVideoAnalysis(Uri.fromFile(recordedFile).toString())
        } else if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0L) {
            onNavigateToVideoAnalysis(Uri.fromFile(recordedFile).toString())
        }
    }

    fun launchNormalVideoCamera() {
        try {
            val videosDir = File(context.cacheDir, "videos").apply { mkdirs() }
            val outFile = File(videosDir, "recorded_${System.currentTimeMillis()}.mp4")
            pendingCaptureFile = outFile
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            cameraVideoCaptureLauncher.launch(contentUri)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al abrir la cámara de video", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchNormalVideoCamera()
        } else {
            Toast.makeText(context, "Se requiere permiso de cámara para grabar el video", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Speed Tracker", fontWeight = FontWeight.Bold)
                        Surface(
                            color = Color(0xFF00FFCC).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, Color(0xFF00FFCC).copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "v$currentVersionName",
                                color = Color(0xFF00FFCC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        enabled = !isCheckingUpdate,
                        onClick = {
                            isCheckingUpdate = true
                            coroutineScope.launch {
                                when (val res = AppUpdateManager.checkForUpdate(context)) {
                                    is UpdateCheckResult.UpdateAvailable -> {
                                        isCheckingUpdate = false
                                        availableUpdate = res.release
                                    }
                                    is UpdateCheckResult.UpToDate -> {
                                        isCheckingUpdate = false
                                        Toast.makeText(
                                            context,
                                            "Ya tienes la última versión instalada (v$currentVersionName)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is UpdateCheckResult.Error -> {
                                        isCheckingUpdate = false
                                        Toast.makeText(
                                            context,
                                            res.message,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Buscar actualización",
                            tint = if (availableUpdate != null) Color(0xFFFF9800) else Color(0xFF00FFCC),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCheckingUpdate) "Buscando..." else if (availableUpdate != null) "¡Actualizar!" else "Actualizar",
                            color = if (availableUpdate != null) Color(0xFFFF9800) else Color(0xFF00FFCC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color(0xFF00FFCC)
                )
            )
        },
        containerColor = Color(0xFF1E1E1E)
    ) { paddingValues ->
        // ── CUADRÍCULA 2x2 A PANTALLA COMPLETA (SIN HISTORIAL EN EL HOME) ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // FILA SUPERIOR: [1] Importar Video desde Galería | [2] Grabar desde Cámara
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HomeActionGridCard(
                    title = "Importar Video\ndesde Galería",
                    subtitle = "Recorta la repetición, calibra 45 cm y mide VBT",
                    tagText = "GALERÍA • 45 CM",
                    watermarkStyle = WeightliftingWatermarkStyle.SNATCH_OVERHEAD,
                    icon = Icons.Default.VideoLibrary,
                    accentColor = Color(0xFF00FFCC),
                    containerColor = Color(0xFF132421),
                    onClick = { videoPickerLauncher.launch("video/*") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                HomeActionGridCard(
                    title = "Grabar desde\nCámara",
                    subtitle = "Graba tu serie y pasa al análisis concéntrico",
                    tagText = "CAPTURA DIRECTA",
                    watermarkStyle = WeightliftingWatermarkStyle.CLEAN_RACK,
                    icon = Icons.Default.PhotoCamera,
                    accentColor = Color(0xFF00E5FF),
                    containerColor = Color(0xFF122129),
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            launchNormalVideoCamera()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            // FILA INFERIOR: [3] Medición Rápida (Velocidad en Vivo) | [4] Tutorial VBT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HomeActionGridCard(
                    title = "Medición Rápida\n(Velocidad en Vivo)",
                    subtitle = "Medir velocidad de barra al vuelo sin registro",
                    tagText = "TIEMPO REAL ⚡",
                    watermarkStyle = WeightliftingWatermarkStyle.HIGH_PULL_TRAJECTORY,
                    icon = Icons.Default.Bolt,
                    accentColor = Color(0xFFFF9800),
                    containerColor = Color(0xFF261E0F),
                    onClick = onNavigateToQuickCamera,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                HomeActionGridCard(
                    title = "Tutorial VBT",
                    subtitle = "Guía y práctica interactiva con Snatch Olímpico",
                    tagText = "DEMO INTERACTIVO",
                    watermarkStyle = WeightliftingWatermarkStyle.BUMPER_PLATE_45CM,
                    icon = Icons.Default.HelpOutline,
                    accentColor = Color(0xFF00E676),
                    containerColor = Color(0xFF14241B),
                    onClick = {
                        val uri = copyRawVideoToCache(context, R.raw.sample_snatch, "sample_snatch_v4.mp4")
                        onNavigateToVideoAnalysis(uri.toString())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }

    if (showTutorialModal) {
        TutorialGuideDialog(
            step = tutorialStep,
            onStepChange = { tutorialStep = it },
            onDismiss = { showTutorialModal = false },
            initialTopic = selectedTutorialTopic,
            onTryDemoVideo = {
                showTutorialModal = false
                val uri = copyRawVideoToCache(context, R.raw.sample_snatch, "sample_snatch_v4.mp4")
                onNavigateToVideoAnalysis(uri.toString())
            }
        )
    }

    availableUpdate?.let { releaseInfo ->
        AppUpdateDialog(
            release = releaseInfo,
            onDismiss = { availableUpdate = null }
        )
    }
}

enum class WeightliftingWatermarkStyle {
    SNATCH_OVERHEAD,
    CLEAN_RACK,
    HIGH_PULL_TRAJECTORY,
    BUMPER_PLATE_45CM
}

@Composable
fun HomeActionGridCard(
    title: String,
    subtitle: String,
    tagText: String,
    watermarkStyle: WeightliftingWatermarkStyle,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.3.dp, accentColor.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            containerColor.copy(alpha = 0.96f),
                            Color(0xFF0E1417)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // ── ILUSTRACIÓN DE HALTEROFILIA EN TRANSPARENCIA (WATERMARK DE FONDO) ──
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                drawWeightliftingWatermark(
                    style = watermarkStyle,
                    accentColor = accentColor
                )
            }

            // ── CONTENIDO CENTRADO: BADGE + ICONO DESTACADO + TÍTULO Y SUBTÍTULO CENTRADOS ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Pill superior de categoría
                Surface(
                    color = accentColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = tagText,
                        color = accentColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Icono central con doble halo luminoso
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.30f),
                                    accentColor.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                ) {
                    Surface(
                        color = accentColor.copy(alpha = 0.20f),
                        shape = CircleShape,
                        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.70f)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = accentColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Título centrado
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtítulo centrado
                Text(
                    text = subtitle,
                    color = Color(0xFFD1D5DB),
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
        }
    }
}

/**
 * Dibuja ilustraciones vectoriales de Halterofilia (siluetas de levantador, barra olímpica,
 * discos de 45 cm y curva de trayectoria S-pull) en transparencia sobre el fondo de cada tarjeta.
 */
private fun DrawScope.drawWeightliftingWatermark(
    style: WeightliftingWatermarkStyle,
    accentColor: Color
) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.52f

    val ghostColor = accentColor.copy(alpha = 0.085f)
    val highlightGhost = accentColor.copy(alpha = 0.13f)
    val subtleGridColor = accentColor.copy(alpha = 0.045f)

    // Retícula biomecánica sutil de fondo
    val step = w / 5f
    for (i in 1..4) {
        drawLine(
            color = subtleGridColor,
            start = Offset(step * i, h * 0.12f),
            end = Offset(step * i, h * 0.88f),
            strokeWidth = 1f
        )
    }

    when (style) {
        WeightliftingWatermarkStyle.SNATCH_OVERHEAD -> {
            // 1. Levantador en recepción profunda de Arranque (Overhead Squat Snatch)
            val barY = h * 0.24f
            val barLeft = w * 0.10f
            val barRight = w * 0.90f

            // Barra olímpica sobre la cabeza
            drawLine(
                color = highlightGhost,
                start = Offset(barLeft, barY),
                end = Offset(barRight, barY),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )

            // Discos olímpicos dobles a cada lado (45 cm)
            val plateOuterH = h * 0.16f
            val plateInnerH = h * 0.13f
            // Izquierda
            drawRoundRect(
                color = highlightGhost,
                topLeft = Offset(barLeft + w * 0.03f, barY - plateOuterH / 2f),
                size = Size(w * 0.045f, plateOuterH),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = ghostColor,
                topLeft = Offset(barLeft + w * 0.085f, barY - plateInnerH / 2f),
                size = Size(w * 0.035f, plateInnerH),
                cornerRadius = CornerRadius(5f, 5f)
            )
            // Derecha
            drawRoundRect(
                color = highlightGhost,
                topLeft = Offset(barRight - w * 0.075f, barY - plateOuterH / 2f),
                size = Size(w * 0.045f, plateOuterH),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = ghostColor,
                topLeft = Offset(barRight - w * 0.12f, barY - plateInnerH / 2f),
                size = Size(w * 0.035f, plateInnerH),
                cornerRadius = CornerRadius(5f, 5f)
            )

            // Silueta estilizada del halterófilo en sentadilla de arranque
            val headCenter = Offset(cx, h * 0.42f)
            drawCircle(color = highlightGhost, radius = w * 0.07f, center = headCenter)

            // Brazos en agarre ancho de Snatch hacia la barra
            val leftShoulder = Offset(cx - w * 0.10f, h * 0.50f)
            val rightShoulder = Offset(cx + w * 0.10f, h * 0.50f)
            drawLine(
                color = highlightGhost,
                start = leftShoulder,
                end = Offset(cx - w * 0.25f, barY),
                strokeWidth = 7f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = highlightGhost,
                start = rightShoulder,
                end = Offset(cx + w * 0.25f, barY),
                strokeWidth = 7f,
                cap = StrokeCap.Round
            )

            // Torso y piernas en sentadilla profunda
            val hipCenter = Offset(cx, h * 0.68f)
            drawLine(
                color = highlightGhost,
                start = Offset(cx, h * 0.49f),
                end = hipCenter,
                strokeWidth = 14f,
                cap = StrokeCap.Round
            )
            val leftKnee = Offset(cx - w * 0.18f, h * 0.66f)
            val rightKnee = Offset(cx + w * 0.18f, h * 0.66f)
            drawLine(color = ghostColor, start = hipCenter, end = leftKnee, strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(color = ghostColor, start = hipCenter, end = rightKnee, strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(color = ghostColor, start = leftKnee, end = Offset(cx - w * 0.15f, h * 0.84f), strokeWidth = 9f, cap = StrokeCap.Round)
            drawLine(color = ghostColor, start = rightKnee, end = Offset(cx + w * 0.15f, h * 0.84f), strokeWidth = 9f, cap = StrokeCap.Round)

            // Curva en "S" de trayectoria VBT al costado
            val sPath = Path().apply {
                moveTo(w * 0.80f, h * 0.82f)
                cubicTo(w * 0.74f, h * 0.62f, w * 0.86f, h * 0.42f, w * 0.78f, barY)
            }
            drawPath(
                path = sPath,
                color = highlightGhost,
                style = Stroke(width = 3.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            )
        }

        WeightliftingWatermarkStyle.CLEAN_RACK -> {
            // 2. Levantador en recepción de Cargada (Front Rack Clean) + visor de cámara
            val barY = h * 0.46f
            val barLeft = w * 0.08f
            val barRight = w * 0.92f

            // Esquinas de encuadre de cámara de video
            val cLen = w * 0.10f
            val m = w * 0.08f
            val cornerStroke = 3.5f
            // Arriba-Izquierda
            drawLine(highlightGhost, Offset(m, h * 0.14f), Offset(m + cLen, h * 0.14f), cornerStroke)
            drawLine(highlightGhost, Offset(m, h * 0.14f), Offset(m, h * 0.14f + cLen), cornerStroke)
            // Arriba-Derecha
            drawLine(highlightGhost, Offset(w - m, h * 0.14f), Offset(w - m - cLen, h * 0.14f), cornerStroke)
            drawLine(highlightGhost, Offset(w - m, h * 0.14f), Offset(w - m, h * 0.14f + cLen), cornerStroke)
            // Abajo-Izquierda
            drawLine(highlightGhost, Offset(m, h * 0.86f), Offset(m + cLen, h * 0.86f), cornerStroke)
            drawLine(highlightGhost, Offset(m, h * 0.86f), Offset(m, h * 0.86f - cLen), cornerStroke)
            // Abajo-Derecha
            drawLine(highlightGhost, Offset(w - m, h * 0.86f), Offset(w - m - cLen, h * 0.86f), cornerStroke)
            drawLine(highlightGhost, Offset(w - m, h * 0.86f), Offset(w - m, h * 0.86f - cLen), cornerStroke)

            // Barra en los hombros (Front Rack)
            drawLine(
                color = highlightGhost,
                start = Offset(barLeft, barY),
                end = Offset(barRight, barY),
                strokeWidth = 5.5f,
                cap = StrokeCap.Round
            )
            val plateH = h * 0.18f
            drawRoundRect(
                color = highlightGhost,
                topLeft = Offset(barLeft + w * 0.03f, barY - plateH / 2f),
                size = Size(w * 0.05f, plateH),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = highlightGhost,
                topLeft = Offset(barRight - w * 0.08f, barY - plateH / 2f),
                size = Size(w * 0.05f, plateH),
                cornerRadius = CornerRadius(6f, 6f)
            )

            // Silueta de atleta en Front Squat / Clean
            drawCircle(color = highlightGhost, radius = w * 0.07f, center = Offset(cx, h * 0.35f))
            drawLine(
                color = ghostColor,
                start = Offset(cx, h * 0.44f),
                end = Offset(cx, h * 0.67f),
                strokeWidth = 15f,
                cap = StrokeCap.Round
            )
            // Codos altos de Clean
            drawLine(color = highlightGhost, start = Offset(cx - w * 0.10f, barY), end = Offset(cx - w * 0.20f, barY - h * 0.04f), strokeWidth = 7f, cap = StrokeCap.Round)
            drawLine(color = highlightGhost, start = Offset(cx + w * 0.10f, barY), end = Offset(cx + w * 0.20f, barY - h * 0.04f), strokeWidth = 7f, cap = StrokeCap.Round)
            // Piernas
            val hip = Offset(cx, h * 0.67f)
            drawLine(color = ghostColor, start = hip, end = Offset(cx - w * 0.16f, h * 0.84f), strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(color = ghostColor, start = hip, end = Offset(cx + w * 0.16f, h * 0.84f), strokeWidth = 10f, cap = StrokeCap.Round)
        }

        WeightliftingWatermarkStyle.HIGH_PULL_TRAJECTORY -> {
            // 3. Medición en Vivo: Disco Olímpico Lateral + Curva de Velocidad Pico VBT y Vectores
            val plateCenter = Offset(cx, h * 0.30f)
            val outerR = w * 0.23f
            drawCircle(
                color = highlightGhost,
                radius = outerR,
                center = plateCenter,
                style = Stroke(width = 6f)
            )
            drawCircle(
                color = ghostColor,
                radius = outerR * 0.62f,
                center = plateCenter,
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = highlightGhost,
                radius = outerR * 0.18f,
                center = plateCenter
            )

            // Onda cinemática de velocidad en tiempo real en la parte inferior
            val wavePath = Path().apply {
                moveTo(w * 0.10f, h * 0.78f)
                cubicTo(w * 0.28f, h * 0.76f, w * 0.35f, h * 0.54f, w * 0.50f, h * 0.54f)
                cubicTo(w * 0.65f, h * 0.54f, w * 0.72f, h * 0.76f, w * 0.90f, h * 0.78f)
            }
            drawPath(
                path = wavePath,
                color = highlightGhost,
                style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Punto de Peak Velocity
            drawCircle(
                color = accentColor.copy(alpha = 0.22f),
                radius = 10f,
                center = Offset(w * 0.50f, h * 0.54f)
            )
        }

        WeightliftingWatermarkStyle.BUMPER_PLATE_45CM -> {
            // 4. Tutorial VBT: Disco Olímpico IWF de 450 mm (45 cm) con cotas de calibración
            val ringCenter = Offset(cx, cy)
            val outerR = w * 0.33f
            drawCircle(
                color = highlightGhost,
                radius = outerR,
                center = ringCenter,
                style = Stroke(width = 8f)
            )
            drawCircle(
                color = ghostColor,
                radius = outerR * 0.72f,
                center = ringCenter,
                style = Stroke(width = 3.5f)
            )
            drawCircle(
                color = highlightGhost,
                radius = outerR * 0.22f,
                center = ringCenter,
                style = Stroke(width = 4f)
            )

            // Líneas de calibración de 45 cm (arriba y abajo del disco)
            val topY = ringCenter.y - outerR
            val botY = ringCenter.y + outerR
            drawLine(
                color = highlightGhost,
                start = Offset(w * 0.14f, topY),
                end = Offset(w * 0.86f, topY),
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
            drawLine(
                color = highlightGhost,
                start = Offset(w * 0.14f, botY),
                end = Offset(w * 0.86f, botY),
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
        }
    }
}

@Composable
fun MetricHighlightCard(title: String, value: String, date: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                color = Color(0xFF00FFCC),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(date, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SessionItemCard(
    session: Session,
    athleteName: String? = null,
    onDelete: () -> Unit
) {
    var showVideoDialog by remember { mutableStateOf(false) }
    var showPdfDialog by remember { mutableStateOf(false) }
    val hasVideo = !session.videoExportPath.isNullOrEmpty() && File(session.videoExportPath).exists()
    val hasPdf = !session.pdfReportPath.isNullOrEmpty() && File(session.pdfReportPath).exists()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isJump = session.activityType.startsWith("JUMP") || session.activityType.contains("Salto", ignoreCase = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isJump) Icons.Default.Height else Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            if (session.activityType == "JUMP") "Salto Vertical" else session.activityType,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val athleteSub = if (!athleteName.isNullOrBlank()) "$athleteName • ${formatDate(session.timestamp)}" else formatDate(session.timestamp)
                        Text(
                            athleteSub,
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val valueStr = if (isJump) {
                        "${String.format("%.1f", session.result * 100)} cm"
                    } else {
                        "${String.format("%.2f", session.result)} m/s"
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            valueStr,
                            color = Color(0xFF00FFCC),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isJump && session.powerWatts != null) {
                            Text(
                                "${session.powerWatts.toInt()} W (${String.format("%.1f", session.powerWattsPerKg ?: 0.0)} W/kg)",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (session.activityType.startsWith("VBT")) {
                            val sub = buildList {
                                if (session.loadKg != null) add("${session.loadKg.toInt()} kg")
                                if (session.horizontalDeviationCm != null) add("Desv: ${String.format("%.1f", session.horizontalDeviationCm)} cm")
                            }.joinToString(" • ")
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub,
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Eliminar",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (hasVideo || hasPdf) {
                Divider(color = Color(0xFF333333), thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasVideo) {
                        OutlinedButton(
                            onClick = { showVideoDialog = true },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Ver Video", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ver Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (hasPdf) {
                        OutlinedButton(
                            onClick = {
                                showPdfDialog = true
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Informe PDF", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Informe PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showVideoDialog && hasVideo) {
        SavedVideoPlayerDialog(
            videoFile = File(session.videoExportPath!!),
            title = session.activityType,
            onDismiss = { showVideoDialog = false }
        )
    }

    if (showPdfDialog && hasPdf) {
        SavedPdfReportDialog(
            pdfFile = File(session.pdfReportPath!!),
            title = "Informe PDF • ${session.activityType}",
            onDismiss = { showPdfDialog = false }
        )
    }
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun copyRawVideoToCache(context: Context, rawResId: Int, fileName: String): Uri {
    val file = File(context.cacheDir, fileName)
    try {
        context.resources.openRawResource(rawResId).use { input ->
            val rawBytes = input.readBytes()
            if (!file.exists() || file.length() != rawBytes.size.toLong()) {
                file.writeBytes(rawBytes)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return Uri.fromFile(file)
}
