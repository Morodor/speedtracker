package com.speedtracker.presentation.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

/**
 * Lienzo interactivo que dibuja la retícula circular Kinovea directamente sobre el video,
 * permitiendo arrastrar el centro con el dedo y redimensionar el radio con el tirador amarillo
 * sin tapar la visión del atleta.
 */
@Composable
fun KinoveaCircleReticleCanvas(
    center: Offset,
    radius: Float,
    onCenterChange: (Offset) -> Unit,
    onRadiusChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentCenter by rememberUpdatedState(center)
    val currentRadius by rememberUpdatedState(radius)
    val onCenterChangeState by rememberUpdatedState(onCenterChange)
    val onRadiusChangeState by rememberUpdatedState(onRadiusChange)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    val c = currentCenter
                    val r = currentRadius
                    val handlePos = Offset(c.x + r, c.y)
                    val isHandle = hypot(
                        (startPos.x - handlePos.x).toDouble(),
                        (startPos.y - handlePos.y).toDouble()
                    ) < 45.dp.toPx()

                    var hasMoved = false
                    var isDragging = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (!hasMoved && !isHandle) {
                                onCenterChangeState(startPos)
                            }
                            break
                        }

                        val currentPos = change.position
                        val delta = currentPos - startPos
                        val dist = hypot(delta.x.toDouble(), delta.y.toDouble())

                        if (!isDragging && dist > 6.0) {
                            isDragging = true
                            hasMoved = true
                        }

                        if (isDragging) {
                            val dragDelta = change.positionChange()
                            change.consume()
                            if (isHandle) {
                                val newR = (currentRadius + dragDelta.x).coerceIn(15f, 650f)
                                onRadiusChangeState(newR)
                            } else {
                                onCenterChangeState(currentCenter + dragDelta)
                            }
                        }
                    }
                }
            }
    ) {
        val strokeW = 3.dp.toPx()
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f), 0f)

        // 1. Relleno suave translúcido del disco
        drawCircle(
            color = Color(0x3300FFCC),
            radius = radius,
            center = center
        )

        // 2. Anillo perimétrico sólido del disco
        drawCircle(
            color = Color(0xFF00FFCC),
            radius = radius,
            center = center,
            style = Stroke(width = strokeW)
        )

        // 3. Anillo exterior óptico punteado (Guía Kinovea)
        drawCircle(
            color = Color(0x6600FFCC),
            radius = radius + 6.dp.toPx(),
            center = center,
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect)
        )

        // 4. Retícula y Mira Central (+)
        val crosshairSize = 22.dp.toPx()
        drawLine(
            color = Color(0xFF00FFCC),
            start = Offset(center.x - crosshairSize, center.y),
            end = Offset(center.x + crosshairSize, center.y),
            strokeWidth = 2.dp.toPx()
        )
        drawLine(
            color = Color(0xFF00FFCC),
            start = Offset(center.x, center.y - crosshairSize),
            end = Offset(center.x, center.y + crosshairSize),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = Color(0xFF00FFCC),
            radius = 4.dp.toPx(),
            center = center
        )

        // 5. Marcas Cardinales (Norte, Sur, Oeste)
        val tickSize = 10.dp.toPx()
        drawLine(Color(0xFFFFD600), Offset(center.x, center.y - radius - tickSize), Offset(center.x, center.y - radius + tickSize), 2.5.dp.toPx())
        drawLine(Color(0xFFFFD600), Offset(center.x, center.y + radius - tickSize), Offset(center.x, center.y + radius + tickSize), 2.5.dp.toPx())
        drawLine(Color(0xFFFFD600), Offset(center.x - radius - tickSize, center.y), Offset(center.x - radius + tickSize, center.y), 2.5.dp.toPx())

        // 6. Tirador de Radio en el borde Este (Arrastrable directamente con el dedo)
        val handleCenter = Offset(center.x + radius, center.y)
        drawCircle(
            color = Color(0xFFFFD600),
            radius = 14.dp.toPx(),
            center = handleCenter
        )
        drawCircle(
            color = Color.Black,
            radius = 4.dp.toPx(),
            center = handleCenter
        )
    }
}

