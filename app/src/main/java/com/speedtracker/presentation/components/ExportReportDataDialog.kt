package com.speedtracker.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.speedtracker.domain.model.User
import java.util.Locale

@Composable
fun ExportReportDataDialog(
    initialAthleteName: String = "",
    initialLoadKg: Double = 70.0,
    initialExerciseName: String = "Clean Suelo",
    availableAthletes: List<User> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmExport: (athleteName: String, loadKg: Double, exerciseName: String) -> Unit
) {
    var athleteName by remember {
        mutableStateOf(
            initialAthleteName.ifBlank { availableAthletes.firstOrNull()?.name ?: "Atleta" }
        )
    }
    var loadKgText by remember { mutableStateOf(initialLoadKg.toInt().toString()) }
    var exerciseName by remember { mutableStateOf(initialExerciseName) }

    val commonExercises = remember {
        com.speedtracker.core.math.VBTExerciseModel.values().map { it.displayName }
    }

    val quickLoads = listOf(40, 50, 60, 70, 80, 90, 100, 110, 120)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1F26),
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Encabezado
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
                            shape = CircleShape,
                            color = Color(0xFF00FFCC).copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.FitnessCenter,
                                    contentDescription = null,
                                    tint = Color(0xFF00FFCC),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                "Datos para el Informe",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Incrustados en el video y reporte",
                                color = Color(0xFF9EABB8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.Gray)
                    }
                }

                Divider(color = Color(0xFF2D3748), thickness = 1.dp)

                // 1. Campo: Nombre del Atleta
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "👤 ATLETA",
                        color = Color(0xFF00FFCC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    OutlinedTextField(
                        value = athleteName,
                        onValueChange = { athleteName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Nombre del atleta...", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FFCC),
                            unfocusedBorderColor = Color(0xFF3B4858),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF00FFCC)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Atletas rápidos guardados
                    if (availableAthletes.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableAthletes.take(8).forEach { user ->
                                val isSelected = athleteName.equals(user.name, ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF26303C),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF00FFCC) else Color(0xFF3B4858)
                                    ),
                                    modifier = Modifier.clickable {
                                        athleteName = user.name
                                    }
                                ) {
                                    Text(
                                        text = user.name,
                                        color = if (isSelected) Color.Black else Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Campo: Peso Levantado (Carga en Barra)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "⚖️ PESO LEVANTADO (CARGA EN BARRA)",
                        color = Color(0xFF00FFCC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = loadKgText,
                            onValueChange = { input ->
                                if (input.isEmpty() || input.all { it.isDigit() || it == '.' }) {
                                    loadKgText = input
                                }
                            },
                            modifier = Modifier.weight(1f),
                            suffix = { Text("kg", color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FFCC),
                                unfocusedBorderColor = Color(0xFF3B4858),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF00FFCC)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // Botones de ajuste fino (-5 / +5)
                        FilledTonalIconButton(
                            onClick = {
                                val current = loadKgText.toDoubleOrNull() ?: 50.0
                                val updated = (current - 5.0).coerceAtLeast(10.0)
                                loadKgText = if (updated % 1.0 == 0.0) updated.toInt().toString() else String.format(Locale.US, "%.1f", updated)
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF26303C)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Text("-5", color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        FilledTonalIconButton(
                            onClick = {
                                val current = loadKgText.toDoubleOrNull() ?: 50.0
                                val updated = current + 5.0
                                loadKgText = if (updated % 1.0 == 0.0) updated.toInt().toString() else String.format(Locale.US, "%.1f", updated)
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF26303C)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Text("+5", color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // Accesos directos a cargas comunes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickLoads.forEach { qLoad ->
                            val isSelected = loadKgText == qLoad.toString()
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) Color(0xFF00FFCC).copy(alpha = 0.25f) else Color(0xFF202731),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00FFCC) else Color(0xFF343F4D)
                                ),
                                modifier = Modifier.clickable {
                                    loadKgText = qLoad.toString()
                                }
                            ) {
                                Text(
                                    text = "$qLoad kg",
                                    color = if (isSelected) Color(0xFF00FFCC) else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Campo: Ejercicio
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "🏋️ EJERCICIO",
                        color = Color(0xFF00FFCC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        commonExercises.forEach { ex ->
                            val isSelected = exerciseName.equals(ex, ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF26303C),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00FFCC) else Color(0xFF3B4858)
                                ),
                                modifier = Modifier.clickable {
                                    exerciseName = ex
                                }
                            ) {
                                Text(
                                    text = ex,
                                    color = if (isSelected) Color.Black else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Botones de acción
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF3B4858))
                    ) {
                        Text("Cancelar", color = Color.LightGray)
                    }

                    Button(
                        onClick = {
                            val finalAthlete = athleteName.trim().ifBlank { "Atleta" }
                            val finalLoad = loadKgText.toDoubleOrNull() ?: 70.0
                            val finalExercise = exerciseName.trim().ifBlank { "Clean Suelo" }
                            onConfirmExport(finalAthlete, finalLoad, finalExercise)
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Exportar Video", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
