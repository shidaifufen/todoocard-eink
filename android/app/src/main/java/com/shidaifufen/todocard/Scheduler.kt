package com.shidaifufen.todocard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.WorkManager

/** 保留旧调用名，实际用下一次任务的系统闹钟，不再每 15 分钟轮询。 */
object SchedulerWorker {
    const val ACTION = "com.shidaifufen.todocard.SCHEDULE_DUE"
    fun exactAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized fun schedule(ctx: Context) {
        PlanStore.init(ctx)
        WorkManager.getInstance(ctx).cancelUniqueWork("todocard-scheduler")
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val next = SchedulePlanner.slots(PlanStore.fixed(ctx), PlanStore.intervals(ctx),
            now + 500, now + 8 * 86_400_000L).firstOrNull()
        val pending = PendingIntent.getBroadcast(ctx, 8301,
            Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION)
                .putExtra("scheduledAt", next?.at ?: 0L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (next == null) return
        if (exactAllowed(ctx)) {
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending) }
            catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending) }
        } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PlanStore.init(ctx)
        if (intent.action == SchedulerWorker.ACTION) {
            val now = System.currentTimeMillis()
            val preferences = ctx.getSharedPreferences("schedule_runs", Context.MODE_PRIVATE)
            val scheduledAt = intent.getLongExtra("scheduledAt", now)
            val due = SchedulePlanner.slots(PlanStore.fixed(ctx), PlanStore.intervals(ctx),
                scheduledAt, scheduledAt)
            due.filter { !preferences.contains(it.key) }.forEach { slot ->
                val kind = slot.templates.random()
                preferences.edit().putLong(slot.key, now).commit()
                try {
                    ctx.startForegroundService(Intent(ctx, PushService::class.java)
                        .putExtra("kind", kind).putExtra("scheduled", true)
                        .putExtra("scheduledAt", slot.at))
                } catch (e: Exception) {
                    PlanStore.addHistory(ctx, PlanStore.Hist(now, kind, false, 0,
                        "计划未能启动：请开启精确定时与蓝牙权限。${e.message.orEmpty().take(90)}"))
                }
            }
            val cleanup = preferences.edit()
            preferences.all.filterValues { (it as? Long ?: 0) < now - 7 * 86_400_000L }
                .keys.forEach { cleanup.remove(it) }
            cleanup.apply()
        }
        SchedulerWorker.schedule(ctx)
    }
}
