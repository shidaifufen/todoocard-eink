package com.shidaifufen.todocard

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/**
 * 周期任务：读取最近一次排队的图片并推送。
 * v0.1 只验证调度链路，真正的模板渲染在后续版本。
 */
class PushWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val queue = File(applicationContext.filesDir, "queue").listFiles() ?: return Result.success()
        val next = queue.minByOrNull { it.lastModified() } ?: return Result.success()
        return try {
            val bmp = android.graphics.BitmapFactory.decodeFile(next.absolutePath) ?: return Result.retry()
            val built = Payload.build(bmp)
            val ble = BleClient(applicationContext)
            kotlinx.coroutines.runBlocking {
                val list = ble.scanAll(8000)
                val found = list.firstOrNull { ble.isLikelyCard(it) } ?: list.firstOrNull()
                    ?: return@runBlocking
                if (ble.connect(found)) ble.send(QuickLz.buildPayload(built.controller))
                ble.close()
            }
            next.delete()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
