package com.speedtracker.presentation.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Videocam
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import com.speedtracker.core.report.BiomechanicalPdfReportGenerator
import com.speedtracker.presentation.history.SavedVideoPlayerDialog
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.speedtracker.R
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    userRepository: UserRepository,
    onNavigateToCamera: () -> Unit,
    onNavigateToQuickCamera: () -> Unit,
    onNavigateToVideoAnalysis: (String) -> Unit,
    onChangeAthlete: () -> Unit
) {
    val activeUser = remember { userRepository.getActiveUser() }
    var sessions by remember { mutableStateOf(userRepository.getSessionsForActiveUser()) }

    val maxJump = sessions.filter { it.activityType == "JUMP" }.maxByOrNull { it.result }
    val maxVBT = sessions.filter { it.activityType.startsWith("VBT") }.maxByOrNull { it.result }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onNavigateToVideoAnalysis(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speed Tracker", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onChangeAthlete) {
                        Icon(
                            Icons.Default.SwitchAccount,
                            contentDescription = "Cambiar Atleta",
                            tint = Color(0xFF00FFCC)
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Tarjeta de Perfil Atleta
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF262626)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFF00FFCC),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = activeUser?.name ?: "Atleta Invitado",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Peso: ${activeUser?.bodyWeightKg ?: 70.0} Kg  •  ${sessions.size} registros",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        TextButton(onClick = onChangeAthlete) {
                            Text("Cambiar", color = Color(0xFF00FFCC))
                        }
                    }
                }
            }

            // Acceso Rápido: Modo Express Sin Registro
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToQuickCamera() },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2211)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFF9800))),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Medición Rápida (Sin Registro)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Medir saltos o barra en vivo al vuelo sin guardar en historial",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Récords Personales (Personal Bests)
            item {
                Text(
                    text = "Mejores Marcas (PB)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.LightGray,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricHighlightCard(
                        title = "Salto Vertical",
                        value = if (maxJump != null) "${String.format("%.1f", maxJump.result * 100)} cm" else "-- cm",
                        date = if (maxJump != null) formatDate(maxJump.timestamp) else "Sin datos",
                        modifier = Modifier.weight(1f)
                    )

                    MetricHighlightCard(
                        title = "Pico VBT",
                        value = if (maxVBT != null) "${String.format("%.2f", maxVBT.result)} m/s" else "-- m/s",
                        date = if (maxVBT != null) formatDate(maxVBT.timestamp) else "Sin datos",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Acceso Directo: Video de Ejemplo y Tutorial Guiado
            item {
                val context = LocalContext.current
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val uri = copyRawVideoToCache(context, R.raw.sample_snatch, "sample_snatch_v4.mp4")
                            onNavigateToVideoAnalysis(uri.toString())
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF142420)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                tint = Color(0xFF00FFCC),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "🎬 Video de Ejemplo y Tutorial",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = Color(0xFF00FFCC).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "DEMO",
                                            color = Color(0xFF00FFCC),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "Snatch olímpico lateral de Lu Xiaojun: practica recorte, calibración a 45 cm y telemetría",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Botón Importar Video
            item {
                OutlinedButton(
                    onClick = { videoPickerLauncher.launch("video/*") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = Color(0xFF00FFCC)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Importar Video desde Galería")
                }
            }

            // Botón Cámara en Vivo (mismo ancho y altura que Importar Video)
            item {
                Button(
                    onClick = onNavigateToCamera,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FFCC),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cámara en Vivo", fontWeight = FontWeight.Bold)
                }
            }

            // Historial de Sesiones
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Historial Reciente",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.LightGray,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (sessions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No hay registros aún",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Realiza un salto o levantamiento en vivo con la cámara, o importa un video pregrabado.",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(sessions) { session ->
                    SessionItemCard(
                        session = session,
                        onDelete = {
                            userRepository.deleteSession(session.id)
                            sessions = userRepository.getSessionsForActiveUser()
                        }
                    )
                }
            }

            // Espacio final para que el FAB no tape el último item
            item {
                Spacer(modifier = Modifier.height(72.dp))
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
fun SessionItemCard(session: Session, onDelete: () -> Unit) {
    val context = LocalContext.current
    var showVideoDialog by remember { mutableStateOf(false) }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (session.activityType == "JUMP") Icons.Default.Height else Icons.Default.FitnessCenter,
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
                        Text(
                            formatDate(session.timestamp),
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val valueStr = if (session.activityType == "JUMP") {
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
                        if (session.activityType == "JUMP" && session.powerWatts != null) {
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
                                BiomechanicalPdfReportGenerator.openOrSharePdf(context, File(session.pdfReportPath!!))
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
