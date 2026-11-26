package com.speedtracker.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSize: Long,
    val publishedAt: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {

    private const val PREFS_NAME = "speed_tracker_update_prefs"
    private const val KEY_REPO_OWNER = "repo_owner"
    private const val KEY_REPO_NAME = "repo_name"

    // Repositorio por defecto en GitHub
    const val DEFAULT_REPO_OWNER = "Morodor"
    const val DEFAULT_REPO_NAME = "speedtracker"

    fun getRepoOwner(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_REPO_OWNER, DEFAULT_REPO_OWNER) ?: DEFAULT_REPO_OWNER
        return if (saved == "bayron") DEFAULT_REPO_OWNER else saved
    }

    fun getRepoName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_REPO_NAME, DEFAULT_REPO_NAME) ?: DEFAULT_REPO_NAME
        return if (saved == "SpeedTracker") DEFAULT_REPO_NAME else saved
    }

    fun setRepoInfo(context: Context, owner: String, repo: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REPO_OWNER, owner.trim())
            .putString(KEY_REPO_NAME, repo.trim())
            .apply()
    }

    fun getAppVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.9.1"
        } catch (e: Exception) {
            "1.9.1"
        }
    }

    /**
     * Consulta la API de GitHub Releases para ver si existe una versión más reciente
     */
    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val owner = getRepoOwner(context)
        val repo = getRepoName(context)
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val curVersion = getAppVersionName(context)

        try {
            val url = URL(apiUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "SpeedTracker-Android/$curVersion")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode == 404) {
                return@withContext UpdateCheckResult.Error("No se encontró ningún Release público en GitHub ($owner/$repo).")
            }
            if (responseCode !in 200..299) {
                return@withContext UpdateCheckResult.Error("GitHub respondió con error HTTP $responseCode")
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)

            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", tagName)
            val body = json.optString("body", "Mejoras de rendimiento y corrección de errores.")
            val publishedAt = json.optString("published_at", "")

            // Buscar el asset .apk en los archivos adjuntos del release
            var apkUrl: String? = null
            var apkSize: Long = 0
            val assetsArray = json.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isNullOrEmpty()) {
                return@withContext UpdateCheckResult.Error("El release $tagName existe pero no tiene ningún archivo .apk adjunto.")
            }

            val cleanRemoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val currentVersion = curVersion.removePrefix("v").removePrefix("V").trim()

            if (isNewerVersion(cleanRemoteVersion, currentVersion)) {
                val releaseInfo = AppReleaseInfo(
                    tagName = tagName,
                    versionName = cleanRemoteVersion,
                    releaseTitle = releaseTitle,
                    changelog = body,
                    apkDownloadUrl = apkUrl,
                    apkSize = apkSize,
                    publishedAt = publishedAt
                )
                UpdateCheckResult.UpdateAvailable(releaseInfo)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UpdateCheckResult.Error(e.message ?: "Error de red al comprobar actualizaciones.")
        }
    }

    /**
     * Compara dos versiones semánticas (ej. "1.9.1" vs "1.9.0")
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val remoteParts = remote.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (e: Exception) {
            return remote != current
        }
    }

    /**
     * Descarga el APK con seguimiento de progreso (0.0f a 1.0f)
     */
    suspend fun downloadUpdate(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, "SpeedTracker_Update.apk")
        if (targetFile.exists()) targetFile.delete()

        var currentUrl = downloadUrl
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            // Manejar redirecciones de GitHub Release (301, 302, 307 -> AWS S3 / CDN)
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "SpeedTracker-Android/${getAppVersionName(context)}")
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307
                ) {
                    currentUrl = connection.getHeaderField("Location")
                    redirects++
                } else {
                    break
                }
            }

            val totalBytes = connection?.contentLengthLong ?: -1L
            inputStream = connection?.inputStream ?: throw Exception("No se pudo obtener el flujo de descarga")
            outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var downloadedBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val prog = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                onProgress(prog, downloadedBytes, totalBytes)
            }

            outputStream.flush()
            targetFile
        } finally {
            try { outputStream?.close() } catch (e: Exception) {}
            try { inputStream?.close() } catch (e: Exception) {}
            try { connection?.disconnect() } catch (e: Exception) {}
        }
    }

    /**
     * Comprueba si la app tiene permiso para solicitar la instalación de paquetes desconocidos
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Redirige al usuario al ajuste del sistema para conceder permiso de instalación
     */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Descarga e instala el APK de forma segura a través del navegador / gestor del sistema Android
     * sin requerir el permiso REQUEST_INSTALL_PACKAGES (evitando el bloqueo de Google Play Protect).
     */
    fun openSystemBrowserDownload(context: Context, downloadUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Inicia la instalación del APK descargado invocando al Package Installer del sistema Android
     */
    fun installApk(context: Context, apkFile: File, fallbackUrl: String? = null) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            if (!fallbackUrl.isNullOrEmpty()) {
                openSystemBrowserDownload(context, fallbackUrl)
            }
        }
    }
}
