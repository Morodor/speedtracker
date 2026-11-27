package com.speedtracker.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.speedtracker.core.update.AppReleaseInfo
import com.speedtracker.core.update.AppUpdateManager
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private enum class UpdateDownloadState {
    IDLE,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var state by remember { mutableStateOf(UpdateDownloadState.IDLE) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(release.apkSize) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = {
        if (state != UpdateDownloadState.DOWNLOADING) onDismiss()
    }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .border(1.2.dp, Color(0xFF00FFCC), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF00FFCC).copy(alpha = 0.15f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Actualización",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "¡Nueva Versión Disponible!",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Badges de versión
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        color = Color(0xFF334155),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Actual: v${AppUpdateManager.getAppVersionName(context)}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(14.dp)
                    )

                    Surface(
                        color = Color(0xFF00FFCC).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC))
                    ) {
                        Text(
                            text = "Nueva: ${release.tagName}",
                            color = Color(0xFF00FFCC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Contenido dinámico según estado
                when (state) {
                    UpdateDownloadState.IDLE -> {
                        // Changelog Box
                        Text(
                            text = "Novedades y Mejoras:",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .border(0.8.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = release.changelog.ifBlank { "Mejoras de precisión en tracking, velocidad y corrección de errores." },
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }

                        if (release.apkSize > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val sizeMb = String.format(Locale.US, "%.1f MB", release.apkSize / (1024.0 * 1024.0))
                            Text(
                                text = "Tamaño de descarga: $sizeMb",
                                color = Color(0xFF64748B),
                                fontSize = 10.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Más tarde",
                                        fontSize = 12.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    state = UpdateDownloadState.DOWNLOADING
                                    downloadProgress = 0f
                                    downloadedBytes = 0L
                                    totalBytes = release.apkSize
                                    errorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val file = AppUpdateManager.downloadUpdate(
                                                context = context,
                                                downloadUrl = release.apkDownloadUrl,
                                                onProgress = { prog, dl, tot ->
                                                    val effectiveTotal = if (tot > 0L) tot else release.apkSize
                                                    totalBytes = effectiveTotal
                                                    downloadedBytes = dl
                                                    downloadProgress = if (effectiveTotal > 0L) {
                                                        (dl.toFloat() / effectiveTotal.toFloat()).coerceIn(0f, 1f)
                                                    } else {
                                                        prog
                                                    }
                                                }
                                            )
                                            downloadedFile = file
                                            state = UpdateDownloadState.DOWNLOADED
                                            AppUpdateManager.installApk(context, file, release.apkDownloadUrl)
                                        } catch (e: Exception) {
                                            errorMessage = e.message ?: "Error al descargar la actualización."
                                            state = UpdateDownloadState.ERROR
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(48.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00FFCC),
                                    contentColor = Color.Black
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Descargar e Instalar",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        lineHeight = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    UpdateDownloadState.DOWNLOADING -> {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = downloadProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = Color(0xFF00FFCC),
                            trackColor = Color(0xFF334155)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val percent = (downloadProgress * 100).toInt()
                        val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                        val totMb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))

                        Text(
                            text = "Descargando actualización: $percent%",
                            color = Color(0xFF00FFCC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "$dlMb MB / $totMb MB",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Al completarse la descarga, se abrirá el instalador del sistema.",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp
                        )
                    }

                    UpdateDownloadState.DOWNLOADED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Descarga completa",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pulsa el botón para iniciar la instalación.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                downloadedFile?.let { AppUpdateManager.installApk(context, it, release.apkDownloadUrl) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FFCC),
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Instalar Ahora", fontWeight = FontWeight.Bold)
                        }

                        if (!AppUpdateManager.canInstallPackages(context)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Nota: Si es la primera vez que actualizas desde la app, activa el interruptor 'Permitir desde esta fuente' y vuelve atrás.",
                                color = Color(0xFFFF9800),
                                fontSize = 10.5.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    UpdateDownloadState.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Error al descargar",
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = errorMessage ?: "No se pudo completar la descarga.",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.5.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cerrar", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    state = UpdateDownloadState.IDLE
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                            ) {
                                Text("Reintentar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
