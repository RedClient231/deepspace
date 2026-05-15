package com.vspace.app.utils

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.vspace.app.App
import com.vspace.app.R
import com.vspace.app.ui.LauncherActivity
import com.vspace.engine.VirtualCore

class DaemonService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        VirtualCore.get().startDaemon()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        VirtualCore.get().stopDaemon()
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
                .setContentTitle("DeepSpace Running")
                .setContentText("Virtual engine is active")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("DeepSpace Running")
                .setContentText("Virtual engine is active")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .build()
        }

        startForeground(1, notification)
    }
}
