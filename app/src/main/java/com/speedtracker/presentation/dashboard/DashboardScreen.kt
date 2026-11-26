package com.speedtracker.presentation.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
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
                    title = "Importar Video desde Galería",
                    subtitle = "Recorta la repetición, calibra 45 cm y mide VBT",
                    icon = Icons.Default.VideoLibrary,
                    accentColor = Color(0xFF00FFCC),
                    containerColor = Color(0xFF182825),
                    onClick = { videoPickerLauncher.launch("video/*") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                HomeActionGridCard(
                    title = "Grabar desde Cámara",
                    subtitle = "Graba tu serie y pasa al análisis concéntrico",
                    icon = Icons.Default.PhotoCamera,
                    accentColor = Color(0xFF00E5FF),
                    containerColor = Color(0xFF16242C),
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
                    title = "Medición Rápida (Velocidad en Vivo)",
                    subtitle = "Medir velocidad de barra al vuelo sin registro",
                    icon = Icons.Default.Bolt,
                    accentColor = Color(0xFFFF9800),
                    containerColor = Color(0xFF2B2211),
                    onClick = onNavigateToQuickCamera,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                HomeActionGridCard(
                    title = "Tutorial VBT",
                    subtitle = "Guía y práctica interactiva con Snatch Olímpico",
                    icon = Icons.Default.HelpOutline,
                    accentColor = Color(0xFF00E676),
                    containerColor = Color(0xFF17261E),
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

@Composable
fun HomeActionGridCard(
    title: String,
    subtitle: String,
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
        border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = accentColor.copy(alpha = 0.18f),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.LightGray.copy(alpha = 0.90f),
                    fontSize = 11.sp,
                    lineHeight = 14.5.sp,
                    maxLines = 3
                )
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
