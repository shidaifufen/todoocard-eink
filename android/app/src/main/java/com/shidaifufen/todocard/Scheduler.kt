package com.shidaifufen.todocard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.WorkManager

/** 保留旧调用名，实际用下一次任务的系统闹钟，不再每 15 分钟轮询。 */
object SchedulerWorker {
    const val ACTION = "com.shidaifufen.todocard.SCHEDULE_DUE"
    const val OPEN_PLANS = "com.shidaifufen.todocard.OPEN_PLANS"
    private const val RETRY = "com.shidaifufen.todocard.SCHEDULE_RETRY"
    private const val STATE = "schedule_alarm"
    private const val ARMED = "armed_at"
    private const val EXACT = "exact"
    internal const val TAG = "TodooSchedule"
    private var armedInProcess = false
    fun exactAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized fun schedule(ctx: Context, restore: Boolean = false) {
        PlanStore.init(ctx)
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val state = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val armed = state.getLong(ARMED, 0)
        val exact = exactAllowed(ctx)
        val fixed = PlanStore.fixed(ctx)
        val intervals = PlanStore.intervals(ctx)
        // Returning to the app must not cancel an alarm whose broadcast is about to arrive.
        if (!restore && armed in (now - 120_000L)..now &&
            SchedulePlanner.slots(fixed, intervals, armed, armed).isNotEmpty()) return
        val next = SchedulePlanner.slots(fixed, intervals, now + 1, now + 8 * 86_400_000L).firstOrNull()
        val at = next?.at ?: 0L
        // The system can clear alarms on package replacement while preferences survive.
        if (!restore && armedInProcess && at > 0 && at == armed && exact == state.getBoolean(EXACT, false)) return
        val armedExact = at > 0 && arm(ctx, alarm, at, pending(ctx, ACTION, at))
        if (armed > 0 && armed != at) alarm.cancel(pending(ctx, ACTION, armed))
        // Cancel the pre-1.8.5 PendingIntent, whose identity had no time URI.
        PendingIntent.getBroadcast(ctx, 8301, Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let(alarm::cancel)
        state.edit().putLong(ARMED, at).putBoolean(EXACT, armedExact).apply()
        armedInProcess = at > 0
    }

    private fun pending(ctx: Context, action: String, scheduledAt: Long) =
        PendingIntent.getBroadcast(ctx, 8301,
            Intent(ctx, ScheduleReceiver::class.java).setAction(action)
                .setData(Uri.parse("todocard-plan://alarm/$scheduledAt"))
                .putExtra("scheduledAt", scheduledAt), PendingIntent.FLAG_IMMUTABLE)

    private fun arm(ctx: Context, alarm: AlarmManager, triggerAt: Long, operation: PendingIntent): Boolean {
        if (exactAllowed(ctx)) {
            val show = PendingIntent.getActivity(ctx, 8303,
                Intent(ctx, MainActivity::class.java).setAction(OPEN_PLANS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            try {
                alarm.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
                Log.i(TAG, "Armed exact card plan at $triggerAt")
                return true
            } catch (_: SecurityException) {
                // Exact-alarm access can be revoked between the check and registration.
            }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        Log.i(TAG, "Armed inexact card plan at $triggerAt")
        return false
    }

    internal fun received(ctx: Context, action: String?, at: Long, now: Long) {
        if (action == ACTION) {
            val state = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
            if (state.getLong(ARMED, 0) == at) state.edit().remove(ARMED).apply()
        }
        Log.i(TAG, "Card plan alarm received at $now for $at")
    }

    internal fun clearLegacyWork(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("todocard-scheduler")
    }

    internal fun retry(ctx: Context, scheduledAt: Long) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val retry = pending(ctx, RETRY, scheduledAt)
        val at = System.currentTimeMillis() + 60_000L
        if (exactAllowed(ctx)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, retry)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, retry)
    }

    internal fun isDispatch(action: String?) = action == ACTION || action == RETRY
    internal fun canRetry(action: String?) = action == ACTION
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PlanStore.init(ctx)
        if (SchedulerWorker.isDispatch(intent.action)) {
            val now = System.currentTimeMillis()
            val preferences = ctx.getSharedPreferences("schedule_runs", Context.MODE_PRIVATE)
            val scheduledAt = intent.getLongExtra("scheduledAt", now)
            SchedulerWorker.received(ctx, intent.action, scheduledAt, now)
            val due = SchedulePlanner.slots(PlanStore.fixed(ctx), PlanStore.intervals(ctx),
                scheduledAt, scheduledAt)
            due.filter { !preferences.contains(it.key) }.forEach { slot ->
                val kind = slot.templates.random()
                try {
                    ctx.startForegroundService(Intent(ctx, PushService::class.java)
                        .putExtra("kind", kind)
                        .putExtra("scheduledAt", slot.at))
                    preferences.edit().putLong(slot.key, now).commit()
                    Log.i(SchedulerWorker.TAG, "Requested background push for ${slot.at}")
                } catch (e: Exception) {
                    Log.w(SchedulerWorker.TAG, "Could not start background push for ${slot.at}", e)
                    if (Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException &&
                        SchedulerWorker.canRetry(intent.action) && SchedulerWorker.exactAllowed(ctx) &&
                        runCatching { SchedulerWorker.retry(ctx, slot.at) }.isSuccess) return@forEach
                    preferences.edit().putLong(slot.key, now).commit()
                    PlanStore.addHistory(ctx, PlanStore.Hist(now, kind, false, 0,
                        "系统未能在后台启动推送 · ${e.javaClass.simpleName}"))
                }
            }
            val cleanup = preferences.edit()
            preferences.all.filterValues { (it as? Long ?: 0) < now - 7 * 86_400_000L }
                .keys.forEach { cleanup.remove(it) }
            cleanup.apply()
        }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) SchedulerWorker.clearLegacyWork(ctx)
        SchedulerWorker.schedule(ctx, restore = !SchedulerWorker.isDispatch(intent.action))
    }
}
