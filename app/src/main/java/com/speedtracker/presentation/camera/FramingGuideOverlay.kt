package com.speedtracker.presentation.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modos de plantilla de encuadre para guiar al usuario antes de grabar
 */
enum class FramingGuideType(val label: String, val icon: String, val shortDesc: String) {
    FLOOR_BAR("Barra Suelo", "🏋️", "Vista Lateral: Disco en Tarima"),
    HANG_BAR("Colgante", "⚡", "Vista Lateral: Muslo / Rodilla"),
    RACK_BAR("En Rack", "🏛️", "Vista Lateral: Sentadilla/Banca"),
    OVERHEAD_BAR("Parado", "🧍", "Vista Lateral: Empuje/Jerk"),
    VERTICAL_JUMP("Salto (Pies)", "👟", "Plano Frontal: Ambos Pies en Suelo")
}

/**
 * Guía interactiva de encuadre con siluetas biomecánicas, referencias del disco,
 * líneas de suelo y altura de cámara recomendada.
 */
@Composable
fun FramingGuideOverlay(
    selectedType: FramingGuideType,
    isVisible: Boolean,
    onTypeSelected: (FramingGuideType) -> Unit,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    var isAdviceDismissed by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // Lienzo con dibujo vectorial de la silueta y líneas técnicas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Marco exterior de seguridad (Border Safe Area)
            drawRoundRect(
                color = Color(0x3300FFCC),
                topLeft = Offset(w * 0.05f, h * 0.08f),
                size = Size(w * 0.90f, h * 0.84f),
                cornerRadius = CornerRadius(16f, 16f),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                )
            )

            // Eje vertical central de simetría
            drawLine(
                color = Color(0x2200FFCC),
                start = Offset(w / 2f, h * 0.08f),
                end = Offset(w / 2f, h * 0.92f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
            )

            when (selectedType) {
                FramingGuideType.FLOOR_BAR -> drawFloorBarGuide(w, h)
                FramingGuideType.HANG_BAR -> drawHangBarGuide(w, h)
                FramingGuideType.RACK_BAR -> drawRackBarGuide(w, h)
                FramingGuideType.OVERHEAD_BAR -> drawOverheadBarGuide(w, h)
                FramingGuideType.VERTICAL_JUMP -> drawVerticalJumpGuide(w, h)
            }
        }

        // Selector Flotante de Plantillas (Suelo | Colgante | Rack | Parado | Salto)
        // Posicionado limpiamente debajo del header principal con scroll horizontal
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 112.dp)
                .fillMaxWidth(0.96f)
                .background(Color(0xEE121212), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x6600FFCC), RoundedCornerShape(20.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FramingGuideType.values().filter { it != FramingGuideType.VERTICAL_JUMP }.forEach { type ->
                val isSelected = selectedType == type
                Surface(
                    onClick = { onTypeSelected(type) },
                    color = if (isSelected) Color(0xFF00FFCC) else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(type.icon, fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            type.label,
                            color = if (isSelected) Color.Black else Color.LightGray,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Botón rápido de Ocultar Guía
            IconButton(
                onClick = onToggleVisibility,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Ocultar Guía",
                    tint = Color.LightGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Tarjeta de Consejos Prácticos de Encuadre (Inferior, ubicada sobre el panel de telemetría sin taparlo)
        if (!isAdviceDismissed) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.92f)
                    .padding(bottom = 240.dp),
                color = Color(0xEE121212),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0x8000FFCC))
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedType.icon, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Consejo de Encuadre: ${selectedType.label}",
                                color = Color(0xFF00FFCC),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(
                            onClick = { isAdviceDismissed = true },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cerrar Consejo",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        when (selectedType) {
                            FramingGuideType.FLOOR_BAR ->
                                "📱 Vista Lateral 90° obligatoria • Graba el disco olímpico (45 cm) de perfil a la rodilla. El disco de lado es la única referencia para calibrar y medir velocidad."
                            FramingGuideType.HANG_BAR ->
                                "📱 Vista Lateral 90° • Trípode a la altura de la cadera • El disco de 45 cm inicia suspendido en el muslo/rodilla con brazos extendidos. Deja margen superior para la extensión explosiva."
                            FramingGuideType.RACK_BAR ->
                                "📱 Vista Lateral 90° • Enfoca el disco olímpico de perfil apoyado en el rack (sentadilla o banca). Barra y disco vistos de lado."
                            FramingGuideType.OVERHEAD_BAR ->
                                "📱 Vista Lateral 90° • Enfoca de perfil el recorrido del disco desde el pecho hasta el bloqueo sobre la cabeza. Celular a la altura del pecho."
                            FramingGuideType.VERTICAL_JUMP ->
                                "📱 Plano Frontal (Pies a ras de suelo) • Enfoca ambos pies en la plataforma. Solo necesitas ver los pies para calcular con precisión los cuadros de despegue y aterrizaje."
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * 1. Guía para Barra en el Suelo (Snatch, Clean, Peso Muerto) - Vista Lateral 90°
 */
private fun DrawScope.drawFloorBarGuide(w: Float, h: Float) {
    val groundY = h * 0.84f

    // Línea de Tarima / Suelo con textura
    drawLine(
        color = Color(0xFF00FFCC),
        start = Offset(w * 0.08f, groundY),
        end = Offset(w * 0.92f, groundY),
        strokeWidth = 3.dp.toPx()
    )
    for (i in 0..12) {
        val x = w * (0.12f + i * 0.06f)
        drawLine(
            color = Color(0x6600FFCC),
            start = Offset(x, groundY),
            end = Offset(x - 8f, groundY + 12f),
            strokeWidth = 1.5.dp.toPx()
        )
    }

    // Ubicación del Disco Olímpico en el suelo (45 cm de diámetro visto de perfil)
    val discRadius = w * 0.13f
    val discCenterX = w * 0.32f
    val discCenterY = groundY - discRadius

    // Círculo del Disco con mira óptica
    drawCircle(
        color = Color(0x3300FFCC),
        radius = discRadius,
        center = Offset(discCenterX, discCenterY)
    )
    drawCircle(
        color = Color(0xFF00FFCC),
        radius = discRadius,
        center = Offset(discCenterX, discCenterY),
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        )
    )
    // Cruz central del disco (eje de la barra) y buje collarín central
    val cross = 14.dp.toPx()
    drawLine(Color(0xFF00FFCC), Offset(discCenterX - cross, discCenterY), Offset(discCenterX + cross, discCenterY), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(discCenterX, discCenterY - cross), Offset(discCenterX, discCenterY + cross), 2.dp.toPx())
    drawCircle(Color(0xFFFFD600), radius = discRadius * 0.28f, center = Offset(discCenterX, discCenterY), style = Stroke(1.5.dp.toPx()))

    // Corredor vertical esperado de la trayectoria de la barra
    drawRect(
        color = Color(0x1400FFCC),
        topLeft = Offset(discCenterX - discRadius * 0.6f, h * 0.22f),
        size = Size(discRadius * 1.2f, groundY - (h * 0.22f))
    )
    drawLine(
        color = Color(0x8000FFCC),
        start = Offset(discCenterX, groundY),
        end = Offset(discCenterX, h * 0.22f),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )

    // Silueta geométrica esquemática del atleta de perfil en posición de salida
    val athleteColor = Color(0x88FFFFFF)
    val strokeAthlete = 2.dp.toPx()

    // Cabeza de perfil
    drawCircle(
        color = athleteColor,
        radius = 16.dp.toPx(),
        center = Offset(w * 0.52f, h * 0.51f),
        style = Stroke(width = strokeAthlete)
    )
    // Torso inclinado
    drawLine(athleteColor, Offset(w * 0.52f, h * 0.54f), Offset(w * 0.64f, h * 0.64f), strokeAthlete)
    // Cadera a rodilla
    drawLine(athleteColor, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.58f, h * 0.74f), strokeAthlete)
    // Rodilla a pie en el suelo
    drawLine(athleteColor, Offset(w * 0.58f, h * 0.74f), Offset(w * 0.50f, groundY), strokeAthlete)
    // Brazo extendido hacia el disco
    drawLine(athleteColor, Offset(w * 0.54f, h * 0.56f), Offset(discCenterX, discCenterY), strokeAthlete)
}

/**
 * 2. Guía para Barra Colgante / Hang (Hang Clean, Hang Snatch, Tirones, RDL) - Vista Lateral 90°
 */
private fun DrawScope.drawHangBarGuide(w: Float, h: Float) {
    val groundY = h * 0.86f
    val hangBarY = h * 0.58f // Altura de rodilla / medio muslo (suspendida en el aire)

    // Línea de Suelo / Tarima con textura
    drawLine(
        color = Color(0xFF00FFCC),
        start = Offset(w * 0.08f, groundY),
        end = Offset(w * 0.92f, groundY),
        strokeWidth = 3.dp.toPx()
    )
    for (i in 0..12) {
        val x = w * (0.12f + i * 0.06f)
        drawLine(
            color = Color(0x6600FFCC),
            start = Offset(x, groundY),
            end = Offset(x - 8f, groundY + 12f),
            strokeWidth = 1.5.dp.toPx()
        )
    }

    // Disco Olímpico de 45 cm suspendido en los brazos a nivel de rodilla/muslo
    val discRadius = w * 0.13f
    val discCenterX = w * 0.38f
    val discCenterY = hangBarY

    // Círculo del Disco con mira óptica
    drawCircle(
        color = Color(0x3300FFCC),
        radius = discRadius,
        center = Offset(discCenterX, discCenterY)
    )
    drawCircle(
        color = Color(0xFF00FFCC),
        radius = discRadius,
        center = Offset(discCenterX, discCenterY),
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        )
    )
    // Cruz central del disco (eje de la barra) y buje collarín central
    val cross = 14.dp.toPx()
    drawLine(Color(0xFF00FFCC), Offset(discCenterX - cross, discCenterY), Offset(discCenterX + cross, discCenterY), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(discCenterX, discCenterY - cross), Offset(discCenterX, discCenterY + cross), 2.dp.toPx())
    drawCircle(Color(0xFFFFD600), radius = discRadius * 0.28f, center = Offset(discCenterX, discCenterY), style = Stroke(1.5.dp.toPx()))

    // Línea técnica de referencia de altura de suspensión (Hang Baseline)
    drawLine(
        color = Color(0xFFFFD600),
        start = Offset(w * 0.14f, discCenterY),
        end = Offset(w * 0.62f, discCenterY),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
    )

    // Corredor vertical de aceleración ascendente (desde el muslo/rodilla hacia arriba)
    val topPullY = h * 0.22f
    drawRect(
        color = Color(0x1400FFCC),
        topLeft = Offset(discCenterX - discRadius * 0.6f, topPullY),
        size = Size(discRadius * 1.2f, discCenterY - topPullY)
    )
    drawLine(
        color = Color(0x8000FFCC),
        start = Offset(discCenterX, discCenterY),
        end = Offset(discCenterX, topPullY),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )
    // Flecha de empuje ascendente
    drawLine(Color(0xFF00FFCC), Offset(discCenterX, topPullY), Offset(discCenterX - 8f, topPullY + 12f), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(discCenterX, topPullY), Offset(discCenterX + 8f, topPullY + 12f), 2.dp.toPx())

    // Silueta esquemática del atleta de perfil en posición de Hang (Power Position)
    val athleteColor = Color(0x88FFFFFF)
    val strokeAthlete = 2.dp.toPx()
    val athleteX = discCenterX + discRadius * 0.70f

    // Cabeza
    drawCircle(
        color = athleteColor,
        radius = 16.dp.toPx(),
        center = Offset(athleteX + 8f, h * 0.36f),
        style = Stroke(width = strokeAthlete)
    )
    // Torso con leve inclinación atlética
    val shoulder = Offset(athleteX, h * 0.44f)
    val hip = Offset(athleteX + 24f, h * 0.58f)
    drawLine(athleteColor, shoulder, hip, strokeAthlete)
    // Cadera a rodilla semiflexionada
    val knee = Offset(athleteX + 10f, h * 0.72f)
    drawLine(athleteColor, hip, knee, strokeAthlete)
    // Rodilla a pie en el suelo
    drawLine(athleteColor, knee, Offset(athleteX + 5f, groundY), strokeAthlete)
    // Brazo extendido hacia abajo sosteniendo la barra en el muslo/rodilla
    drawLine(athleteColor, shoulder, Offset(discCenterX, discCenterY), strokeAthlete)
}

