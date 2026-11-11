package com.ffvpn.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.ffvpn.client.MainActivity
import com.ffvpn.client.R

/** The ongoing notification that keeps the tunnel service alive. */
object Notifications {
    const val CHANNEL = "tunnel"
    const val ID = 7

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = context.getString(R.string.app_name)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, FirefoxVpnService::class.java).setAction(FirefoxVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_notification),
                    context.getString(R.string.action_disconnect),
                    stop
                ).build()
            )
            .build()
    }

    fun update(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(ID, build(context, text)) }
    }
}
