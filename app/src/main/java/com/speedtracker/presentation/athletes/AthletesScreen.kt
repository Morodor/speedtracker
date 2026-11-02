package com.speedtracker.presentation.athletes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedtracker.core.report.BiomechanicalPdfReportGenerator
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
    val activeUser = userRepository.getActiveUser()

    var showCreateDialog by remember { mutableStateOf(false) }
    var userToEdit by remember { mutableStateOf<User?>(null) }
    var userToDelete by remember { mutableStateOf<User?>(null) }
    var expandedUserId by remember { mutableStateOf<String?>(activeUser?.id) }
    var videoSessionToPlay by remember { mutableStateOf<Session?>(null) }
    var pdfSessionToView by remember { mutableStateOf<Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestión de Atletas", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Toca un perfil para seleccionarlo como activo y consultar sus informes PDF y videos guardados.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
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

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                userRepository.setActiveUserId(user.id)
                                users = userRepository.getUsers()
                                expandedUserId = if (expandedUserId == user.id) null else user.id
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) Color(0xFF1B3830) else Color(0xFF262626)
                        ),
                        border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))) else null,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
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
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                user.name,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (isCurrent) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    color = Color(0x3300FFCC),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "ACTIVO",
                                                        color = Color(0xFF00FFCC),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Black,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            "Peso corporal: ${user.bodyWeightKg} Kg  •  ${userSessions.size} sesiones",
                                            color = Color.LightGray,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Row {
                                    IconButton(onClick = { userToEdit = user }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Color.LightGray)
                                    }
                                    IconButton(onClick = { userToDelete = user }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", tint = Color(0xFFFF5252))
                                    }
                                }
                            }

                            if (userSessions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = { expandedUserId = if (isExpanded) null else user.id },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (isExpanded) "▴ Ocultar Informes PDF y Videos (${userSessions.size})"
                                        else "▾ Ver Informes PDF y Videos Guardados (${userSessions.size})",
                                        color = Color(0xFF00FFCC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        userSessions.forEach { session ->
                                            val hasVideo = !session.videoExportPath.isNullOrEmpty() && File(session.videoExportPath).exists()
                                            val hasPdf = !session.pdfReportPath.isNullOrEmpty() && File(session.pdfReportPath).exists()
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
                                                                session.activityType,
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 12.5.sp
                                                            )
                                                            Text(
                                                                formatHistoryDate(session.timestamp),
                                                                color = Color.Gray,
                                                                fontSize = 10.5.sp
                                                            )
                                                        }
                                                        val valStr = if (session.activityType == "JUMP") {
                                                            "${String.format(Locale.US, "%.1f", session.result * 100)} cm"
                                                        } else {
                                                            "${String.format(Locale.US, "%.2f", session.result)} m/s"
                                                        }
                                                        Text(
                                                            valStr,
                                                            color = Color(0xFF00FFCC),
                                                            fontWeight = FontWeight.Black,
                                                            fontSize = 14.sp
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
                                userRepository.saveUser(name.trim(), w)
                                users = userRepository.getUsers()
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
                                users = userRepository.getUsers()
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
                            users = userRepository.getUsers()
                            allSessions = userRepository.getSessions().sortedByDescending { it.timestamp }
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