/**
 * 3. Guía para Barra en Rack (Sentadilla / Press Banca) - Vista Lateral 90°
 */
private fun DrawScope.drawRackBarGuide(w: Float, h: Float) {
    val groundY = h * 0.86f
    val rackPostX = w * 0.28f
    val rackBarY = h * 0.42f // Altura del rack / hombros

    // Suelo
    drawLine(Color(0xFF00FFCC), Offset(w * 0.08f, groundY), Offset(w * 0.92f, groundY), 3.dp.toPx())

    // Poste vertical lateral del rack (perfil 90°)
    drawLine(Color(0x88FFD600), Offset(rackPostX, h * 0.18f), Offset(rackPostX, groundY), 4.dp.toPx())
    // Base del rack en el suelo
    drawLine(Color(0x88FFD600), Offset(rackPostX - 20f, groundY), Offset(rackPostX + 80f, groundY), 4.dp.toPx())

    // Soporte J-Cup horizontal que sostiene la barra
    drawLine(Color(0xFFFFD600), Offset(rackPostX, rackBarY + 12f), Offset(rackPostX + 45f, rackBarY + 12f), 3.5.dp.toPx())
    drawLine(Color(0xFFFFD600), Offset(rackPostX + 45f, rackBarY + 12f), Offset(rackPostX + 45f, rackBarY - 10f), 3.5.dp.toPx())

    // Brazo de seguridad lateral (Spotter arm) a la altura de flexión
    val safetyY = h * 0.64f
    drawLine(
        Color(0x66FFD600),
        Offset(rackPostX, safetyY),
        Offset(rackPostX + 110f, safetyY),
        3.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
    )

    // Disco Olímpico de 45 cm visto de perfil lateral (Círculo perfecto)
    val discRadius = w * 0.13f
    val discCenterX = rackPostX + 35f + discRadius * 0.5f
    val discCenterY = rackBarY

    drawCircle(color = Color(0x3300FFCC), radius = discRadius, center = Offset(discCenterX, discCenterY))
    drawCircle(
        color = Color(0xFF00FFCC),
        radius = discRadius,
        center = Offset(discCenterX, discCenterY),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f))
    )
    // Cruz central del eje de la barra y buje central (collarín 28%)
    val cross = 12.dp.toPx()
    drawLine(Color(0xFF00FFCC), Offset(discCenterX - cross, discCenterY), Offset(discCenterX + cross, discCenterY), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(discCenterX, discCenterY - cross), Offset(discCenterX, discCenterY + cross), 2.dp.toPx())
    drawCircle(Color(0xFFFFD600), radius = discRadius * 0.28f, center = Offset(discCenterX, discCenterY), style = Stroke(1.5.dp.toPx()))

    // Corredor vertical de descenso (excéntrico) y ascenso (concéntrico)
    drawRect(
        color = Color(0x1400FFCC),
        topLeft = Offset(discCenterX - discRadius * 0.6f, rackBarY - 10f),
        size = Size(discRadius * 1.2f, (safetyY - rackBarY) + 30f)
    )
    drawLine(
        color = Color(0x8000FFCC),
        start = Offset(discCenterX, rackBarY),
        end = Offset(discCenterX, safetyY + 20f),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )

    // Silueta lateral del atleta (Perfil 90° en Rack)
    val athleteColor = Color(0x88FFFFFF)
    val strokeAthlete = 2.dp.toPx()
    val athleteX = discCenterX + discRadius * 0.55f

    // Cabeza lateral
    drawCircle(athleteColor, radius = 15.dp.toPx(), center = Offset(athleteX, discCenterY - 26.dp.toPx()), style = Stroke(strokeAthlete))
    // Torso lateral (barra apoyada en trapecio/deltoides)
    drawLine(athleteColor, Offset(athleteX, discCenterY - 10.dp.toPx()), Offset(athleteX + 15f, h * 0.58f), strokeAthlete)
    // Muslo
    drawLine(athleteColor, Offset(athleteX + 15f, h * 0.58f), Offset(athleteX - 10f, h * 0.72f), strokeAthlete)
    // Pierna a pie
    drawLine(athleteColor, Offset(athleteX - 10f, h * 0.72f), Offset(athleteX - 10f, groundY), strokeAthlete)
}

