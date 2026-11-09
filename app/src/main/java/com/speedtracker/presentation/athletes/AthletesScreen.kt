package com.speedtracker.presentation.athletes

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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthletesScreen(
    userRepository: UserRepository
) {
    val context = LocalContext.current
    var users by remember { mutableStateOf(userRepository.getUsers()) }
    var allSessions by remember { mutableStateOf(userRepository.getSessions().sortedByDescending { it.timestamp }) }
    var activeUser by remember { mutableStateOf(userRepository.getActiveUser()) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var userToEdit by remember { mutableStateOf<User?>(null) }
    var userToDelete by remember { mutableStateOf<User?>(null) }
    var expandedUserId by remember { mutableStateOf<String?>(activeUser?.id) }
    var videoSessionToPlay by remember { mutableStateOf<Session?>(null) }
    var pdfSessionToView by remember { mutableStateOf<Session?>(null) }

    // Estado para la Comparación Multiatleta (2 a 4 atletas con Gráfico)
    var showComparisonDialog by remember { mutableStateOf(false) }

    fun refreshUsers() {
        users = userRepository.getUsers()
        activeUser = userRepository.getActiveUser()
        allSessions = userRepository.getSessions().sortedByDescending { it.timestamp }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fichas de Atletas", fontWeight = FontWeight.Bold) },
                actions = {
                    if (users.size >= 2) {
                        FilledTonalButton(
                            onClick = { showComparisonDialog = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF1B322C),
                                contentColor = Color(0xFF00FFCC)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.BarChart,
                                contentDescription = "Comparar",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Comparar Atletas", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        text = "Selecciona o marca el atleta activo y consulta marcas personales, informes PDF y videos.",
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
                    val isCurrent = user.id == activeUser?.id
                    val userSessions = allSessions.filter { it.userId == user.id }
                    val isExpanded = expandedUserId == user.id

                    val maxJumpSession = userSessions.filter { it.activityType.startsWith("JUMP") || it.activityType.contains("Salto", ignoreCase = true) }.maxByOrNull { it.result }
                    val maxVbtSession = userSessions.filter { it.activityType.startsWith("VBT") }.maxByOrNull { it.result }
                    val maxPowerSession = userSessions.filter { it.powerWatts != null }.maxByOrNull { it.powerWatts ?: 0.0 }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedUserId = if (expandedUserId == user.id) null else user.id
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) Color(0xFF1B322C) else Color(0xFF262626)
                        ),
                        border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))) else null,
                        shape = RoundedCornerShape(12.dp)
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
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (isCurrent) Color(0xFF00FFCC) else Color.Gray,
                                        modifier = Modifier.size(36.dp)
                                    )
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

                            Spacer(modifier = Modifier.height(10.dp))

                            // ── BOTÓN EXPLICITO: MARCAR ATLETA ACTIVO ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    Surface(
                                        color = Color(0x2E00FFCC),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.2.dp, Color(0xFF00FFCC))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF00FFCC),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Atleta Activo en Mediciones",
                                                color = Color(0xFF00FFCC),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            userRepository.setActiveUserId(user.id)
                                            refreshUsers()
                                            Toast.makeText(context, "'${user.name}' establecido como atleta activo", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.6f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = Color(0xFF00FFCC),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Marcar como Atleta Activo",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = { expandedUserId = if (isExpanded) null else user.id },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (isExpanded) "Ocultar Detalles ▴" else "Ver Detalles ▾",
                                        color = Color.LightGray,
                                        fontSize = 11.5.sp
                                    )
                                }
                            }

                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = Color(0xFF32423D), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(10.dp))

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
                                    // PB Salto Vertical
                                    Surface(
                                        color = Color(0xFF141A1F),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF2B3844)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Salto Vertical", color = Color.LightGray, fontSize = 10.5.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (maxJumpSession != null) "${String.format(Locale.US, "%.1f", maxJumpSession.result * 100)} cm" else "-- cm",
                                                color = Color(0xFF00FFCC),
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Text(
                                                if (maxJumpSession != null) formatHistoryDate(maxJumpSession.timestamp) else "Sin registros",
                                                color = Color.Gray,
                                                fontSize = 9.5.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // PB Pico VBT
                                    Surface(
                                        color = Color(0xFF141A1F),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Pico VBT", color = Color.LightGray, fontSize = 10.5.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (maxVbtSession != null) "${String.format(Locale.US, "%.2f", maxVbtSession.result)} m/s" else "-- m/s",
                                                color = Color(0xFF00E5FF),
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

        // Modal de Comparación de Atletas con Gráfico
        if (showComparisonDialog) {
            AthleteComparisonDialog(
                users = users,
                sessions = allSessions,
                onDismiss = { showComparisonDialog = false }
            )
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
 * Diálogo interactivo para comparar de 2 a 4 atletas con selector de métricas,
 * gráfico biomecánico ampliado y tarjetas individuales de rendimiento sin apreturas.
 */
@Composable
fun AthleteComparisonDialog(
    users: List<User>,
    sessions: List<Session>,
    onDismiss: () -> Unit
) {
    var selectedAthleteIds by remember {
        mutableStateOf(users.take(minOf(4, users.size)).map { it.id }.toSet())
    }
    // Métricas disponibles para comparar
    var selectedMetricType by remember { mutableStateOf("VBT") } // "JUMP", "VBT", "POWER", "REL_POWER"

    val athleteColors = listOf(
        Color(0xFF00FFCC), // Cian Neón
        Color(0xFF00E5FF), // Azul Eléctrico
        Color(0xFFFF9800), // Naranja Neón
        Color(0xFFE040FB)  // Púrpura Neón
    )

    val selectedAthletes = users.filter { selectedAthleteIds.contains(it.id) }

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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── HEADER SUPERIOR ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = Color(0xFF00FFCC).copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("📊", fontSize = 20.sp)
                            }
                        }
                        Column {
                            Text(
                                "Comparativa de Rendimiento",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "${selectedAthleteIds.size} atletas elegidos (elige entre 2 y 4)",
                                color = Color(0xFF00FFCC),
                                fontSize = 12.sp,
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── 1. SELECTOR DE ATLETAS (AMPLIADO Y CÓMODO) ──
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Seleccionar Atletas a Comparar:",
                            color = Color.LightGray,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            users.forEachIndexed { index, user ->
                                val isSelected = selectedAthleteIds.contains(user.id)
                                val color = athleteColors[index % athleteColors.size]

                                Surface(
                                    onClick = {
                                        if (isSelected) {
                                            if (selectedAthleteIds.size > 2) {
                                                selectedAthleteIds = selectedAthleteIds - user.id
                                            }
                                        } else {
                                            if (selectedAthleteIds.size < 4) {
                                                selectedAthleteIds = selectedAthleteIds + user.id
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) color.copy(alpha = 0.20f) else Color(0xFF1B222C),
                                    border = BorderStroke(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) color else Color(0xFF2C394A)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Círculo con Inicial
                                        Surface(
                                            color = if (isSelected) color else Color.Gray,
                                            shape = CircleShape,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    user.name.take(1).uppercase(),
                                                    color = Color.Black,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Column {
                                            Text(
                                                user.name,
                                                color = if (isSelected) Color.White else Color.LightGray,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            Text(
                                                "${user.bodyWeightKg.toInt()} kg",
                                                color = if (isSelected) color else Color.Gray,
                                                fontSize = 10.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 2. SELECTOR DE MÉTRICA CON PESTAÑAS AMPLIAS ──
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Métrica a Comparar:",
                            color = Color.LightGray,
                            fontSize = 12.5.sp,
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
                                "LOAD" to "🏋️ Carga Levantada (kg)"
                            ).forEach { (key, label) ->
                                val isSel = selectedMetricType == key
                                Surface(
                                    onClick = { selectedMetricType = key },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) Color(0xFF00FFCC) else Color(0xFF1B222C),
                                    border = BorderStroke(1.2.dp, if (isSel) Color(0xFF00FFCC) else Color(0xFF2C394A)),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp)
                                    ) {
                                        Text(
                                            label,
                                            color = if (isSel) Color.Black else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 3. GRÁFICO COMPARATIVO DE BARRAS BIOMECÁNICO (GRANDE Y ESPACIOSO) ──
                    val comparisonData = selectedAthletes.mapIndexed { idx, user ->
                        val userSessions = sessions.filter { it.userId == user.id }
                        val value: Double = when (selectedMetricType) {
                            "VBT" -> {
                                val maxVbt = userSessions.filter { it.activityType.startsWith("VBT") }.maxByOrNull { it.result }
                                maxVbt?.result ?: 0.0 // m/s
                            }
                            "VMP" -> {
                                val maxVmp = userSessions.mapNotNull { it.vmpVelocity }.maxOrNull()
                                maxVmp ?: 0.0 // m/s
                            }
                            "POWER" -> {
                                val maxPower = userSessions.mapNotNull { it.powerWatts }.maxOrNull()
                                maxPower ?: 0.0 // Watts
                            }
                            "LOAD" -> {
                                val maxLoad = userSessions.mapNotNull { it.loadKg }.maxOrNull()
                                maxLoad ?: 0.0 // kg
                            }
                            else -> { // REL_POWER
                                val maxRel = userSessions.mapNotNull { it.powerWattsPerKg }.maxOrNull()
                                maxRel ?: 0.0 // W/kg
                            }
                        }
                        Triple(user.name, value, athleteColors[idx % athleteColors.size])
                    }

                    val maxValue = comparisonData.maxOfOrNull { it.second }?.takeIf { it > 0.0 } ?: 1.0

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141B)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF263342)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (selectedMetricType) {
                                        "VBT" -> "📊 Máxima Velocidad Pico VBT (m/s)"
                                        "VMP" -> "📊 Velocidad Media Propulsiva (m/s)"
                                        "POWER" -> "📊 Potencia Pico Máxima (Watts)"
                                        "LOAD" -> "📊 Carga Máxima Desplazada (kg)"
                                        else -> "📊 Potencia Relativa al Peso (Watts / kg)"
                                    },
                                    color = Color(0xFF00FFCC),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                val bestAthlete = comparisonData.maxByOrNull { it.second }
                                if (bestAthlete != null && bestAthlete.second > 0.0) {
                                    Surface(
                                        color = Color(0xFFFFD700).copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFFD700))
                                    ) {
                                        Text(
                                            "★ Líder: ${bestAthlete.first.split(" ").first()}",
                                            color = Color(0xFFFFD700),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            // Canvas de barras verticales grande
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            ) {
                                val w = size.width
                                val h = size.height
                                val barCount = comparisonData.size
                                val spacing = w / (barCount + 1)
                                val barWidth = 44.dp.toPx().coerceAtMost(spacing * 0.70f)
                                val baselineY = h - 28.dp.toPx()

                                // Líneas de referencia horizontales (25%, 50%, 75%, 100%)
                                for (gridStep in 1..3) {
                                    val gridY = baselineY - (baselineY - 32.dp.toPx()) * (gridStep / 4f)
                                    drawLine(
                                        color = Color(0xFF1E2836),
                                        start = Offset(12f, gridY),
                                        end = Offset(w - 12f, gridY),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }

                                // Línea base sólida
                                drawLine(
                                    color = Color(0xFF384656),
                                    start = Offset(8f, baselineY),
                                    end = Offset(w - 8f, baselineY),
                                    strokeWidth = 2.dp.toPx()
                                )

                                comparisonData.forEachIndexed { i, (name, valDouble, color) ->
                                    val barCenterX = spacing * (i + 1)
                                    val barLeft = barCenterX - barWidth / 2f
                                    val fraction = if (maxValue > 0) (valDouble / maxValue).coerceIn(0.04, 1.0).toFloat() else 0.04f
                                    val availableHeight = baselineY - 32.dp.toPx()
                                    val barHeight = availableHeight * fraction
                                    val topY = baselineY - barHeight

                                    // Barra vertical con esquinas superiores redondeadas
                                    drawRoundRect(
                                        color = color,
                                        topLeft = Offset(barLeft, topY),
                                        size = Size(barWidth, barHeight),
                                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                                    )

                                    // Formatear valor numérico prominente
                                    val textVal = when (selectedMetricType) {
                                        "VBT", "VMP" -> if (valDouble > 0) "${String.format(Locale.US, "%.2f", valDouble)} m/s" else "-- m/s"
                                        "POWER" -> if (valDouble > 0) "${valDouble.toInt()} W" else "-- W"
                                        "LOAD" -> if (valDouble > 0) "${valDouble.toInt()} kg" else "-- kg"
                                        else -> if (valDouble > 0) "${String.format(Locale.US, "%.1f", valDouble)} W/kg" else "-- W/kg"
                                    }

                                    val valuePaint = android.graphics.Paint().apply {
                                        this.color = android.graphics.Color.WHITE
                                        this.textSize = 12.5.sp.toPx()
                                        this.isAntiAlias = true
                                        this.typeface = android.graphics.Typeface.DEFAULT_BOLD
                                        this.textAlign = android.graphics.Paint.Align.CENTER
                                    }
                                    drawContext.canvas.nativeCanvas.drawText(
                                        textVal,
                                        barCenterX,
                                        (topY - 6.dp.toPx()).coerceAtLeast(14.dp.toPx()),
                                        valuePaint
                                    )

                                    // Nombre del atleta bajo la barra
                                    val shortName = name.split(" ").firstOrNull() ?: name
                                    val namePaint = android.graphics.Paint().apply {
                                        this.color = android.graphics.Color.parseColor("#CBD5E1")
                                        this.textSize = 12.sp.toPx()
                                        this.isAntiAlias = true
                                        this.typeface = android.graphics.Typeface.DEFAULT_BOLD
                                        this.textAlign = android.graphics.Paint.Align.CENTER
                                    }
                                    drawContext.canvas.nativeCanvas.drawText(
                                        shortName,
                                        barCenterX,
                                        h - 6.dp.toPx(),
                                        namePaint
                                    )
                                }
                            }
                        }
                    }

                    // ── 4. TARJETAS DETALLADAS INDIVIDUALES POR ATLETA (ESPACIOSAS Y LIMPIAS) ──
                    Text(
                        "Desglose Biomecánico Individual:",
                        color = Color.LightGray,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        selectedAthletes.forEachIndexed { idx, user ->
                            val userSessions = sessions.filter { it.userId == user.id }
                            val maxJump = userSessions.filter { it.activityType.startsWith("JUMP") || it.activityType.contains("Salto", ignoreCase = true) }.maxByOrNull { it.result }
                            val maxVbt = userSessions.filter { it.activityType.startsWith("VBT") }.maxByOrNull { it.result }
                            val maxPower = userSessions.mapNotNull { it.powerWatts }.maxOrNull()
                            val maxRelPower = userSessions.mapNotNull { it.powerWattsPerKg }.maxOrNull()
                            val color = athleteColors[idx % athleteColors.size]

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161D26)),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.2.dp, color.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Fila de Encabezado de Atleta
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .background(color, CircleShape)
                                            )
                                            Text(
                                                user.name,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp
                                            )
                                        }
                                        Text(
                                            "Peso: ${user.bodyWeightKg.toInt()} kg • ${userSessions.size} registros",
                                            color = Color.LightGray,
                                            fontSize = 11.5.sp
                                        )
                                    }

                                    Divider(color = Color(0xFF222C3A), thickness = 1.dp)

                                    val maxVmp = userSessions.mapNotNull { it.vmpVelocity }.maxOrNull()
                                    val maxLoad = userSessions.mapNotNull { it.loadKg }.maxOrNull()

                                    // Cuadrícula 4 Métricas Claras VBT
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Métrica 1: Vel. Pico
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("⚡ Vel. Pico", color = Color.Gray, fontSize = 10.5.sp)
                                            Text(
                                                if (maxVbt != null) "${String.format(Locale.US, "%.2f", maxVbt.result)} m/s" else "-- m/s",
                                                color = color,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp
                                            )
                                        }

                                        // Métrica 2: Vel. Propulsiva
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("🎯 VMP", color = Color.Gray, fontSize = 10.5.sp)
                                            Text(
                                                if (maxVmp != null) "${String.format(Locale.US, "%.2f", maxVmp)} m/s" else "-- m/s",
                                                color = color,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp
                                            )
                                        }

                                        // Métrica 3: Potencia W
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("🔥 Potencia", color = Color.Gray, fontSize = 10.5.sp)
                                            Text(
                                                if (maxPower != null) "${maxPower.toInt()} W" else "-- W",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp
                                            )
                                        }

                                        // Métrica 4: Carga Máx (kg)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("🏋️ Carga Máx", color = Color.Gray, fontSize = 10.5.sp)
                                            Text(
                                                if (maxLoad != null) "${maxLoad.toInt()} kg" else "-- kg",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── BOTÓN CERRAR ──
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Cerrar Comparativa", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
