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
        return promote(ctx, builder.build(), null, text)
    }

    fun update(ctx: Context, text: String, progress: Int? = null, finished: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val notification = builder(ctx, text, progress, finished)
        ctx.getSystemService(NotificationManager::class.java).notify(ID,
            promote(ctx, notification.build(), if (finished && text.contains("成功")) 100 else progress, text))
    }

    fun clear(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID)

    // Android 16 的公开 API：保留 SDK 35 构建，旧系统直接使用普通进度通知。
    private fun promote(ctx: Context, notification: android.app.Notification,
                        progress: Int?, text: String): android.app.Notification {
        if (Build.VERSION.SDK_INT < 36) return notification
        return runCatching {
            val builder = android.app.Notification.Builder.recoverBuilder(ctx, notification)
            val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
            val style = styleClass.getConstructor().newInstance()
            styleClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType)
                .invoke(style, progress == null)
            if (progress != null) styleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                .invoke(style, progress.coerceIn(0, 100))
            builder.setStyle(style as android.app.Notification.Style)
            builder.setOngoing(true)
            builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
            builder.javaClass.getMethod("setShortCriticalText", String::class.java)
                .invoke(builder, criticalText(text, progress))
            builder.build()
        }.getOrDefault(notification)
    }

    internal fun criticalText(text: String, progress: Int?): String = when {
        text.contains("成功") -> "推送成功"
        text.startsWith("失败") -> "推送失败"
        text.contains("查找") || text.contains("广播") -> "查找中"
        text.contains("已连接") -> "已连接"
        text.contains("已找到") -> "已找到卡片"
        text.contains("未找到") || text.contains("扫描失败") -> "未找到卡片"
        text.contains("连接中") -> "连接中"
        text.contains("显影") -> "显影中"
        progress != null -> "$progress%"
        text.contains("准备") -> "准备中"
        else -> "推送中"
    }

    private fun builder(ctx: Context, text: String, progress: Int?, finished: Boolean) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("土豆片 · ${criticalText(text, progress)}")
            .setContentText(text.take(120))
            .setContentIntent(PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(finished)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                setProgress(100, if (finished && text.contains("成功")) 100 else progress ?: 0, progress == null && !finished)
            }
}
