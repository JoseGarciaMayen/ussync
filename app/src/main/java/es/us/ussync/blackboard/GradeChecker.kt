package es.us.ussync.blackboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import es.us.ussync.MainActivity
import es.us.ussync.data.CatalogDao
import es.us.ussync.data.GradeSnapshotEntity
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Comprueba las calificaciones del gradebook de Blackboard para todas las asignaturas
 * seleccionadas y emite una notificación por cada nota nueva o modificada.
 *
 * Se llama desde [es.us.ussync.workers.SyncWorker] al final de cada ciclo de sincronización.
 */
object GradeChecker {

    private const val CHANNEL_ID = "grades"
    private const val CHANNEL_NAME = "Calificaciones"
    private const val EV_BASE = "https://ev.us.es"

    /**
     * Consulta el gradebook de cada curso seleccionado y persiste cambios.
     * Devuelve el número de nuevas calificaciones detectadas.
     */
    suspend fun checkGrades(
        context: Context,
        user: EvUser,
        courseIds: List<String>,
        dao: CatalogDao,
    ): Int {
        if (courseIds.isEmpty()) return 0
        val client = OkHttpClient.Builder()
            .addInterceptor(WebViewCookieInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        var newGrades = 0
        val now = Instant.now().toString()

        for (courseId in courseIds) {
            try {
                val encodedCourse = URLEncoder.encode(courseId, StandardCharsets.UTF_8.name())
                val encodedUser = URLEncoder.encode(user.id, StandardCharsets.UTF_8.name())
                // Endpoint REST de Blackboard Learn: notas del usuario en la asignatura
                val url = "$EV_BASE${user.apiPrefix}/courses/$encodedCourse/gradebook/users/$encodedUser"
                    .toHttpUrl()

                val body = client.newCall(Request.Builder().url(url).get().build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.string()
                    } ?: continue

                val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: continue

                // La API devuelve { "results": [ { "columnId", "columnName", "score", … } ] }
                val items = when (parsed) {
                    is org.json.JSONObject -> parsed.optJSONArray("results")
                    is org.json.JSONArray -> parsed
                    else -> null
                } ?: continue

                val existing = dao.gradeSnapshotsForCourse(courseId).associateBy { it.columnId }
                val newSnapshots = mutableListOf<GradeSnapshotEntity>()
                val changedColumns = mutableListOf<Pair<String, String>>() // name, score

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val columnId = item.optString("columnId").ifBlank { item.optString("id") }
                    if (columnId.isBlank()) continue
                    val columnName = item.optString("columnName").ifBlank { item.optString("name", columnId) }

                    // score puede venir como número o cadena
                    val scoreRaw = item.opt("score")
                    val score: String? = when (scoreRaw) {
                        null, JSONObject.NULL -> null
                        else -> scoreRaw.toString().trim().ifBlank { null }
                    }
                    val possible: String? = item.opt("possible")?.toString()?.trim()?.ifBlank { null }
                    val modified: String? = item.optString("modified").ifBlank { null }

                    val prev = existing[columnId]
                    val hasChange = prev == null ||
                        (score != null && score != prev.score) ||
                        (modified != null && modified != prev.modified)

                    if (hasChange && score != null) {
                        changedColumns += columnName to score
                        newGrades++
                    }

                    newSnapshots += GradeSnapshotEntity(
                        courseId = courseId,
                        columnId = columnId,
                        columnName = columnName,
                        score = score,
                        possible = possible,
                        modified = modified,
                        seenAt = now,
                    )
                }

                if (newSnapshots.isNotEmpty()) {
                    dao.upsertGradeSnapshots(newSnapshots)
                }

                if (changedColumns.isNotEmpty()) {
                    notifyGrades(context, courseId, changedColumns)
                }
            } catch (_: Exception) {
                // Ignorar fallos de un curso individual; no interrumpir el resto
            }
        }
        return newGrades
    }

    private fun notifyGrades(
        context: Context,
        courseId: String,
        columns: List<Pair<String, String>>,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        )

        val openIntent = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (columns.size == 1) "Nueva calificación publicada" else "${columns.size} calificaciones publicadas"
        val text = columns.joinToString(" · ") { (name, score) -> "$name: $score" }

        // Usamos courseId como parte del notification ID para no solapar distintas asignaturas
        val notifId = 200 + (courseId.hashCode() and 0x7FFFFFFF) % 100
        manager.notify(
            notifId,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_report_image)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .build()
        )
    }
}
