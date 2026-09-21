package es.us.ussync.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import es.us.ussync.MainActivity

object NotificationTest {
    fun send(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("news", "Novedades de tus asignaturas", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("news")?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(102, Notification.Builder(context, "news")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Las notificaciones de USSync funcionan")
            .setContentText("Este es un aviso de prueba. Los avisos de novedades están activados.")
            .setContentIntent(open).setAutoCancel(true).build())
        return true
    }

    fun settingsIntent(context: Context): Intent {
        val manager = context.getSystemService(NotificationManager::class.java)
        return if (manager.areNotificationsEnabled() && manager.getNotificationChannel("news")?.importance == NotificationManager.IMPORTANCE_NONE)
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "news")
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}
