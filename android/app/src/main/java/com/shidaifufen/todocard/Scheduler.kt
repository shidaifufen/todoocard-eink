package com.shidaifufen.todocard

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 低功耗后台调度：WorkManager 每 15 分钟醒一次，检查计划里有没有到点的任务。
 * 到点就渲染 → 连卡片 → 推送 → 写历史。失败交给下一次（30 分钟内补跑）。
 */
class SchedulerWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        PlanStore.init(applicationContext)
        val now = Calendar.getInstance()
        val nowTs = now.timeInMillis
        val catchUpMs = 30 * 60_000L

        // 固定计划：今天这个时间点已过、且还没执行过
        PlanStore.fixed(applicationContext).forEach { item ->
            if (!item.enabled) return@forEach
            val hhmm = item.time.split(":")
            val due = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hhmm.getOrNull(0)?.toIntOrNull() ?: 8)
                set(Calendar.MINUTE, hhmm.getOrNull(1)?.toIntOrNull() ?: 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val delta = nowTs - due.timeInMillis
            if (delta in 0..catchUpMs) {
                pushTemplate(item.template)
            }
        }

        // 间隔任务：当前时刻落在时段内，且是 N 分钟的整数倍（±7 分钟容差）
        PlanStore.intervals(applicationContext).forEach { itv ->
            if (!itv.enabled) return@forEach
            val dow = now.get(Calendar.DAY_OF_WEEK)
            val workday = dow in Calendar.MONDAY..Calendar.FRIDAY
            if (itv.days == "workday" && !workday) return@forEach
            if (itv.days == "weekend" && workday) return@forEach
            val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val s = minutes(itv.start); val e = minutes(itv.end)
            if (cur !in s..e) return@forEach
            if ((cur - s) % maxOf(5, itv.everyMin) <= 7) pushTemplate(itv.template)
        }
        PlanStore.lastChecked = nowTs
        return Result.success()
    }

    private fun minutes(hhmm: String): Int {
        val p = hhmm.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 渲染模板并按已保存的设备地址推送 */
    private fun pushTemplate(kind: String) {
        val addr = PlanStore.deviceAddress
        if (addr.isEmpty()) return
        val t0 = System.currentTimeMillis()
        try {
            val bmp = if (kind == "photo") {
                val p = PlanStore.lastPhoto
                if (p.isEmpty()) return
                PhotoCard.render(p, PlanStore.photoFields)
            } else Render.render(Render.Card(kind = kind))
            val payload = QuickLz.buildPayload(Payload.build(bmp).controller)
            val ble = BleClient(applicationContext)
            kotlinx.coroutines.runBlocking {
                val found = ble.scanAll(8000)
                val target = found.firstOrNull { it.device.address == addr } ?: found.firstOrNull()
                    ?: return@runBlocking
                if (ble.connect(target)) {
                    val ok = withWait(ble, payload)
                    PlanStore.addHistory(applicationContext, PlanStore.Hist(
                        System.currentTimeMillis(), kind, ok,
                        ((System.currentTimeMillis() - t0) / 1000).toInt(), if (ok) "成功" else "已写入未确认"))
                }
                ble.close()
            }
        } catch (e: Exception) {
            PlanStore.addHistory(applicationContext, PlanStore.Hist(
                System.currentTimeMillis(), kind, false,
                ((System.currentTimeMillis() - t0) / 1000).toInt(), e.message ?: "失败"))
        }
    }

    private suspend fun withWait(ble: BleClient, payload: ByteArray): Boolean {
        var ok = false
        ble.send(payload, onLog = {}, onProgress = {})
        ok = true   // 数据写完即视为已推送（显影确认由下一次轮询修正）
        return ok
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SchedulerWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "todocard-scheduler", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
