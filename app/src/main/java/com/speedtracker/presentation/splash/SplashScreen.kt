package com.speedtracker.presentation.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onTimeout: () -> Unit
) {
    // Animación de pulso y escala del logo
    val infiniteTransition = rememberInfiniteTransition(label = "SplashPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    LaunchedEffect(Unit) {
        delay(2200)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0E17), Color(0xFF121824))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Emblema Vectorial Deportivo Animado
            Box(
                modifier = Modifier.size(130.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = (size.width / 2f) * 0.78f

                    // Anillo exterior tenue
                    drawCircle(
                        color = Color(0x3300FFCC),
                        radius = radius * pulseScale,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Arco de velocidad giratorio
                    drawArc(
                        color = Color(0xFF00FFCC),
                        startAngle = rotationAngle,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Barra horizontal
                    drawLine(
                        color = Color(0xFFCFD8DC),
                        start = Offset(center.x - radius * 0.75f, center.y),
                        end = Offset(center.x + radius * 0.75f, center.y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Rayo / Vector central
                    val boltPath = Path().apply {
                        moveTo(center.x + 4.dp.toPx(), center.y - 28.dp.toPx())
                        lineTo(center.x - 14.dp.toPx(), center.y)
                        lineTo(center.x - 2.dp.toPx(), center.y)
                        lineTo(center.x - 8.dp.toPx(), center.y + 28.dp.toPx())
                        lineTo(center.x + 16.dp.toPx(), center.y - 4.dp.toPx())
                        lineTo(center.x + 4.dp.toPx(), center.y - 4.dp.toPx())
                        close()
                    }
                    drawPath(path = boltPath, color = Color(0xFF00FFCC))

                    // Mira central
                    drawCircle(
                        color = Color.White,
                        radius = 3.5.dp.toPx(),
                        center = center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Título Principal
            Text(
                text = "SPEED TRACKER",
                style = MaterialTheme.typography.headlineMedium.copy(
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Black
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtítulo
            Text(
                text = "LABORATORIO DE BIOMECÁNICA & VBT",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color(0xFF00FFCC),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Badge Pro Edition
            Surface(
                color = Color(0x3300FFCC),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6600FFCC))
            ) {
                Text(
                    text = "PRO EDITION",
                    color = Color(0xFF00FFCC),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
