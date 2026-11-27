package com.speedtracker.presentation.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var showServiceInfoDialog by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    var selectedTutorialTopic by remember { mutableStateOf("VBT") }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    // Estado de comprobación de actualización desde GitHub (Morodor/speedtracker)
    var availableUpdate by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val currentVersionName = remember { AppUpdateManager.getAppVersionName(context) }

    // Comprobar automáticamente al abrir el Home si hay una versión más nueva publicada en GitHub
    LaunchedEffect(Unit) {
        when (val result = AppUpdateManager.checkForUpdate(context)) {
            is UpdateCheckResult.UpdateAvailable -> {
                availableUpdate = result.release
                showUpdateDialog = true
            }
            else -> {
                availableUpdate = null
                showUpdateDialog = false
            }
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
                    // El botón Actualizar SOLO aparece cuando realmente existe una versión más nueva disponible en GitHub
                    val pendingRelease = availableUpdate
                    if (pendingRelease != null) {
                        TextButton(
                            onClick = {
                                showUpdateDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "Nueva actualización disponible",
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "¡Actualizar!",
                                color = Color(0xFFFF9800),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Breve explicación de la app bajo el título + botón de información del servicio
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF14181C),
                border = BorderStroke(1.dp, Color(0xFF00E5A3).copy(alpha = 0.22f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Análisis biomecánico VBT para halterofilia: mide velocidad, potencia y trayectoria de barra en video o en vivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0D5DD),
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        onClick = { showServiceInfoDialog = true },
                        shape = CircleShape,
                        color = Color(0xFF00E5A3).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFF00E5A3).copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Información del servicio",
                            tint = Color(0xFF00E5A3),
                            modifier = Modifier
                                .padding(7.dp)
                                .size(18.dp)
                        )
                    }
                }
            }

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
                    backgroundImageResId = R.drawable.bg_home_import,
                    icon = Icons.Default.VideoLibrary,
                    accentColor = Color(0xFF00FFCC),
                    containerColor = Color(0xFF101D1A),
                    onClick = { videoPickerLauncher.launch("video/*") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                HomeActionGridCard(
                    title = "Grabar desde\nCámara",
                    subtitle = "Graba tu serie y pasa al análisis concéntrico",
                    tagText = "CAPTURA DIRECTA",
                    backgroundImageResId = R.drawable.bg_home_camera,
                    icon = Icons.Default.PhotoCamera,
                    accentColor = Color(0xFF00E5FF),
                    containerColor = Color(0xFF101B22),
                    onClick = onNavigateToCamera,
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
                    backgroundImageResId = R.drawable.bg_home_live,
                    icon = Icons.Default.Bolt,
                    accentColor = Color(0xFFFF9800),
                    containerColor = Color(0xFF20180B),
                    onClick = onNavigateToQuickCamera,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                HomeActionGridCard(
                    title = "Tutorial VBT",
                    subtitle = "Guía y práctica interactiva con Snatch Olímpico",
                    tagText = "DEMO INTERACTIVO",
                    backgroundImageResId = R.drawable.bg_home_tutorial,
                    icon = Icons.Default.HelpOutline,
                    accentColor = Color(0xFF00E676),
                    containerColor = Color(0xFF101E16),
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

    if (showUpdateDialog && availableUpdate != null) {
        AppUpdateDialog(
            release = availableUpdate!!,
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showServiceInfoDialog) {
        ServiceInfoDialog(
            onDismiss = { showServiceInfoDialog = false }
        )
    }
}

@Composable
fun HomeActionGridCard(
    title: String,
    subtitle: String,
    tagText: String,
    backgroundImageResId: Int,
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
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // ── FOTOGRAFÍA REAL DE HALTEROFILIA CON MAYOR VISIBILIDAD DE FONDO ──
            Image(
                painter = painterResource(id = backgroundImageResId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f)
            )

            // ── VELO DEGRADADO SUAVE PARA QUE LA IMAGEN SE VEA CLARA Y EL TEXTO RESALTE ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                containerColor.copy(alpha = 0.50f),
                                containerColor.copy(alpha = 0.28f),
                                containerColor.copy(alpha = 0.68f)
                            )
                        )
                    )
            )

            // ── CONTENIDO CON ALINEACIÓN HORIZONTAL IDÉNTICA EN LAS 4 TARJETAS ──
            // Cada elemento (Pill, Icono, Título y Subtítulo) ocupa un contenedor de altura fija
            // para que los iconos, títulos y subtítulos queden exactamente a la misma altura en cada fila.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 1. Franja fija para el Pill superior (26.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color(0xAA0B1014),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(0.9.dp, accentColor.copy(alpha = 0.65f))
                    ) {
                        Text(
                            text = tagText,
                            color = accentColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Franja fija para el Icono central con doble halo luminoso (68.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(68.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.35f),
                                    accentColor.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                ) {
                    Surface(
                        color = Color(0xCC0D1418),
                        shape = CircleShape,
                        border = BorderStroke(1.6.dp, accentColor),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = accentColor,
                                modifier = Modifier.size(27.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Franja amplia para el Título centrado (64.dp -> caben hasta 3 líneas completas sin recortes)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp,
                        lineHeight = 17.5.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 4. Franja amplia para el Subtítulo centrado (54.dp -> caben hasta 3 líneas completas sin recortes)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = subtitle,
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }
            }
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

@Composable
private fun ServiceInfoDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF14181C),
            border = BorderStroke(1.dp, Color(0xFF00E5A3).copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF00E5A3).copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFF00E5A3),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Sobre Speed Tracker",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Servicio Biomecánico VBT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF00E5A3)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color(0xFF98A2B3)
                        )
                    }
                }

                Text(
                    text = "Speed Tracker transforma tu teléfono en un laboratorio de entrenamiento basado en velocidad (VBT) para halterofilia olímpica y fuerza.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD0D5DD),
                    lineHeight = 18.sp
                )

                ServiceFeatureItem(
                    title = "Análisis Biomecánico de Video + Complex",
                    description = "Importa o graba tus levantamientos, calibra el disco olímpico de 45 cm y analiza velocidad pico, velocidad media propulsiva (VMP), potencia y trayectoria frame por frame. Crea series o complex combinando varios ejercicios."
                )
                ServiceFeatureItem(
                    title = "Medición Rápida en Vivo",
                    description = "Detecta el disco en tiempo real con cámara frontal o trasera, entregando retroalimentación visual y por voz sin guardar datos innecesarios."
                )
                ServiceFeatureItem(
                    title = "Fichas de Atletas e Informes PDF",
                    description = "Registra marcas por atleta, compara la evolución de cada métrica y exporta informes biomecánicos en PDF listos para compartir."
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5A3),
                        contentColor = Color(0xFF071310)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Entendido", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ServiceFeatureItem(
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1D232A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5A3)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1),
                lineHeight = 17.sp
            )
        }
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
