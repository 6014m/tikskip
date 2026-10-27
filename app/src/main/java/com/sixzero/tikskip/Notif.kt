package com.sixzero.tikskip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Notif {
    const val CHANNEL = "tikskip_status"
    const val ID = 1
    const val ACT_TOGGLE_AUTO = "com.sixzero.tikskip.TOGGLE_AUTO"
    const val ACT_TOGGLE_SKIP = "com.sixzero.tikskip.TOGGLE_SKIP"

    fun show(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "TikSkip status", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); description = "Quick toggles while TikTok is open"
        })
        Prefs.load(ctx)
        fun pi(action: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, ToggleReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val skipLabel = if (Prefs.enabled) "SKIPPER: ON" else "SKIPPER: OFF"
        val autoLabel = if (Prefs.autoScroll) "AUTO-SCROLL: ON (${Prefs.autoScrollSec}s)" else "AUTO-SCROLL: OFF"
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("TikSkip")
            .setContentText("$skipLabel  •  $autoLabel")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (Prefs.enabled) "Skipper off" else "Skipper on", pi(ACT_TOGGLE_SKIP, 1)).build())
            .addAction(Notification.Action.Builder(null, if (Prefs.autoScroll) "Auto-scroll off" else "Auto-scroll on", pi(ACT_TOGGLE_AUTO, 2)).build())
            .build()
        nm.notify(ID, n)
    }

    fun hide(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}

class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.load(ctx)
        when (intent.action) {
            Notif.ACT_TOGGLE_AUTO -> Prefs.autoScroll = !Prefs.autoScroll
            Notif.ACT_TOGGLE_SKIP -> Prefs.enabled = !Prefs.enabled
        }
        Prefs.save(ctx)
        Notif.show(ctx)
    }
}
