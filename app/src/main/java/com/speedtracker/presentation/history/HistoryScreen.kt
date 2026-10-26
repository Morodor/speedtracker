package com.speedtracker.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.Session
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
    val usersMap = remember(sessions) { userRepository.getUsers().associateBy { it.id } }

    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "JUMP", "VBT"

    val filteredSessions = remember(sessions, selectedFilter) {
        when (selectedFilter) {
            "JUMP" -> sessions.filter { it.activityType == "JUMP" }
            "VBT" -> sessions.filter { it.activityType == "VBT" }
            else -> sessions
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
            // Filtros de categoría
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("Todos (${sessions.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00FFCC),
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == "JUMP",
                        onClick = { selectedFilter = "JUMP" },
                        label = { Text("Saltos") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00FFCC),
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == "VBT",
                        onClick = { selectedFilter = "VBT" },
                        label = { Text("VBT / Barra") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00FFCC),
                            selectedLabelColor = Color.Black
                        )
                    )
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    } else if (session.activityType == "VBT") {
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
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

fun formatHistoryDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
