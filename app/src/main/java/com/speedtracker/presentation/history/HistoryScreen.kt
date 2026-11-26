package com.speedtracker.presentation.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.speedtracker.core.report.BiomechanicalPdfReportGenerator
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    userRepository: UserRepository
) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(userRepository.getSessions().sortedByDescending { it.timestamp }) }
    val allUsers = remember(sessions) { userRepository.getUsers() }
    val usersMap = remember(allUsers) { allUsers.associateBy { it.id } }

    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "JUMP", "VBT"
    var selectedAthleteFilterId by remember { mutableStateOf("ALL_ATHLETES") } // Por defecto: Todos los deportistas
    var videoSessionToPlay by remember { mutableStateOf<Session?>(null) }
    var pdfSessionToView by remember { mutableStateOf<Session?>(null) }

    val athleteFilteredSessions = remember(sessions, selectedAthleteFilterId) {
        if (selectedAthleteFilterId == "ALL_ATHLETES") {
            sessions
        } else {
            sessions.filter { it.userId == selectedAthleteFilterId }
        }
    }

    val filteredSessions = remember(athleteFilteredSessions, selectedFilter) {
        when (selectedFilter) {
            "SNATCH" -> athleteFilteredSessions.filter {
                it.activityType.contains("Snatch", ignoreCase = true) ||
                it.activityType.contains("Arranque", ignoreCase = true)
            }
            "CLEAN_JERK" -> athleteFilteredSessions.filter {
                it.activityType.contains("Clean", ignoreCase = true) ||
                it.activityType.contains("Cargada", ignoreCase = true) ||
                it.activityType.contains("Jerk", ignoreCase = true) ||
                it.activityType.contains("Envión", ignoreCase = true) ||
                it.activityType.contains("Push", ignoreCase = true) ||
                it.activityType.contains("Dos Tiempos", ignoreCase = true)
            }
            else -> athleteFilteredSessions
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial Completo", fontWeight = FontWeight.Bold) },
                actions = {
                    if (sessions.isNotEmpty()) {
                        IconButton(onClick = { userRepository.shareSessionsCsv(context) }) {
                            Icon(Icons.Default.Share, contentDescription = "Exportar CSV", tint = Color(0xFF00FFCC))
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Los 3 botones de filtro de Halterofilia del MISMO TAMAÑO exacto
            item {
                val categoryItems = listOf(
                    "ALL" to "Todos (${athleteFilteredSessions.size})",
                    "SNATCH" to "Arranque",
                    "CLEAN_JERK" to "Cargada / Envión"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categoryItems.forEach { (key, label) ->
                        val isSelected = selectedFilter == key
                        Surface(
                            onClick = { selectedFilter = key },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF262626),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF00FFCC) else Color(0xFF3E3E3E)
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.Black else Color.LightGray,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // 2. Barra inferior de filtro por Deportista (por defecto: Todos los deportistas)
            item {
                Surface(
                    color = Color(0xFF181D22),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF2B3642)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFF00FFCC),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Filtrar por Deportista:",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isAllSelected = selectedAthleteFilterId == "ALL_ATHLETES"
                            Surface(
                                onClick = { selectedAthleteFilterId = "ALL_ATHLETES" },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAllSelected) Color(0xFF00FFCC).copy(alpha = 0.20f) else Color(0xFF222932),
                                border = BorderStroke(
                                    1.dp,
                                    if (isAllSelected) Color(0xFF00FFCC) else Color(0xFF334155)
                                )
                            ) {
                                Text(
                                    text = "Todos los deportistas (${sessions.size})",
                                    color = if (isAllSelected) Color(0xFF00FFCC) else Color.LightGray,
                                    fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            allUsers.forEach { user ->
                                val count = sessions.count { it.userId == user.id }
                                val isSel = selectedAthleteFilterId == user.id
                                Surface(
                                    onClick = { selectedAthleteFilterId = user.id },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Color(0xFF00FFCC).copy(alpha = 0.20f) else Color(0xFF222932),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSel) Color(0xFF00FFCC) else Color(0xFF334155)
                                    )
                                ) {
                                    Text(
                                        text = "${user.name} ($count)",
                                        color = if (isSel) Color(0xFF00FFCC) else Color.LightGray,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (filteredSessions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Sin registros", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("No hay mediciones guardadas en este filtro.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(filteredSessions) { session ->
                    val athlete = usersMap[session.userId]
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
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        if (session.activityType == "JUMP") Icons.Default.Height else Icons.Default.FitnessCenter,
                                        contentDescription = null,
                                        tint = Color(0xFF00FFCC),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            athlete?.name ?: "Atleta Invitado",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            session.activityType,
                                            color = Color(0xFF00FFCC),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            formatHistoryDate(session.timestamp),
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        val valStr = if (session.activityType == "JUMP") {
                                            "${String.format(Locale.US, "%.1f", session.result * 100)} cm"
                                        } else {
                                            "${String.format(Locale.US, "%.2f", session.result)} m/s"
                                        }
                                        Text(
                                            valStr,
                                            color = Color(0xFF00FFCC),
                                            fontWeight = FontWeight.Black,
                                            style = MaterialTheme.typography.titleMedium
                                        )

                                        if (session.activityType == "JUMP") {
                                            val subtitle = when {
                                                session.rsi != null && session.contactTimeMs != null ->
                                                    "${session.jumpType ?: "DJ"} • RSI: ${String.format(Locale.US, "%.2f", session.rsi)} • Tc: ${session.contactTimeMs}ms"
                                                session.powerWatts != null ->
                                                    "${session.jumpType ?: "Salto"} • ${session.powerWatts.toInt()} W"
                                                else -> session.jumpType ?: ""
                                            }
                                            if (subtitle.isNotEmpty()) {
                                                Text(subtitle, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                            }
                                        } else {
                                            val details = buildList {
                                                if (session.loadKg != null) add("${session.loadKg.toInt()} kg")
                                                if (session.meanVelocity != null) add("MV: ${String.format(Locale.US, "%.2f", session.meanVelocity)}")
                                                if (session.horizontalDeviationCm != null) add("Desv: ${String.format(Locale.US, "%.1f", session.horizontalDeviationCm)}cm")
                                            }.joinToString(" • ")

                                            if (details.isNotEmpty()) {
                                                Text(details, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    IconButton(onClick = {
                                        userRepository.deleteSession(session.id)
                                        sessions = userRepository.getSessions().sortedByDescending { it.timestamp }
                                    }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Borrar", tint = Color.Gray)
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
                                            onClick = { videoSessionToPlay = session },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                                        ) {
                                            Icon(Icons.Default.PlayCircle, contentDescription = "Ver Video", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Ver Video Analizado", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (hasPdf) {
                                        OutlinedButton(
                                            onClick = {
                                                pdfSessionToView = session
                                            },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Ver Informe PDF", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Informe PDF", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    videoSessionToPlay?.let { session ->
        val vFile = session.videoExportPath?.let { File(it) }
        if (vFile != null && vFile.exists()) {
            val athleteName = usersMap[session.userId]?.name ?: "Atleta"
            SavedVideoPlayerDialog(
                videoFile = vFile,
                title = "${session.activityType} • $athleteName",
                onDismiss = { videoSessionToPlay = null }
            )
        } else {
            videoSessionToPlay = null
        }
    }

    pdfSessionToView?.let { session ->
        val pFile = session.pdfReportPath?.let { File(it) }
        if (pFile != null && pFile.exists()) {
            val athleteName = usersMap[session.userId]?.name ?: "Atleta"
            SavedPdfReportDialog(
                pdfFile = pFile,
                title = "Informe PDF • ${session.activityType} ($athleteName)",
                onDismiss = { pdfSessionToView = null }
            )
        } else {
            pdfSessionToView = null
        }
    }
}

@Composable
fun SavedVideoPlayerDialog(
    videoFile: File,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(true) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            try { videoViewRef?.stopPlayback() } catch (e: Exception) {}
            onDismiss()
        },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF161A20),
            border = BorderStroke(1.dp, Color(0x5500FFCC))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            try { videoViewRef?.stopPlayback() } catch (e: Exception) {}
                            onDismiss()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.fromFile(videoFile))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                    isPlaying = true
                                }
                                videoViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            videoViewRef?.let { vv ->
                                if (vv.isPlaying) {
                                    vv.pause()
                                    isPlaying = false
                                } else {
                                    vv.start()
                                    isPlaying = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF262D37),
                            contentColor = Color(0xFF00FFCC)
                        )
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPlaying) "Pausar" else "Reproducir", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            openOrShareVideoFile(context, videoFile)
                        },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartir Video", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

fun openOrShareVideoFile(context: Context, videoFile: File) {
    try {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartir o Abrir Video Analizado").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun SavedPdfReportDialog(
    pdfFile: File,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var renderedBitmap by remember(pdfFile) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(pdfFile) { mutableStateOf(true) }

    LaunchedEffect(pdfFile) {
        isLoading = true
        renderedBitmap = BiomechanicalPdfReportGenerator.renderPdfPageToBitmap(pdfFile)
        isLoading = false
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF161A20),
            border = BorderStroke(1.dp, Color(0x5500FFCC))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(470.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF8FAFC)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = renderedBitmap
                    if (isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Renderizando Informe Biomecánico...", color = Color(0xFF0F172A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (bmp != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Informe PDF",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Text(
                            "Listo para abrir o compartir en visor externo.",
                            color = Color(0xFF0F172A),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            BiomechanicalPdfReportGenerator.openPdfInExternalViewer(context, pdfFile)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF262D37),
                            contentColor = Color(0xFF00FFCC)
                        )
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Visor Externo", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                    }

                    Button(
                        onClick = {
                            BiomechanicalPdfReportGenerator.openOrSharePdf(context, pdfFile)
                        },
                        modifier = Modifier.weight(1.15f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartir PDF", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

fun formatHistoryDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
