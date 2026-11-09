package com.speedtracker.presentation.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CameraTutorialScreen(
    onContinue: () -> Unit,
    onSkipForever: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Guía de Encuadre y Medición",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FFCC),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Cómo colocar tu celular antes de grabar para máxima precisión",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    FramingInstructionCard(
                        icon = "🏋️",
                        title = "1. Barra en el Suelo (Snatch / Clean / Peso Muerto)",
                        desc = "• Trípode a 2.5 metros a la altura de la rodilla.\n• Vista lateral o en ángulo de 45°.\n• El disco debe descansar en la línea inferior de la tarima.\n• Asegúrate de que el atleta completo entre en el cuadro al ponerse de pie."
                    )
                }
                item {
                    FramingInstructionCard(
                        icon = "🏛️",
                        title = "2. Barra en Rack (Sentadilla / Press Banca)",
                        desc = "• Trípode a 2.5 metros a la altura de la cadera o pecho.\n• Ángulo perpendicular a 90° lateral a la barra.\n• La barra debe coincidir con los soportes del rack.\n• La trayectoria vertical de descenso debe quedar completamente visible."
                    )
                }
                item {
                    FramingInstructionCard(
                        icon = "🧍",
                        title = "3. Parado / Overhead (Press Militar / Jerk)",
                        desc = "• Trípode a 3 metros a la altura de la cintura.\n• Posición erguida con barra en los hombros.\n• IMPORTANTE: Deja un amplio margen superior libre para que la barra no se corte al bloquear arriba de la cabeza."
                    )
                }
                item {
                    FramingInstructionCard(
                        icon = "🎯",
                        title = "4. Calibración del Disco Olímpico (45 cm)",
                        desc = "• Toca la pantalla sobre el disco para centrar la retícula.\n• Ajusta el diámetro hasta que calce con los 45 cm del disco olímpico.\n• Asegura una escala milimétrica exacta (mm/px) para medir la velocidad de la barra."
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Entendido, encender cámara", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            TextButton(
                onClick = onSkipForever
            ) {
                Text("Omitir (No volver a mostrar)", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun FramingInstructionCard(icon: String, title: String, desc: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                desc,
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
