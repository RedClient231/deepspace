package com.vspace.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.vspace.engine.VirtualCore

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        VirtualCore.get().init(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepSpace Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Virtual space engine service"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "deepspace_daemon"
        lateinit var instance: App
            private set
    }
}