/**
 * 3. Guía para Parado / Overhead (Press / Push Press / Jerk) - Vista Lateral 90°
 */
private fun DrawScope.drawOverheadBarGuide(w: Float, h: Float) {
    val groundY = h * 0.88f
    val overheadLockoutY = h * 0.20f // Bloqueo de brazos arriba
    val rackShoulderY = h * 0.48f   // Barra en hombros / clavícula

    // Suelo
    drawLine(Color(0xFF00FFCC), Offset(w * 0.10f, groundY), Offset(w * 0.90f, groundY), 3.dp.toPx())

    val discRadius = w * 0.12f
    val discCenterX = w * 0.42f

    // 1. Disco Olímpico 45 cm en posición de inicio (Hombros / Front Rack de perfil)
    drawCircle(color = Color(0x2200FFCC), radius = discRadius, center = Offset(discCenterX, rackShoulderY))
    drawCircle(
        color = Color(0xFF00FFCC),
        radius = discRadius,
        center = Offset(discCenterX, rackShoulderY),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f))
    )
    val cross = 12.dp.toPx()
    drawLine(Color(0xFF00FFCC), Offset(discCenterX - cross, rackShoulderY), Offset(discCenterX + cross, rackShoulderY), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(discCenterX, rackShoulderY - cross), Offset(discCenterX, rackShoulderY + cross), 2.dp.toPx())
    drawCircle(Color(0xFFFFD600), radius = discRadius * 0.28f, center = Offset(discCenterX, rackShoulderY), style = Stroke(1.5.dp.toPx()))

    // 2. Disco Olímpico 45 cm en posición de Bloqueo Overhead (arriba)
    drawCircle(color = Color(0x22FFD600), radius = discRadius, center = Offset(discCenterX, overheadLockoutY))
    drawCircle(
        color = Color(0xFFFFD600),
        radius = discRadius,
        center = Offset(discCenterX, overheadLockoutY),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
    )
    drawLine(Color(0xFFFFD600), Offset(discCenterX - cross, overheadLockoutY), Offset(discCenterX + cross, overheadLockoutY), 1.5.dp.toPx())
    drawLine(Color(0xFFFFD600), Offset(discCenterX, overheadLockoutY - cross), Offset(discCenterX, overheadLockoutY + cross), 1.5.dp.toPx())

    // 3. Corredor vertical de trayectoria de elevación
    drawRect(
        color = Color(0x1400FFCC),
        topLeft = Offset(discCenterX - discRadius * 0.5f, overheadLockoutY),
        size = Size(discRadius, rackShoulderY - overheadLockoutY)
    )
    drawLine(
        color = Color(0x8000FFCC),
        start = Offset(discCenterX, rackShoulderY),
        end = Offset(discCenterX, overheadLockoutY),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )

    // Silueta lateral del atleta (Perfil 90° de pie)
    val athleteColor = Color(0x88FFFFFF)
    val strokeAthlete = 2.dp.toPx()
    val athleteX = discCenterX + discRadius * 0.70f

    // Cabeza
    drawCircle(athleteColor, radius = 15.dp.toPx(), center = Offset(athleteX, rackShoulderY - 28.dp.toPx()), style = Stroke(strokeAthlete))
    // Torso erguido
    drawLine(athleteColor, Offset(athleteX, rackShoulderY - 12.dp.toPx()), Offset(athleteX, h * 0.68f), strokeAthlete)
    // Piernas verticales
    drawLine(athleteColor, Offset(athleteX, h * 0.68f), Offset(athleteX - 8f, groundY), strokeAthlete)
    // Brazo extendido hacia arriba (indicador de empuje)
    drawLine(Color(0x66FFD600), Offset(athleteX, rackShoulderY - 8.dp.toPx()), Offset(discCenterX, overheadLockoutY), 1.5.dp.toPx())
}

