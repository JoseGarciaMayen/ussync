package es.us.ussync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.webkit.CookieManager
import android.widget.RemoteViews
import es.us.ussync.MainActivity
import es.us.ussync.R
import es.us.ussync.blackboard.EvProfileClient
import es.us.ussync.blackboard.ProfileResult
import es.us.ussync.data.AppDatabase
import es.us.ussync.data.AppSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Widget de escritorio de USSync.
 *
 * Muestra:
 *  - Estado de la sesión EV (conectado / sesión caducada / desconectado / sin configurar)
 *  - Número de documentos pendientes de revisión
 *  - Hora de la última sincronización (tocar para refrescar el widget)
 *  - Botón "Conectar" cuando la sesión ha caducado o está desconectada
 */
class UsSyncWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, UsSyncWidget::class.java))
                    if (ids.isNotEmpty()) {
                        val data = WidgetDataSource.load(context, forceVerify = true)
                        val views = buildViews(context, data)
                        for (id in ids) manager.updateAppWidget(id, views)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = WidgetDataSource.load(context, forceVerify = true)
                val views = buildViews(context, data)
                for (id in appWidgetIds) {
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "es.us.ussync.widget.ACTION_REFRESH"

        /** Actualiza todos los widgets instalados de forma reactiva e inmediata. */
        fun updateAll(context: Context) {
            val appContext = context.applicationContext ?: context
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, UsSyncWidget::class.java))
            if (ids.isEmpty()) return

            CoroutineScope(Dispatchers.IO).launch {
                val data = WidgetDataSource.load(appContext, forceVerify = false)
                val views = buildViews(appContext, data)
                for (id in ids) {
                    manager.updateAppWidget(id, views)
                }
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

            // Intent para el botón "Conectar" (abre la app para conectar)
            val connectIntent = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).putExtra("reconnect_session", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_connect_btn, connectIntent)

            // Intent para refrescar el widget al tocar la última sincronización
            val refreshIntent = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, UsSyncWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_last_sync, refreshIntent)

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
                    views.setTextViewText(R.id.widget_connect_btn, "Reconectar")
                    views.setViewVisibility(R.id.widget_connect_btn, View.VISIBLE)
                }
                WidgetSessionState.DISCONNECTED -> {
                    views.setTextViewText(R.id.widget_status, "Sesión desconectada")
                    views.setTextViewText(R.id.widget_pending, "")
                    views.setTextViewText(R.id.widget_connect_btn, "Conectar")
                    views.setViewVisibility(R.id.widget_connect_btn, View.VISIBLE)
                }
                WidgetSessionState.UNKNOWN -> {
                    views.setTextViewText(R.id.widget_status, "Sin configurar")
                    views.setTextViewText(R.id.widget_pending, "")
                    views.setTextViewText(R.id.widget_connect_btn, "Conectar")
                    views.setViewVisibility(R.id.widget_connect_btn, View.VISIBLE)
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

enum class WidgetSessionState { CONNECTED, EXPIRED, DISCONNECTED, UNKNOWN }

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
    suspend fun load(context: Context, forceVerify: Boolean = false): WidgetData {
        val dao = AppDatabase.get(context).catalogDao()
        val evSessionStateSetting = dao.setting("ev_session_state")
        val backgroundStatus = dao.setting("background_status").orEmpty()
        val lastScan = dao.setting("last_scan")

        // 1. Comprobación local de cookies
        val cookies = runCatching {
            CookieManager.getInstance().getCookie("https://ev.us.es")
        }.getOrNull()
        val hasCookies = !cookies.isNullOrBlank()

        var sessionState = when {
            evSessionStateSetting == "DISCONNECTED" -> WidgetSessionState.DISCONNECTED
            evSessionStateSetting == "EXPIRED" -> WidgetSessionState.EXPIRED
            backgroundStatus.contains("caducad", ignoreCase = true) -> WidgetSessionState.EXPIRED
            backgroundStatus.contains("desconectad", ignoreCase = true) -> WidgetSessionState.DISCONNECTED
            !hasCookies && lastScan != null -> WidgetSessionState.EXPIRED
            !hasCookies && lastScan == null -> WidgetSessionState.UNKNOWN
            evSessionStateSetting == "CONNECTED" -> WidgetSessionState.CONNECTED
            lastScan != null -> WidgetSessionState.CONNECTED
            else -> WidgetSessionState.UNKNOWN
        }

        // Si hay cookies y se fuerza verificación o el estado previo era CONNECTED,
        // verificamos con la API para capturar caducidades del servidor.
        if (hasCookies && (forceVerify || sessionState == WidgetSessionState.CONNECTED)) {
            val profileResult = runCatching {
                withContext(Dispatchers.IO) {
                    EvProfileClient().verify()
                }
            }.getOrNull()

            when (profileResult) {
                is ProfileResult.Valid -> {
                    sessionState = WidgetSessionState.CONNECTED
                    dao.putSetting(AppSettingsEntity("ev_session_state", "CONNECTED"))
                }
                ProfileResult.Expired -> {
                    sessionState = WidgetSessionState.EXPIRED
                    dao.putSetting(AppSettingsEntity("ev_session_state", "EXPIRED"))
                    dao.putSetting(AppSettingsEntity("background_status", "La sesión ha caducado."))
                }
                else -> {
                    // Timeout o sin red: conservamos el sessionState calculado previamente
                }
            }
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
