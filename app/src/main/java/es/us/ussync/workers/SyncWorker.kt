package es.us.ussync.workers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import androidx.work.*
import es.us.ussync.MainActivity
import es.us.ussync.BuildConfig
import es.us.ussync.blackboard.*
import es.us.ussync.data.*
import es.us.ussync.storage.LibraryDownloader
import es.us.ussync.storage.LocalFileState
import es.us.ussync.sync.SyncLocks
import es.us.ussync.data.reconcileMissingDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Horas locales durante las que se omiten las consultas automáticas. */
object QuietHours {
    fun isActive(now: LocalTime, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start !in 0..23 || end !in 0..23 || start == end) return false
        val hour = now.hour
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    fun resumesAt(start: Int?, end: Int?): String? = end?.takeIf { start != null && start != end }?.let { "%02d:00".format(it) }
}

object SyncSchedule {
    const val NAME = "ussync-periodic-scan"
    private const val DEBUG_NAME = "ussync-minute-debug"
    const val DEBUG_TOKEN = "debug_token"
    private fun preferences(context: Context) = context.getSharedPreferences("sync_schedule", Context.MODE_PRIVATE)
    @Synchronized
    fun update(context: Context, minutes: Long, wifi: Boolean, battery: Boolean) {
        val manager = WorkManager.getInstance(context)
        val token = java.util.UUID.randomUUID().toString()
        preferences(context).edit().putString(DEBUG_TOKEN, if (minutes == 1L && BuildConfig.DEBUG) token else null)
            .putBoolean("wifi", wifi).putBoolean("battery", battery).apply()
        manager.cancelUniqueWork(DEBUG_NAME)
        if (minutes == 0L) { manager.cancelUniqueWork(NAME); return }
        if (minutes == 1L && BuildConfig.DEBUG) {
            manager.cancelUniqueWork(NAME)
            enqueueDebug(context, token, ExistingWorkPolicy.REPLACE)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(battery).build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun isCurrentDebug(context: Context, token: String): Boolean = BuildConfig.DEBUG && preferences(context).getString(DEBUG_TOKEN, null) == token

    @Synchronized
    fun nextDebug(context: Context, token: String) {
        if (isCurrentDebug(context, token)) enqueueDebug(context, token, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueDebug(context: Context, token: String, policy: ExistingWorkPolicy) {
        val prefs = preferences(context)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setInputData(workDataOf(DEBUG_TOKEN to token))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (prefs.getBoolean("wifi", true)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(prefs.getBoolean("battery", true)).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DEBUG_NAME, policy, request)
    }
}

class DownloadPendingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("download_pending" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("ussync-download-pending", ExistingWorkPolicy.KEEP, work)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val catalog = AppDatabase.get(applicationContext).catalogDao()
        val debugToken = inputData.getString(SyncSchedule.DEBUG_TOKEN)
        if (debugToken != null && !SyncSchedule.isCurrentDebug(applicationContext, debugToken)) return@withContext Result.success()
        try {
            val forceDownload = inputData.getBoolean("download_pending", false)
            val quietStart = catalog.setting("quiet_hours_start")?.toIntOrNull()
            // Al activar la pausa, 07:00 es el fin inicial hasta que se elija otro.
            val quietEnd = catalog.setting("quiet_hours_end")?.toIntOrNull() ?: quietStart?.let { 7 }
            if (!forceDownload && QuietHours.isActive(LocalTime.now(), quietStart, quietEnd)) {
                val resumesAt = QuietHours.resumesAt(quietStart, quietEnd)
                catalog.putSetting(AppSettingsEntity("background_status", "Consulta automática en pausa hasta las $resumesAt."))
                return@withContext Result.success()
            }
            catalog.putSetting(AppSettingsEntity("last_attempt", Instant.now().toString()))
            withContext(Dispatchers.Main) { CookieManager.getInstance().setAcceptCookie(true) }
            val profile = EvProfileClient().verify()
            val user = when (profile) {
                is ProfileResult.Valid -> profile.user
                ProfileResult.Expired -> {
                    sessionExpired(catalog)
                    return@withContext Result.success()
                }
                is ProfileResult.Failed -> {
                    catalog.putSetting(AppSettingsEntity("background_status", "No se pudo comprobar la sesión: ${profile.message}"))
                    return@withContext if (debugToken != null || runAttemptCount >= 3) Result.success() else Result.retry()
                }
            }
            applicationContext.getSystemService(NotificationManager::class.java).cancel(SESSION_NOTIFICATION_ID)
            val courses = catalog.evCourses().filter { it.selected }
            if (courses.isEmpty()) return@withContext Result.success()
            val client = BlackboardClient()
            val blocked = parseExtensionList(catalog.setting("blocked_extensions"))
            if (!forceDownload) SyncLocks.scan.withLock {
                val documents = client.documents(user, courses.map { EvCourse(it.remoteId, it.name, it.remoteId, it.folder) })
                val missingDownloads = catalog.reconcileMissingDownloads(applicationContext)
                val libraryTree = catalog.setting("library_tree_uri")
                val courseFolders = courses.mapNotNull { c -> c.folder?.let { c.remoteId to it } }.toMap()
                val existingFiles = catalog.reconcileExistingLibraryFiles(applicationContext, libraryTree, documents, courseFolders)
                catalog.recordEvScan(documents, courses.map { it.remoteId }, missingDownloads, blocked, existingFiles)
                catalog.putSetting(AppSettingsEntity("last_scan", Instant.now().toString()))
            }
            val rules = catalog.savedRules()
            val defaultAction = catalog.setting("default_action") ?: "ASK"
            val courseIds = courses.map { it.remoteId }.toSet()
            val pending = catalog.pendingDocuments().filter { it.documentKey.split(":").getOrNull(1) in courseIds }
            for (item in pending) {
                val action = if (forceDownload) "AUTO_DOWNLOAD" else rules.firstOrNull { it.matches(item) }?.action ?: defaultAction
                if (action == "IGNORE" || isBlockedExtension(item.filename, blocked)) {
                    catalog.updateInboxState(item.inboxId, "IGNORED", Instant.now().toString())
                    continue
                }
                if (action != "AUTO_DOWNLOAD") continue
                if (item.availableFrom != null && es.us.ussync.ui.isFutureDate(item.availableFrom)) continue
                val tree = catalog.setting("library_tree_uri") ?: continue
                if (catalog.setting("wifi_only") != "false") {
                    val network = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
                    if (network.getNetworkCapabilities(network.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) continue
                }
                val remote = catalog.document(item.documentKey) ?: continue
                val course = courses.first { it.remoteId == remote.courseId }
                val doc = EvDocument(remote.key, course.folder ?: course.name, remote.relativePath.split('/').filter { it.isNotBlank() }, remote.filename, remote.revision, remote.size, remote.availableFrom)
                val temp = File.createTempFile("background-", ".part", applicationContext.cacheDir)
                try {
                    client.download(user, doc, temp)
                    SyncLocks.publication.withLock {
                        val previous = catalog.latestDownload(remote.key)?.let { LocalFileState(it.targetUri, it.sha256) }
                        val file = LibraryDownloader(applicationContext).publish(tree, doc, temp, previous)
                        catalog.recordDownload(DownloadRecordEntity(documentKey = remote.key, remoteRevision = remote.revision, targetUri = file.uri,
                            sha256 = file.sha256, bytes = temp.length(), result = if (forceDownload) "DOWNLOADED" else "AUTO_DOWNLOADED", createdAt = Instant.now().toString()))
                        catalog.markDownloaded(remote.key, file.sha256, remote.revision)
                        catalog.updateInboxState(item.inboxId, "DOWNLOADED", Instant.now().toString())
                    }
                } finally { temp.delete() }
            }
            if (!forceDownload) catalog.seviusSelections().filter { it.courseId in courseIds }.forEach { selection ->
                es.us.ussync.sevius.downloadTeachingSelection(applicationContext, selection)
            }
            catalog.putSetting(AppSettingsEntity("background_status", "Consulta completada"))
            val remaining = catalog.pendingDocuments().filter { it.documentKey.split(":").getOrNull(1) in courseIds }
            if (catalog.setting("notifications") == "true" && remaining.isNotEmpty()) notifyPending(remaining.size)
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is EvSessionExpiredException) {
                sessionExpired(catalog)
                return@withContext Result.success()
            }
            catalog.putSetting(AppSettingsEntity("background_status", "No se pudo completar la consulta. Revisa la conexión o tu sesión en Inicio."))
            if (debugToken != null) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (debugToken != null && currentCoroutineContext().isActive) SyncSchedule.nextDebug(applicationContext, debugToken)
        }
    }

    private suspend fun sessionExpired(catalog: CatalogDao) {
        catalog.putSetting(AppSettingsEntity("background_status", "La sesión ha caducado. Conecta de nuevo Enseñanza Virtual para continuar las consultas."))
        if (catalog.setting("notify_session") == "false") return
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("session", "Acceso a Enseñanza Virtual", NotificationManager.IMPORTANCE_DEFAULT))
        val reconnect = PendingIntent.getActivity(context, 3,
            Intent(context, MainActivity::class.java).putExtra("reconnect_session", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(SESSION_NOTIFICATION_ID, Notification.Builder(context, "session")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Vuelve a conectar Enseñanza Virtual")
            .setContentText("Tu sesión ha caducado. Abre USSync para iniciar sesión y continuar las consultas.")
            .setContentIntent(reconnect).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Conectar", reconnect).build()).build())
    }

    companion object { const val SESSION_NOTIFICATION_ID = 101 }

    private fun notifyPending(count: Int) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("news", "Novedades de tus asignaturas", NotificationManager.IMPORTANCE_DEFAULT))
        val review = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).putExtra("review_news", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val download = PendingIntent.getBroadcast(context, 2, Intent(context, DownloadPendingReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(100, Notification.Builder(context, "news").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$count documentos por revisar").setContentText("Revisa tus novedades o descarga los pendientes con Wi-Fi.")
            .setContentIntent(review).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Revisar", review).build())
            .addAction(Notification.Action.Builder(null, "Descargar con Wi-Fi", download).build()).build())
    }
}