/**
 * Panel de Calibración de Disco Olímpico (Estándar internacional 45 cm / 450 mm).
 * Simplificado para una experiencia ágil y directa sin modos redundantes.
 */
@Composable
fun KinoveaCalibrationBottomControls(
    currentVideoPosMs: Long,
    videoDurationMs: Long,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
    onStepFrame: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    radius: Float,
    center: Offset,
    onRadiusChange: (Float) -> Unit,
    onCenterChange: (Offset) -> Unit,
    onCenterReset: () -> Unit,
    onAutoDetect: () -> Unit,
    isDetecting: Boolean,
    onConfirm: (Float, Double) -> Unit,
    onCancel: () -> Unit
) {
    val diameterPx = radius * 2f
    val mmPerPx = if (diameterPx > 0) (450.0 / diameterPx) else 0.0

    val currentSec = currentVideoPosMs / 1000.0
    val totalSec = videoDurationMs / 1000.0
    val currentFrame = (currentVideoPosMs / 33L) + 1L
    val totalFrames = (videoDurationMs / 33L).coerceAtLeast(1L)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0x4400FFCC))
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Encabezado claro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🎯 Calibración del Disco (45 cm)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
                Text(
                    "Frame $currentFrame / $totalFrames",
                    color = Color(0xFF00FFCC),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }

            Text(
                "Coloca el círculo sobre el disco para fijar la escala métrica y el tracker visual.",
                fontSize = 11.sp,
                color = Color.LightGray,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Barra deslizadora de video para elegir el cuadro exacto
            if (videoDurationMs > 0) {
                Slider(
                    value = currentVideoPosMs.toFloat().coerceIn(0f, videoDurationMs.toFloat()),
                    onValueChange = { onSeekTo(it.toLong()) },
                    valueRange = 0f..videoDurationMs.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00FFCC),
                        activeTrackColor = Color(0xFF00FFCC),
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )
            }

            // Controles de paso cuadro a cuadro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { onStepFrame(-165L) },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "-5 Frames", modifier = Modifier.size(15.dp))
                }

                FilledTonalIconButton(
                    onClick = { onStepFrame(-33L) },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Text("-1f", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF00FFCC),
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pausa",
                        modifier = Modifier.size(18.dp)
                    )
                }

                FilledTonalIconButton(
                    onClick = { onStepFrame(33L) },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Text("+1f", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalIconButton(
                    onClick = { onStepFrame(165L) },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "+5 Frames", modifier = Modifier.size(15.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Botón de Auto-Detección
            FilledTonalButton(
                onClick = onAutoDetect,
                enabled = !isDetecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0x3300FFCC),
                    contentColor = Color(0xFF00FFCC)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isDetecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF00FFCC)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Detectando disco en el video...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("⚡ Auto-Detectar Disco en este Frame", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Información de Medida
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Disco Olímpico Estándar:",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                    Text(
                        "Ø ${diameterPx.toInt()} px • ${String.format("%.2f", mmPerPx)} mm/px (45 cm)",
                        color = Color(0xFF00FFCC),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Controles de Tamaño (Slider + botones +/-)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { onRadiusChange((radius - 5f).coerceAtLeast(15f)) },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Text("-5", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalIconButton(
                    onClick = { onRadiusChange((radius - 1f).coerceAtLeast(15f)) },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "-1", modifier = Modifier.size(12.dp))
                }

                Slider(
                    value = radius.coerceIn(15f, 450f),
                    onValueChange = { onRadiusChange(it) },
                    valueRange = 15f..450f,
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00FFCC),
                        activeTrackColor = Color(0xFF00FFCC),
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                FilledTonalIconButton(
                    onClick = { onRadiusChange((radius + 1f).coerceAtMost(650f)) },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "+1", modifier = Modifier.size(12.dp))
                }

                FilledTonalIconButton(
                    onClick = { onRadiusChange((radius + 5f).coerceAtMost(650f)) },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF262626),
                        contentColor = Color.White
                    )
                ) {
                    Text("+5", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Botones de Acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(0.7f)
                        .height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Cancelar", fontSize = 11.sp, color = Color.LightGray)
                }

                Button(
                    onClick = {
                        if (diameterPx > 15f) {
                            onConfirm(diameterPx, mmPerPx)
                        }
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(38.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FFCC),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Confirmar Disco (45 cm)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Overlay sutil que indica la calibración activa del disco olímpico
 * cuando se sale del modo calibrador.
 */
@Composable
fun CalibratedPlateMarkerOverlay(
    center: Offset,
    radius: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = Color(0x2200FFCC),
            radius = radius,
            center = center
        )
        drawCircle(
            color = Color(0x9900FFCC),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
        )
        val sizeCross = 12.dp.toPx()
        drawLine(Color(0x9900FFCC), Offset(center.x - sizeCross, center.y), Offset(center.x + sizeCross, center.y), 1.5.dp.toPx())
        drawLine(Color(0x9900FFCC), Offset(center.x, center.y - sizeCross), Offset(center.x, center.y + sizeCross), 1.5.dp.toPx())
        drawCircle(Color(0xFF00FFCC), radius = 3.dp.toPx(), center = center)
    }
}

/**
 * Controles direccionales superpuestos directamente sobre el video (HUD Overlay)
 * para ajustar milimétricamente la posición del disco con precisión táctil.
 */
@Composable
fun BoxScope.CalibrationVideoDirectionalOverlay(
    onMove: (dx: Float, dy: Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Flecha Arriba (Alineada al margen superior de la pantalla - Tamaño Doble 80dp para pulgar)
    Surface(
        onClick = { onMove(0f, -6f) },
        shape = CircleShape,
        color = Color(0xEE141414),
        border = BorderStroke(2.5.dp, Color(0xFF00FFCC)),
        shadowElevation = 12.dp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
            .size(80.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Mover Arriba",
                tint = Color(0xFF00FFCC),
                modifier = Modifier.size(48.dp)
            )
        }
    }

    // Flecha Abajo (Alineada al margen inferior de la pantalla - Tamaño Doble 80dp para pulgar)
    Surface(
        onClick = { onMove(0f, 6f) },
        shape = CircleShape,
        color = Color(0xEE141414),
        border = BorderStroke(2.5.dp, Color(0xFF00FFCC)),
        shadowElevation = 12.dp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 8.dp)
            .size(80.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Mover Abajo",
                tint = Color(0xFF00FFCC),
                modifier = Modifier.size(48.dp)
            )
        }
    }

    // Flecha Izquierda (Alineada al margen izquierdo de la pantalla - Tamaño Doble 80dp para pulgar)
    Surface(
        onClick = { onMove(-6f, 0f) },
        shape = CircleShape,
        color = Color(0xEE141414),
        border = BorderStroke(2.5.dp, Color(0xFF00FFCC)),
        shadowElevation = 12.dp,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 6.dp)
            .size(80.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "Mover Izquierda",
                tint = Color(0xFF00FFCC),
                modifier = Modifier.size(48.dp)
            )
        }
    }

    // Flecha Derecha (Alineada al margen derecho de la pantalla - Tamaño Doble 80dp para pulgar)
    Surface(
        onClick = { onMove(6f, 0f) },
        shape = CircleShape,
        color = Color(0xEE141414),
        border = BorderStroke(2.5.dp, Color(0xFF00FFCC)),
        shadowElevation = 12.dp,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 6.dp)
            .size(80.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "Mover Derecha",
                tint = Color(0xFF00FFCC),
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
