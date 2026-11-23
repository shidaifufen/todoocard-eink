package com.shidaifufen.todocard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/** 系统进度通知。支持实时状态的系统可将它提升到状态栏胶囊，展示方式由系统决定。 */
object PushStatus {
    internal const val CHANNEL = "manual_push"
    internal const val ID = 2120

    fun ensureChannel(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "卡片推送进度", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun notification(ctx: Context, text: String): android.app.Notification {
        val builder = builder(ctx, text, null, false)
        builder.setContentIntent(PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return promote(ctx, builder.build(), null)
    }

    fun update(ctx: Context, text: String, progress: Int? = null, finished: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val notification = builder(ctx, text, progress, finished)
        ctx.getSystemService(NotificationManager::class.java).notify(ID,
            if (finished) notification.build() else promote(ctx, notification.build(), progress))
    }

    // Android 16 的公开 API：保留 SDK 35 构建，旧系统直接使用普通进度通知。
    private fun promote(ctx: Context, notification: android.app.Notification,
                        progress: Int?): android.app.Notification {
        if (Build.VERSION.SDK_INT < 36) return notification
        return runCatching {
            val builder = android.app.Notification.Builder.recoverBuilder(ctx, notification)
            builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
            builder.javaClass.getMethod("setShortCriticalText", String::class.java)
                .invoke(builder, progress?.let { "$it%" } ?: "推送中")
            builder.build()
        }.getOrDefault(notification)
    }

    private fun builder(ctx: Context, text: String, progress: Int?, finished: Boolean) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (finished) "土豆片 · 推送结束" else "土豆片 · 正在推送")
            .setContentText(text.take(120))
            .setContentIntent(PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (!finished && progress != null) setProgress(100, progress, false)
            }
}
