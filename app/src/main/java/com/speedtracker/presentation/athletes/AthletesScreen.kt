package com.speedtracker.presentation.athletes

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
import com.speedtracker.domain.model.User
import com.speedtracker.presentation.history.SavedPdfReportDialog
import com.speedtracker.presentation.history.SavedVideoPlayerDialog
import com.speedtracker.presentation.history.formatHistoryDate
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthletesScreen(
    userRepository: UserRepository
) {
    val context = LocalContext.current
    var users by remember { mutableStateOf(userRepository.getUsers()) }
    var allSessions by remember { mutableStateOf(userRepository.getSessions().sortedByDescending { it.timestamp }) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var userToEdit by remember { mutableStateOf<User?>(null) }
    var userToDelete by remember { mutableStateOf<User?>(null) }
    var expandedUserId by remember { mutableStateOf<String?>(users.firstOrNull()?.id) }
    var videoSessionToPlay by remember { mutableStateOf<Session?>(null) }
    var pdfSessionToView by remember { mutableStateOf<Session?>(null) }
    var generatedAthleteReportFile by remember { mutableStateOf<Pair<File, String>?>(null) }

    // Estado para la Evolución e Informe de un Mismo Atleta
    var athleteForProgression by remember { mutableStateOf<User?>(null) }

    fun refreshUsers() {
        users = userRepository.getUsers()
        allSessions = userRepository.getSessions().sortedByDescending { it.timestamp }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fichas de Atletas", fontWeight = FontWeight.Bold) },
                actions = {
                    if (users.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                val target = users.find { it.id == expandedUserId } ?: users.firstOrNull()
                                athleteForProgression = target
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF1B322C),
                                contentColor = Color(0xFF00FFCC)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.TrendingUp,
                                contentDescription = "Evolución del Atleta",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Evolución e Informe", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color(0xFF00FFCC)
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = Color(0xFF00FFCC),
                contentColor = Color.Black
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Nuevo")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Nuevo Atleta", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Consulta el historial de evaluaciones, marcas personales, informes PDF y videos de cada atleta.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            if (users.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No hay atletas creados", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Toca '+ Nuevo Atleta' para comenzar.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(users) { user ->
                    val userSessions = allSessions.filter { it.userId == user.id }
                    val isExpanded = expandedUserId == user.id

                    val maxVbtSession = userSessions.filter { it.activityType.startsWith("VBT") }.maxByOrNull { it.result }
                    val maxVmpValue = userSessions.mapNotNull { it.vmpVelocity ?: it.meanVelocity }.maxOrNull()
                    val maxPowerSession = userSessions.filter { it.powerWatts != null }.maxByOrNull { it.powerWatts ?: 0.0 }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedUserId = if (expandedUserId == user.id) null else user.id
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1B2228)
                        ),
                        border = BorderStroke(
                            1.2.dp,
                            if (isExpanded) Color(0xFF00FFCC).copy(alpha = 0.8f) else Color(0xFF2C394A)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            // Cabecera de la ficha del atleta
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        color = Color(0xFF00FFCC).copy(alpha = 0.15f),
                                        shape = CircleShape,
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = Color(0xFF00FFCC),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            user.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Peso: ${user.bodyWeightKg} Kg  •  ${userSessions.size} registros",
                                            color = Color.LightGray,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { userToEdit = user }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Color.LightGray)
                                    }
                                    IconButton(onClick = { userToDelete = user }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", tint = Color(0xFFFF5252))
                                    }
                                    Icon(
                                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "Expandir ficha",
                                        tint = Color(0xFF00FFCC)
                                    )
                                }
                            }

                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Divider(color = Color(0xFF263342), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(12.dp))

                                // ── SECCIÓN 1: MEJORES MARCAS (PB) DEL ATLETA ──
                                Text(
                                    text = "🏆 Mejores Marcas Personales (PB)",
                                    color = Color(0xFF00FFCC),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // PB Pico VBT
                                    Surface(
                                        color = Color(0xFF141A1F),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Pico VBT", color = Color.LightGray, fontSize = 10.5.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (maxVbtSession != null) "${String.format(Locale.US, "%.2f", maxVbtSession.result)} m/s" else "-- m/s",
                                                color = Color(0xFF00FFCC),
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Text(
                                                if (maxVbtSession != null) formatHistoryDate(maxVbtSession.timestamp) else "Sin registros",
                                                color = Color.Gray,
                                                fontSize = 9.5.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // PB Vel. Propulsiva (VMP)
                                    Surface(
                                        color = Color(0xFF141A1F),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("VMP Propulsiva", color = Color.LightGray, fontSize = 10.5.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (maxVmpValue != null && maxVmpValue > 0.0) "${String.format(Locale.US, "%.2f", maxVmpValue)} m/s" else "-- m/s",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Text(
                                                "Vel. Media",
                                                color = Color.Gray,
                                                fontSize = 9.5.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // PB Potencia Pico
                                    Surface(
                                        color = Color(0xFF141A1F),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF2B3844)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Potencia Pico", color = Color.LightGray, fontSize = 10.5.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (maxPowerSession?.powerWatts != null) "${maxPowerSession.powerWatts.toInt()} W" else "-- W",
                                                color = Color(0xFFFFD600),
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Text(
                                                if (maxPowerSession?.powerWattsPerKg != null) "${String.format(Locale.US, "%.1f", maxPowerSession.powerWattsPerKg)} W/kg" else "Sin registros",
                                                color = Color.Gray,
                                                fontSize = 9.5.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                // ── BOTONES DE ACCIÓN DEL ATLETA: COMPARAR SUS MEDICIONES + GENERAR INFORME PDF DEL ATLETA ──
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = { athleteForProgression = user },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF162C28),
                                            contentColor = Color(0xFF00FFCC)
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Comparar Evolución", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            val pdfFile = generateAthleteSummaryPdfReport(
                                                context = context,
                                                user = user,
                                                userSessions = userSessions
                                            )
                                            if (pdfFile != null) {
                                                generatedAthleteReportFile = pdfFile to "Informe de Evolución • ${user.name}"
                                            } else {
                                                Toast.makeText(context, "Error al generar el informe del atleta", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF162530),
                                            contentColor = Color(0xFF00E5FF)
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Informe del Atleta", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // ── SECCIÓN 2: DATOS REGISTRADOS, INFORMES PDF Y VIDEOS ──
                                Text(
                                    text = "📋 Historial de Evaluaciones, Informes PDF y Videos (${userSessions.size})",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (userSessions.isEmpty()) {
                                    Surface(
                                        color = Color(0xFF14181D),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "Aún no hay mediciones guardadas para ${user.name}.",
                                            color = Color.Gray,
                                            fontSize = 11.5.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        userSessions.forEach { session ->
                                            val hasVideo = !session.videoExportPath.isNullOrEmpty() && File(session.videoExportPath).exists()
                                            val hasPdf = !session.pdfReportPath.isNullOrEmpty() && File(session.pdfReportPath).exists()
                                            val isJump = session.activityType.startsWith("JUMP") || session.activityType.contains("Salto", ignoreCase = true)

                                            Surface(
                                                color = Color(0xFF14181D),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, Color(0xFF2E3842)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                if (session.activityType == "JUMP") "Salto Vertical (${session.jumpType ?: "CMJ"})" else session.activityType,
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 12.5.sp
                                                            )
                                                            val subDetails = buildList {
                                                                add(formatHistoryDate(session.timestamp))
                                                                if (session.loadKg != null && !isJump) add("${session.loadKg.toInt()} kg")
                                                                if (session.powerWatts != null) add("${session.powerWatts.toInt()} W")
                                                                if (session.rsi != null) add("RSI: ${String.format(Locale.US, "%.2f", session.rsi)}")
                                                            }.joinToString(" • ")
                                                            Text(
                                                                subDetails,
                                                                color = Color.Gray,
                                                                fontSize = 10.5.sp
                                                            )
                                                        }
                                                        val valStr = if (isJump) {
                                                            "${String.format(Locale.US, "%.1f", session.result * 100)} cm"
                                                        } else {
                                                            "${String.format(Locale.US, "%.2f", session.result)} m/s"
                                                        }
                                                        Text(
                                                            valStr,
                                                            color = Color(0xFF00FFCC),
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 14.5.sp
                                                        )
                                                    }

                                                    if (hasVideo || hasPdf) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            if (hasVideo) {
                                                                OutlinedButton(
                                                                    onClick = { videoSessionToPlay = session },
                                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                                                    border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                                                                ) {
                                                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text("Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                            if (hasPdf) {
                                                                OutlinedButton(
                                                                    onClick = {
                                                                        pdfSessionToView = session
                                                                    },
                                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                                                    border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                                                                ) {
                                                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(14.dp))
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text("Informe PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }

        // Modal de Evolución Histórica e Informe de un Mismo Atleta
        athleteForProgression?.let { initialAthlete ->
            SingleAthleteProgressionDialog(
                users = users,
                initialUser = initialAthlete,
                allSessions = allSessions,
                onGeneratePdfReport = { targetUser, filteredSessions ->
                    val pdfFile = generateAthleteSummaryPdfReport(
                        context = context,
                        user = targetUser,
                        userSessions = filteredSessions
                    )
                    if (pdfFile != null) {
                        generatedAthleteReportFile = pdfFile to "Informe de Evolución • ${targetUser.name}"
                    } else {
                        Toast.makeText(context, "Error al generar el informe del atleta", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { athleteForProgression = null }
            )
        }

        generatedAthleteReportFile?.let { (file, title) ->
            if (file.exists()) {
                SavedPdfReportDialog(
                    pdfFile = file,
                    title = title,
                    onDismiss = { generatedAthleteReportFile = null }
                )
            } else {
                generatedAthleteReportFile = null
            }
        }

        videoSessionToPlay?.let { session ->
            val vFile = session.videoExportPath?.let { File(it) }
            if (vFile != null && vFile.exists()) {
                val athleteName = users.find { it.id == session.userId }?.name ?: "Atleta"
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
                val athleteName = users.find { it.id == session.userId }?.name ?: "Atleta"
                SavedPdfReportDialog(
                    pdfFile = pFile,
                    title = "Informe PDF • ${session.activityType} ($athleteName)",
                    onDismiss = { pdfSessionToView = null }
                )
            } else {
                pdfSessionToView = null
            }
        }

        // Diálogo Crear Atleta
        if (showCreateDialog) {
            var name by remember { mutableStateOf("") }
            var weight by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Nuevo Atleta") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nombre Completo") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = weight,
                            onValueChange = { weight = it },
                            label = { Text("Peso Corporal (Kg)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val w = weight.toDoubleOrNull() ?: 70.0
                            if (name.isNotBlank()) {
                                val created = userRepository.saveUser(name.trim(), w)
                                refreshUsers()
                                expandedUserId = created.id
                                showCreateDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Crear", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancelar") }
                }
            )
        }

        // Diálogo Editar Atleta
        userToEdit?.let { user ->
            var editName by remember { mutableStateOf(user.name) }
            var editWeight by remember { mutableStateOf(user.bodyWeightKg.toString()) }

            AlertDialog(
                onDismissRequest = { userToEdit = null },
                title = { Text("Editar Atleta") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Nombre") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editWeight,
                            onValueChange = { editWeight = it },
                            label = { Text("Peso Corporal (Kg)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val w = editWeight.toDoubleOrNull() ?: user.bodyWeightKg
                            if (editName.isNotBlank()) {
                                userRepository.updateUser(user.copy(name = editName.trim(), bodyWeightKg = w))
                                refreshUsers()
                                userToEdit = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Guardar Cambios", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { userToEdit = null }) { Text("Cancelar") }
                }
            )
        }

        // Diálogo Confirmar Eliminación
        userToDelete?.let { user ->
            AlertDialog(
                onDismissRequest = { userToDelete = null },
                title = { Text("¿Eliminar Atleta?") },
                text = { Text("Se eliminará a '${user.name}' y todas sus mediciones históricas registradas.") },
                confirmButton = {
                    Button(
                        onClick = {
                            userRepository.deleteUser(user.id)
                            refreshUsers()
                            userToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.White)
                    ) {
                        Text("Eliminar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { userToDelete = null }) { Text("Cancelar") }
                }
            )
        }
    }
}

/**
 * Diálogo interactivo para analizar y comparar la evolución de UN MISMO ATLETA
 * a través de sus nuevas mediciones (con filtro por ejercicio, selector de métrica
 * y botón directo para generar el Informe PDF con todos los datos ingresados del atleta).
 */
@Composable
fun SingleAthleteProgressionDialog(
    users: List<User>,
    initialUser: User,
    allSessions: List<Session>,
    onGeneratePdfReport: (User, List<Session>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedUser by remember { mutableStateOf(initialUser) }
    var selectedMetricType by remember { mutableStateOf("VBT") } // "VBT", "VMP", "POWER", "REL_POWER", "LOAD"
    var selectedExerciseFilter by remember { mutableStateOf("ALL") }

    val userSessionsDesc = remember(selectedUser, allSessions) {
        allSessions.filter { it.userId == selectedUser.id }.sortedByDescending { it.timestamp }
    }

    val availableExercises = remember(userSessionsDesc) {
        userSessionsDesc.map { it.activityType }.distinct()
    }

    val filteredSessionsChronological = remember(userSessionsDesc, selectedExerciseFilter) {
        val base = if (selectedExerciseFilter == "ALL") {
            userSessionsDesc
        } else {
            userSessionsDesc.filter { it.activityType == selectedExerciseFilter }
        }
        base.sortedBy { it.timestamp }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF13171F),
            border = BorderStroke(1.5.dp, Color(0xFF263342))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── HEADER SUPERIOR ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = Color(0xFF00FFCC).copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.TrendingUp,
                                    contentDescription = null,
                                    tint = Color(0xFF00FFCC),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                "Evolución del Atleta",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                "${selectedUser.name} (${selectedUser.bodyWeightKg.toInt()} kg) • ${filteredSessionsChronological.size} mediciones",
                                color = Color(0xFF00FFCC),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(Color(0xFF1E2530), CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    }
                }

                Divider(color = Color(0xFF202A36), thickness = 1.dp)

                // Contenido desplazable vertical
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ── 1. SELECTOR DE UN SOLO ATLETA ──
                    if (users.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Atleta Evaluado:",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                users.forEach { user ->
                                    val isSel = user.id == selectedUser.id
                                    Surface(
                                        onClick = {
                                            selectedUser = user
                                            selectedExerciseFilter = "ALL"
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSel) Color(0xFF00FFCC).copy(alpha = 0.20f) else Color(0xFF1B222C),
                                        border = BorderStroke(
                                            if (isSel) 1.6.dp else 1.dp,
                                            if (isSel) Color(0xFF00FFCC) else Color(0xFF2C394A)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                color = if (isSel) Color(0xFF00FFCC) else Color.Gray,
                                                shape = CircleShape,
                                                modifier = Modifier.size(22.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        user.name.take(1).uppercase(),
                                                        color = Color.Black,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Text(
                                                user.name,
                                                color = if (isSel) Color.White else Color.LightGray,
                                                fontSize = 12.5.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 2. FILTRO POR EJERCICIO DEL ATLETA ──
                    if (availableExercises.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Filtrar Mediciones por Ejercicio:",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val allSelected = selectedExerciseFilter == "ALL"
                                Surface(
                                    onClick = { selectedExerciseFilter = "ALL" },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (allSelected) Color(0xFF00E5FF).copy(alpha = 0.20f) else Color(0xFF1B222C),
                                    border = BorderStroke(1.dp, if (allSelected) Color(0xFF00E5FF) else Color(0xFF2C394A))
                                ) {
                                    Text(
                                        "Todos (${userSessionsDesc.size})",
                                        color = if (allSelected) Color(0xFF00E5FF) else Color.LightGray,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }

                                availableExercises.forEach { exName ->
                                    val isSel = selectedExerciseFilter == exName
                                    val count = userSessionsDesc.count { it.activityType == exName }
                                    Surface(
                                        onClick = { selectedExerciseFilter = exName },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) Color(0xFF00E5FF).copy(alpha = 0.20f) else Color(0xFF1B222C),
                                        border = BorderStroke(1.dp, if (isSel) Color(0xFF00E5FF) else Color(0xFF2C394A))
                                    ) {
                                        Text(
                                            "$exName ($count)",
                                            color = if (isSel) Color(0xFF00E5FF) else Color.LightGray,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 3. SELECTOR DE MÉTRICA A COMPARAR ENTRE SUS MEDICIONES ──
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Métrica de Progreso:",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "VBT" to "⚡ Velocidad Pico (m/s)",
                                "VMP" to "🎯 Vel. Propulsiva (m/s)",
                                "POWER" to "🔥 Potencia (Watts)",
                                "REL_POWER" to "⚖️ Relativa (W/kg)",
                                "LOAD" to "🏋️ Carga (kg)"
                            ).forEach { (key, label) ->
                                val isSel = selectedMetricType == key
                                Surface(
                                    onClick = { selectedMetricType = key },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) Color(0xFF00FFCC) else Color(0xFF1B222C),
                                    border = BorderStroke(1.2.dp, if (isSel) Color(0xFF00FFCC) else Color(0xFF2C394A)),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    ) {
                                        Text(
                                            label,
                                            color = if (isSel) Color.Black else Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 4. GRÁFICO DE EVOLUCIÓN HISTÓRICA DEL MISMO ATLETA (ÚLTIMAS 10 MEDICIONES) ──
                    val recentChronological = filteredSessionsChronological.takeLast(10)
                    val dateFormatShort = remember { SimpleDateFormat("dd/MM", Locale.getDefault()) }

                    val sessionPoints = recentChronological.map { session ->
                        val v = when (selectedMetricType) {
                            "VBT" -> session.result
                            "VMP" -> session.vmpVelocity ?: session.meanVelocity ?: session.result
                            "POWER" -> session.powerWatts ?: 0.0
                            "LOAD" -> session.loadKg ?: 0.0
                            else -> session.powerWattsPerKg ?: 0.0
                        }
                        val label = dateFormatShort.format(Date(session.timestamp))
                        Triple(label, v, session)
                    }

                    val maxVal = sessionPoints.maxOfOrNull { it.second }?.takeIf { it > 0.0 } ?: 1.0
                    val firstVal = sessionPoints.firstOrNull()?.second ?: 0.0
                    val lastVal = sessionPoints.lastOrNull()?.second ?: 0.0
                    val deltaPercent = if (firstVal > 0.0 && sessionPoints.size >= 2) {
                        ((lastVal - firstVal) / firstVal) * 100.0
                    } else null

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141B)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF263342)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(255.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (selectedMetricType) {
                                        "VBT" -> "📈 Evolución de Velocidad Pico (m/s)"
                                        "VMP" -> "📈 Evolución de Vel. Propulsiva (m/s)"
                                        "POWER" -> "📈 Evolución de Potencia Pico (W)"
                                        "LOAD" -> "📈 Evolución de Carga Levantada (kg)"
                                        else -> "📈 Evolución de Potencia Relativa (W/kg)"
                                    },
                                    color = Color(0xFF00FFCC),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (deltaPercent != null) {
                                    val isPositive = deltaPercent >= 0.0
                                    val badgeColor = if (isPositive) Color(0xFF00E676) else Color(0xFFFF9800)
                                    Surface(
                                        color = badgeColor.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, badgeColor)
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "%s%.1f%%", if (isPositive) "+" else "", deltaPercent),
                                            color = badgeColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            if (sessionPoints.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "Sin mediciones registradas para este filtro.",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(185.dp)
                                ) {
                                    val w = size.width
                                    val h = size.height
                                    val count = sessionPoints.size
                                    val spacing = w / (count + 1)
                                    val barWidth = 34.dp.toPx().coerceAtMost(spacing * 0.65f)
                                    val baselineY = h - 28.dp.toPx()

                                    for (gridStep in 1..3) {
                                        val gridY = baselineY - (baselineY - 28.dp.toPx()) * (gridStep / 4f)
                                        drawLine(
                                            color = Color(0xFF1E2836),
                                            start = Offset(10f, gridY),
                                            end = Offset(w - 10f, gridY),
                                            strokeWidth = 1.dp.toPx()
                                        )
                                    }

                                    drawLine(
                                        color = Color(0xFF384656),
                                        start = Offset(8f, baselineY),
                                        end = Offset(w - 8f, baselineY),
                                        strokeWidth = 2.dp.toPx()
                                    )

                                    val linePath = Path()
                                    sessionPoints.forEachIndexed { i, (dateLabel, valDouble, _) ->
                                        val cx = spacing * (i + 1)
                                        val fraction = if (maxVal > 0) (valDouble / maxVal).coerceIn(0.05, 1.0).toFloat() else 0.05f
                                        val availH = baselineY - 30.dp.toPx()
                                        val barH = availH * fraction
                                        val topY = baselineY - barH

                                        val isLatest = i == sessionPoints.lastIndex
                                        val barColor = if (isLatest) Color(0xFF00FFCC) else Color(0xFF00E5FF).copy(alpha = 0.55f)

                                        drawRoundRect(
                                            color = barColor,
                                            topLeft = Offset(cx - barWidth / 2f, topY),
                                            size = Size(barWidth, barH),
                                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                                        )

                                        if (i == 0) linePath.moveTo(cx, topY) else linePath.lineTo(cx, topY)

                                        val textVal = when (selectedMetricType) {
                                            "VBT", "VMP" -> if (valDouble > 0) String.format(Locale.US, "%.2f", valDouble) else "--"
                                            "POWER" -> if (valDouble > 0) "${valDouble.toInt()}" else "--"
                                            "LOAD" -> if (valDouble > 0) "${valDouble.toInt()}kg" else "--"
                                            else -> if (valDouble > 0) String.format(Locale.US, "%.1f", valDouble) else "--"
                                        }

                                        val valuePaint = Paint().apply {
                                            color = android.graphics.Color.WHITE
                                            textSize = 10.5.sp.toPx()
                                            isAntiAlias = true
                                            typeface = Typeface.DEFAULT_BOLD
                                            textAlign = Paint.Align.CENTER
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(
                                            textVal,
                                            cx,
                                            (topY - 6.dp.toPx()).coerceAtLeast(12.dp.toPx()),
                                            valuePaint
                                        )

                                        val datePaint = Paint().apply {
                                            color = android.graphics.Color.parseColor("#CBD5E1")
                                            textSize = 10.sp.toPx()
                                            isAntiAlias = true
                                            textAlign = Paint.Align.CENTER
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(
                                            dateLabel,
                                            cx,
                                            h - 6.dp.toPx(),
                                            datePaint
                                        )
                                    }

                                    if (sessionPoints.size >= 2) {
                                        drawPath(
                                            path = linePath,
                                            color = Color(0xFF00FFCC),
                                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 5. TABLA / TARJETAS DE MEDICIONES COMPARADAS DEL ATLETA ──
                    Text(
                        "Detalle Cronológico de Mediciones (${userSessionsDesc.size} totales):",
                        color = Color.LightGray,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val listToDisplay = filteredSessionsChronological.reversed()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listToDisplay.forEachIndexed { idx, session ->
                            val prevChronologicalSession = listToDisplay.getOrNull(idx + 1)
                            val deltaVel = if (prevChronologicalSession != null) {
                                session.result - prevChronologicalSession.result
                            } else null

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161D26)),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (idx == 0) Color(0xFF00FFCC).copy(alpha = 0.65f) else Color(0xFF263342)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = session.activityType,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        val details = buildList {
                                            add(formatHistoryDate(session.timestamp))
                                            if (session.loadKg != null) add("${session.loadKg.toInt()} kg")
                                            if (session.vmpVelocity != null) add("VMP: ${String.format(Locale.US, "%.2f", session.vmpVelocity)} m/s")
                                            if (session.powerWatts != null) add("${session.powerWatts.toInt()} W")
                                        }.joinToString(" • ")
                                        Text(
                                            text = details,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${String.format(Locale.US, "%.2f", session.result)} m/s",
                                            color = Color(0xFF00FFCC),
                                            fontWeight = FontWeight.Black,
                                            fontSize = 15.sp
                                        )
                                        if (deltaVel != null) {
                                            val isUp = deltaVel >= 0.0
                                            Text(
                                                text = String.format(
                                                    Locale.US,
                                                    "%s%.2f m/s vs ant.",
                                                    if (isUp) "+" else "",
                                                    deltaVel
                                                ),
                                                color = if (isUp) Color(0xFF00E676) else Color(0xFFFF9800),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── BOTONES INFERIORES: GENERAR INFORME PDF DEL ATLETA + CERRAR ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.9f)
                            .height(46.dp)
                    ) {
                        Text("Cerrar", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            onGeneratePdfReport(selectedUser, filteredSessionsChronological.reversed())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generar Informe PDF", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Genera un Informe PDF completo con la ficha, mejores marcas personales (PB),
 * gráfico de evolución histórica y tabla de todas las mediciones registradas del atleta.
 */
fun generateAthleteSummaryPdfReport(
    context: Context,
    user: User,
    userSessions: List<Session>
): File? {
    return try {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = android.graphics.Color.parseColor("#0B0F17") }
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#151C28")
            style = Paint.Style.FILL
        }
        val borderCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00FFCC")
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }
        val borderSubtlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#263346")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00FFCC")
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val whiteBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val grayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 10.5f
        }
        val cyanValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00FFCC")
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // 1. Encabezado del Informe del Atleta
        val headerRect = RectF(28f, 24f, pageWidth - 28f, 96f)
        canvas.drawRoundRect(headerRect, 12f, 12f, cardPaint)
        canvas.drawRoundRect(headerRect, 12f, 12f, borderCyanPaint)

        canvas.drawText("SPEED TRACKER VBT • INFORME DE EVOLUCIÓN DEL ATLETA", 44f, 48f, titlePaint)
        val nowStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText(
            "Deportista: ${user.name}   |   Peso Corporal: ${String.format(Locale.US, "%.1f", user.bodyWeightKg)} kg   |   Total Mediciones: ${userSessions.size}",
            44f,
            68f,
            whiteBoldPaint
        )
        canvas.drawText("Fecha de emisión del informe: $nowStr", 44f, 84f, grayTextPaint)

        // 2. Mejores Marcas Personales (PB)
        val sortedDesc = userSessions.sortedByDescending { it.timestamp }
        val maxPeakVel = sortedDesc.maxByOrNull { it.result }
        val maxVmp = sortedDesc.mapNotNull { it.vmpVelocity ?: it.meanVelocity }.maxOrNull()
        val maxPower = sortedDesc.mapNotNull { it.powerWatts }.maxOrNull()
        val maxLoad = sortedDesc.mapNotNull { it.loadKg }.maxOrNull()

        val pbTop = 112f
        val pbBottom = 178f
        val colW = (pageWidth - 56f - 24f) / 4f
        val pbItems = listOf(
            Triple("MEJOR VEL. PICO (PV)", if (maxPeakVel != null) "${String.format(Locale.US, "%.2f", maxPeakVel.result)} m/s" else "-- m/s", maxPeakVel?.activityType ?: "Sin registro"),
            Triple("MEJOR VMP PROPULSIVA", if (maxVmp != null) "${String.format(Locale.US, "%.2f", maxVmp)} m/s" else "-- m/s", "Fase propulsiva"),
            Triple("POTENCIA PICO MÁX.", if (maxPower != null) "${maxPower.toInt()} W" else "-- W", if (maxPower != null && user.bodyWeightKg > 0) "${String.format(Locale.US, "%.1f", maxPower / user.bodyWeightKg)} W/kg" else "Watts"),
            Triple("CARGA MÁXIMA", if (maxLoad != null) "${maxLoad.toInt()} kg" else "-- kg", "Mayor peso registrado")
        )

        pbItems.forEachIndexed { idx, (label, value, sub) ->
            val left = 28f + idx * (colW + 8f)
            val rect = RectF(left, pbTop, left + colW, pbBottom)
            canvas.drawRoundRect(rect, 10f, 10f, cardPaint)
            canvas.drawRoundRect(rect, 10f, 10f, borderSubtlePaint)
            canvas.drawText(label, left + 10f, pbTop + 20f, grayTextPaint.apply { textSize = 8.5f })
            canvas.drawText(value, left + 10f, pbTop + 42f, cyanValuePaint)
            val shortSub = if (sub.length > 22) sub.take(20) + "…" else sub
            canvas.drawText(shortSub, left + 10f, pbTop + 58f, grayTextPaint.apply { textSize = 8.5f })
        }

        // 3. Gráfico de Evolución de Velocidad Pico (últimas 12 sesiones en orden cronológico)
        val chartRect = RectF(28f, 194f, pageWidth - 28f, 370f)
        canvas.drawRoundRect(chartRect, 12f, 12f, cardPaint)
        canvas.drawRoundRect(chartRect, 12f, 12f, borderSubtlePaint)

        canvas.drawText("EVOLUCIÓN CRONOLÓGICA DE VELOCIDAD PICO (ÚLTIMAS MEDICIONES)", 44f, 216f, whiteBoldPaint.apply { textSize = 11.5f })

        val chronoSessions = userSessions.sortedBy { it.timestamp }.takeLast(12)
        if (chronoSessions.isEmpty()) {
            canvas.drawText("No hay mediciones registradas para graficar.", 44f, 285f, grayTextPaint.apply { textSize = 11f })
        } else {
            val cLeft = 48f
            val cRight = pageWidth - 48f
            val cTop = 238f
            val cBase = 344f
            val maxV = (chronoSessions.maxOfOrNull { it.result } ?: 1.0).coerceAtLeast(0.5)
            val count = chronoSessions.size
            val stepX = (cRight - cLeft) / (count + 1)
            val barW = (stepX * 0.55f).coerceAtMost(28f)
            val dfShort = SimpleDateFormat("dd/MM", Locale.getDefault())

            val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#00E5FF")
                style = Paint.Style.FILL
            }
            val barLastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#00FFCC")
                style = Paint.Style.FILL
            }
            val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 8.5f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            canvas.drawLine(cLeft, cBase, cRight, cBase, borderSubtlePaint)

            chronoSessions.forEachIndexed { i, s ->
                val cx = cLeft + stepX * (i + 1)
                val frac = (s.result / maxV).coerceIn(0.06, 1.0).toFloat()
                val bH = (cBase - cTop) * frac
                val topY = cBase - bH
                val bRect = RectF(cx - barW / 2f, topY, cx + barW / 2f, cBase)
                canvas.drawRoundRect(
                    bRect,
                    4f,
                    4f,
                    if (i == chronoSessions.lastIndex) barLastPaint else barFillPaint
                )
                canvas.drawText(String.format(Locale.US, "%.2f", s.result), cx, topY - 5f, centerTextPaint)
                canvas.drawText(dfShort.format(Date(s.timestamp)), cx, cBase + 14f, centerTextPaint.apply {
                    color = android.graphics.Color.parseColor("#94A3B8")
                })
                centerTextPaint.color = android.graphics.Color.WHITE
            }
        }

        // 4. Tabla Histórica de Todas las Mediciones Ingresadas del Atleta
        val tableRect = RectF(28f, 386f, pageWidth - 28f, pageHeight - 36f)
        canvas.drawRoundRect(tableRect, 12f, 12f, cardPaint)
        canvas.drawRoundRect(tableRect, 12f, 12f, borderSubtlePaint)

        canvas.drawText("REGISTRO DETALLADO DE MEDICIONES DEL ATLETA", 44f, 410f, whiteBoldPaint.apply { textSize = 11.5f })

        val colFechaX = 44f
        val colEjercicioX = 135f
        val colCargaX = 335f
        val colPvX = 395f
        val colVmpX = 455f
        val colPotX = 515f

        val headerRowY = 432f
        val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00FFCC")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("FECHA / HORA", colFechaX, headerRowY, tableHeaderPaint)
        canvas.drawText("EJERCICIO / COMPLEX", colEjercicioX, headerRowY, tableHeaderPaint)
        canvas.drawText("CARGA", colCargaX, headerRowY, tableHeaderPaint)
        canvas.drawText("VEL. PICO", colPvX, headerRowY, tableHeaderPaint)
        canvas.drawText("VMP", colVmpX, headerRowY, tableHeaderPaint)
        canvas.drawText("POTENCIA", colPotX, headerRowY, tableHeaderPaint)

        canvas.drawLine(40f, headerRowY + 6f, pageWidth - 40f, headerRowY + 6f, borderSubtlePaint)

        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E2E8F0")
            textSize = 9.2f
        }
        val rowCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00FFCC")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val dfFull = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
        val maxRows = 16
        sortedDesc.take(maxRows).forEachIndexed { idx, s ->
            val y = headerRowY + 24f + idx * 21f
            val dateStr = dfFull.format(Date(s.timestamp))
            val exStr = if (s.activityType.length > 32) s.activityType.take(30) + "…" else s.activityType
            val loadStr = s.loadKg?.let { "${it.toInt()} kg" } ?: "--"
            val pvStr = "${String.format(Locale.US, "%.2f", s.result)} m/s"
            val vmpStr = (s.vmpVelocity ?: s.meanVelocity)?.let { "${String.format(Locale.US, "%.2f", it)} m/s" } ?: "--"
            val potStr = s.powerWatts?.let { "${it.toInt()} W" } ?: "--"

            canvas.drawText(dateStr, colFechaX, y, rowPaint)
            canvas.drawText(exStr, colEjercicioX, y, rowPaint)
            canvas.drawText(loadStr, colCargaX, y, rowPaint)
            canvas.drawText(pvStr, colPvX, y, rowCyanPaint)
            canvas.drawText(vmpStr, colVmpX, y, rowPaint)
            canvas.drawText(potStr, colPotX, y, rowPaint)
        }

        pdfDocument.finishPage(page)

        val reportsDir = File(context.filesDir, "saved_reports").apply { mkdirs() }
        val safeName = user.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(reportsDir, "Informe_Atleta_${safeName}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outFile).use { fos ->
            pdfDocument.writeTo(fos)
        }
        pdfDocument.close()
        outFile
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