/**
 * 4. Guía para Salto Vertical - Plano Frontal a ras del suelo (Enfoque exclusivo en ambos pies)
 */
private fun DrawScope.drawVerticalJumpGuide(w: Float, h: Float) {
    val groundY = h * 0.82f
    val flightApexY = h * 0.42f // Margen superior para el despegue de los pies

    // 1. Línea de Tarima / Plataforma de Salto (Línea de Suelo técnica)
    drawLine(
        color = Color(0xFF00FFCC),
        start = Offset(w * 0.08f, groundY),
        end = Offset(w * 0.92f, groundY),
        strokeWidth = 3.5.dp.toPx()
    )
    // Textura / grosor de la plataforma
    drawLine(
        color = Color(0x6600FFCC),
        start = Offset(w * 0.08f, groundY + 12f),
        end = Offset(w * 0.92f, groundY + 12f),
        strokeWidth = 1.5.dp.toPx()
    )
    for (i in 0..14) {
        val x = w * (0.10f + i * 0.055f)
        drawLine(
            color = Color(0x4400FFCC),
            start = Offset(x, groundY),
            end = Offset(x - 6f, groundY + 12f),
            strokeWidth = 1.2.dp.toPx()
        )
    }

    // 2. Cajas / Siluetas de los Dos Pies en Plano Frontal
    val footWidth = w * 0.16f
    val footHeight = 44.dp.toPx()
    val leftFootX = w * 0.28f
    val rightFootX = w * 0.56f

    // Pie Izquierdo (Caja de encuadre frontal)
    drawRoundRect(
        color = Color(0x3300FFCC),
        topLeft = Offset(leftFootX, groundY - footHeight),
        size = Size(footWidth, footHeight),
        cornerRadius = CornerRadius(10f, 10f)
    )
    drawRoundRect(
        color = Color(0xFF00FFCC),
        topLeft = Offset(leftFootX, groundY - footHeight),
        size = Size(footWidth, footHeight),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
    )
    // Puntos de contacto en el suelo del pie izquierdo (Talón y Metatarso/Puntera)
    drawCircle(Color(0xFFFFD600), radius = 4.dp.toPx(), center = Offset(leftFootX + footWidth * 0.3f, groundY))
    drawCircle(Color(0xFFFFD600), radius = 4.dp.toPx(), center = Offset(leftFootX + footWidth * 0.7f, groundY))
    // Línea de tobillo hacia arriba
    drawLine(Color(0x88FFFFFF), Offset(leftFootX + footWidth * 0.5f, groundY - footHeight), Offset(leftFootX + footWidth * 0.5f, groundY - footHeight - 24.dp.toPx()), 2.5.dp.toPx())

    // Pie Derecho (Caja de encuadre frontal)
    drawRoundRect(
        color = Color(0x3300FFCC),
        topLeft = Offset(rightFootX, groundY - footHeight),
        size = Size(footWidth, footHeight),
        cornerRadius = CornerRadius(10f, 10f)
    )
    drawRoundRect(
        color = Color(0xFF00FFCC),
        topLeft = Offset(rightFootX, groundY - footHeight),
        size = Size(footWidth, footHeight),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
    )
    // Puntos de contacto en el suelo del pie derecho
    drawCircle(Color(0xFFFFD600), radius = 4.dp.toPx(), center = Offset(rightFootX + footWidth * 0.3f, groundY))
    drawCircle(Color(0xFFFFD600), radius = 4.dp.toPx(), center = Offset(rightFootX + footWidth * 0.7f, groundY))
    // Línea de tobillo hacia arriba
    drawLine(Color(0x88FFFFFF), Offset(rightFootX + footWidth * 0.5f, groundY - footHeight), Offset(rightFootX + footWidth * 0.5f, groundY - footHeight - 24.dp.toPx()), 2.5.dp.toPx())

    // 3. Corredor de Vuelo Vertical y Despegue de Ambos Pies
    drawRect(
        color = Color(0x1000FFCC),
        topLeft = Offset(leftFootX - 10f, flightApexY),
        size = Size((rightFootX + footWidth + 10f) - (leftFootX - 10f), groundY - flightApexY)
    )

    // Flechas y trayectorias ascendentes de despegue
    val leftCenterCol = leftFootX + footWidth * 0.5f
    val rightCenterCol = rightFootX + footWidth * 0.5f

    drawLine(
        color = Color(0x8000FFCC),
        start = Offset(leftCenterCol, groundY - footHeight),
        end = Offset(leftCenterCol, flightApexY),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )
    // Flecha despegue izq
    drawLine(Color(0xFF00FFCC), Offset(leftCenterCol, flightApexY), Offset(leftCenterCol - 8f, flightApexY + 12f), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(leftCenterCol, flightApexY), Offset(leftCenterCol + 8f, flightApexY + 12f), 2.dp.toPx())

    drawLine(
        color = Color(0x8000FFCC),
        start = Offset(rightCenterCol, groundY - footHeight),
        end = Offset(rightCenterCol, flightApexY),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )
    // Flecha despegue der
    drawLine(Color(0xFF00FFCC), Offset(rightCenterCol, flightApexY), Offset(rightCenterCol - 8f, flightApexY + 12f), 2.dp.toPx())
    drawLine(Color(0xFF00FFCC), Offset(rightCenterCol, flightApexY), Offset(rightCenterCol + 8f, flightApexY + 12f), 2.dp.toPx())

    // Línea superior del margen de vuelo
    drawLine(
        color = Color(0xFFFFD600),
        start = Offset(leftFootX - 20f, flightApexY),
        end = Offset(rightFootX + footWidth + 20f, flightApexY),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
    )
}
