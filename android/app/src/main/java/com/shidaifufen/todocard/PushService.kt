package com.shidaifufen.todocard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** 常驻前台服务：Android 8+ 后台推送必须挂在通知上，否则会被系统冻结。 */
class PushService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ID, "自动推送", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = Notification.Builder(this, CH_ID)
            .setContentTitle("土豆片")
            .setContentText("自动推送运行中")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val CH_ID = "push" }
}
