package es.us.ussync.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import es.us.ussync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class AppUpdate(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val publishedAt: String?,
)

object AppUpdater {
    private const val GITHUB_REPO = "JoseGarciaMayen/ussync"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun isNewerVersion(remote: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val rParts = remote.trim().trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val cParts = current.trim().trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(rParts.size, cParts.size)) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder().build()
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("User-Agent", "USSync-Android-Updater")
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                val json = JSONObject(body)
                val tagName = json.optString("tag_name").ifBlank { json.optString("name") }
                if (tagName.isBlank() || !isNewerVersion(tagName)) return@withContext null

                val assets = json.optJSONArray("assets") ?: return@withContext null
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (downloadUrl.isNullOrBlank()) return@withContext null

                AppUpdate(
                    versionName = tagName.trimStart('v'),
                    downloadUrl = downloadUrl,
                    releaseNotes = json.optString("body").takeIf { it.isNotBlank() },
                    publishedAt = json.optString("published_at").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()
    }

    suspend fun downloadUpdate(
        context: Context,
        update: AppUpdate,
        progress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "USSync-${update.versionName}.apk")
        if (target.exists()) target.delete()

        val client = OkHttpClient.Builder().followRedirects(true).build()
        val request = Request.Builder()
            .url(update.downloadUrl)
            .header("User-Agent", "USSync-Android-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Error al descargar actualización: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Respuesta vacía al descargar actualización.")
            val total = body.contentLength()
            var received = 0L
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        received += read
                        if (total > 0) progress(received.toFloat() / total)
                    }
                }
            }
        }
        target
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun installUpdate(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
