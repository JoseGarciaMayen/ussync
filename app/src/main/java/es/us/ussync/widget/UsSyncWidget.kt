package es.us.ussync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import es.us.ussync.MainActivity
import es.us.ussync.R
import es.us.ussync.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Widget de escritorio de USSync.
 *
 * Muestra:
 *  - Estado de la sesión EV (conectado / sesión caducada / sin configurar)
 *  - Número de documentos pendientes de revisión
 *  - Hora de la última sincronización
 *  - Botón "Conectar" cuando la sesión ha caducado
 *
 * Los datos provienen de [AppDatabase] vía [WidgetDataSource].
 * El widget se actualiza cada vez que [AppWidgetManager.ACTION_APPWIDGET_UPDATE] se recibe
 * (período configurado en widget_info.xml: 30 min) y también cada vez que [updateAll]
 * es llamado desde [es.us.ussync.workers.SyncWorker].
 */
class UsSyncWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        /** Actualiza todos los widgets instalados. Llama desde SyncWorker tras cada scan. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, UsSyncWidget::class.java)
            )
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                val data = WidgetDataSource.load(context)
                val views = buildViews(context, data)
                manager.updateAppWidget(widgetId, views)
            }
        }

        private fun buildViews(context: Context, data: WidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_ussync)

            // Intent para abrir la app (pantalla principal)
            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)

            // Intent para el botón "Conectar" (abre la app indicando que hay que reconectar)
            val connectIntent = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).putExtra("reconnect_session", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_connect_btn, connectIntent)

            when (data.sessionState) {
                WidgetSessionState.CONNECTED -> {
                    views.setTextViewText(R.id.widget_status, "✓ EV conectado")
                    views.setViewVisibility(R.id.widget_connect_btn, View.GONE)
                    if (data.pendingCount > 0) {
                        views.setTextViewText(
                            R.id.widget_pending,
                            "${data.pendingCount} documento${if (data.pendingCount == 1) "" else "s"} pendiente${if (data.pendingCount == 1) "" else "s"}",
                        )
                    } else {
                        views.setTextViewText(R.id.widget_pending, "Sin novedades")
                    }
                }
                WidgetSessionState.EXPIRED -> {
                    views.setTextViewText(R.id.widget_status, "⚠ Sesión caducada")
                    views.setTextViewText(R.id.widget_pending, "")
                    views.setViewVisibility(R.id.widget_connect_btn, View.VISIBLE)
                }
                WidgetSessionState.UNKNOWN -> {
                    views.setTextViewText(R.id.widget_status, "Sin configurar")
                    views.setTextViewText(R.id.widget_pending, "")
                    views.setViewVisibility(R.id.widget_connect_btn, View.GONE)
                }
            }

            // Última sincronización
            val lastSync = data.lastScanAt
            if (lastSync != null) {
                val formatted = runCatching {
                    val instant = Instant.parse(lastSync)
                    val zdt = instant.atZone(ZoneId.systemDefault())
                    DateTimeFormatter.ofPattern("HH:mm · d MMM").format(zdt)
                }.getOrNull() ?: lastSync
                views.setTextViewText(R.id.widget_last_sync, "Últ. sync: $formatted")
            } else {
                views.setTextViewText(R.id.widget_last_sync, "Sin sincronizar")
            }

            return views
        }
    }
}

enum class WidgetSessionState { CONNECTED, EXPIRED, UNKNOWN }

data class WidgetData(
    val sessionState: WidgetSessionState,
    val pendingCount: Int,
    val lastScanAt: String?,
)

/**
 * Carga los datos necesarios para el widget desde [AppDatabase] de forma síncrona
 * (debe llamarse desde un hilo IO).
 */
object WidgetDataSource {
    suspend fun load(context: Context): WidgetData {
        val dao = AppDatabase.get(context).catalogDao()
        val backgroundStatus = dao.setting("background_status").orEmpty()
        val lastScan = dao.setting("last_scan")
        val sessionExpired = backgroundStatus.contains("caducado", ignoreCase = true) ||
            backgroundStatus.contains("caducada", ignoreCase = true)

        // Si no hay ningún scan registrado, la app aún no ha sido configurada
        val sessionState = when {
            lastScan == null && !sessionExpired -> WidgetSessionState.UNKNOWN
            sessionExpired -> WidgetSessionState.EXPIRED
            else -> WidgetSessionState.CONNECTED
        }

        val courses = dao.evCourses().filter { it.selected }
        val courseIds = courses.map { it.remoteId }.toSet()
        val pendingCount = if (courseIds.isEmpty()) 0 else
            dao.pendingDocuments().count { it.documentKey.split(":").getOrNull(1) in courseIds }

        return WidgetData(
            sessionState = sessionState,
            pendingCount = pendingCount,
            lastScanAt = lastScan,
        )
    }
}
