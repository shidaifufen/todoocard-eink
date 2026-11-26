package com.shidaifufen.todocard

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 前台服务持有推送任务，返回系统相册后仍继续工作。BLE 调用顺序及协议保持不变。 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var lastNotificationAt = 0L
    private val queue = java.util.ArrayDeque<Intent>()

    override fun onCreate() {
        super.onCreate()
        PlanStore.init(this)
        PushStatus.ensureChannel(this)
        startForeground(PushStatus.ID, PushStatus.notification(this, "准备推送…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        queue.add(intent)
        if (task?.isActive != true) task = scope.launch {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                push(next.getStringExtra("bitmapPath"), next.getStringExtra("kind") ?: "photo",
                    next.getLongExtra("scheduledAt", 0), next.getBooleanExtra("replay", false))
                // 成功或失败状态短暂展示，然后与通知一起消失；新任务可直接接续。
                repeat(10) { if (queue.isEmpty()) delay(500) }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            PushStatus.clear(this@PushService)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun report(message: String, progress: Int = state.value.progress, force: Boolean = false) {
        state.value = State(true, message, progress)
        val now = System.currentTimeMillis()
        if (force || now - lastNotificationAt > 700) {
            lastNotificationAt = now
            PushStatus.update(this, message, progress.takeIf { it in 1..100 })
        }
    }

    private suspend fun push(path: String?, kind: String, scheduledAt: Long, replay: Boolean) {
        val started = System.currentTimeMillis()
        var message = "准备推送…"
        var ok = false
        var client: BleClient? = null
        var snapshot = ""
        var notice = ""
        val wake = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "todocard:push")
        state.value = State(true, message)
        try {
            wake.acquire(10 * 60_000L)
            report("正在准备卡片…", 0, true)
            val source = if (path == null) CardSources.scheduledBitmap(this, kind) { notice = it }
                else BitmapFactory.decodeFile(path) ?: error("无法读取卡片图片")
            val payload = withContext(Dispatchers.Default) {
                try {
                    snapshot = withContext(Dispatchers.IO) {
                        runCatching { HistoryCards.save(this@PushService, source) }.getOrElse {
                            notice = listOf(notice, "未能保存历史图片").filter(String::isNotBlank).joinToString(" · ")
                            ""
                        }
                    }
                    QuickLz.buildPayload(Payload.build(source).controller)
                }
                finally { source.recycle() }
            }
            val saved = PlanStore.deviceAddress
            val ble = BleClient(this).also { client = it }
            report("查找中 · 请唤醒卡片", 0, true)
            var target: android.bluetooth.le.ScanResult? = null
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline && target == null) {
                val list = withContext(Dispatchers.IO) {
                    if (scheduledAt > 0) ble.scanForPush(saved, 10_000)
                    else ble.scanAll(10_000)
                }
                target = DeviceDiscovery.target(list, saved)
                if (target == null) {
                    val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    report("等待卡片广播，剩余 $left 秒")
                }
            }
            val selected = target ?: error("未找到卡片，请唤醒后重试")
            PlanStore.deviceAddress = selected.device.address
            PlanStore.deviceName = selected.device.name ?: ""
            report("连接中…", force = true)
            if (!withContext(Dispatchers.IO) { ble.connect(selected) })
                error("连接失败：卡片可能在休眠或被占用")
            report("已连接 · 准备推送", 0, true)
            delay(700)
            report("推送中（${payload.size / 1024} KB）", force = true)
            var lastLog = ""
            withContext(Dispatchers.IO) {
                ble.send(payload,
                    onLog = { lastLog = it },
                    onProgress = { report(if (it == 100) "显影中 · 等待设备确认" else "推送中 · $it%", it) })
            }
            ok = lastLog == "已完成并收到显影确认"
            message = if (ok) "推送成功 · 已收到显影确认" else lastLog.ifBlank { "数据已发送，未收到显影确认" }
        } catch (error: Exception) {
            message = "失败：${error.message ?: "推送中断"}"
        } finally {
            client?.close()
            path?.let { File(it).delete() }
            if (wake.isHeld) wake.release()
            val result = listOf(if (replay) "原图重推" else "", message, notice).filter(String::isNotBlank).joinToString(" · ")
            val detail = if (scheduledAt > 0) "计划 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(scheduledAt))} · $result" else result
            PlanStore.addHistory(this, PlanStore.Hist(System.currentTimeMillis(), kind, ok,
                ((System.currentTimeMillis() - started) / 1000).toInt(), detail, snapshot, replay))
            state.value = State(false, message, if (ok) 100 else state.value.progress)
            PushStatus.update(this, message, finished = true)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    data class State(val running: Boolean = false, val message: String = "", val progress: Int = 0)
    companion object {
        private val state = MutableStateFlow(State())
        val status = state.asStateFlow()
    }
}
