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

    override fun onCreate() {
        super.onCreate()
        PlanStore.init(this)
        PushStatus.ensureChannel(this)
        startForeground(PushStatus.ID, PushStatus.notification(this, "准备推送…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra("bitmapPath")
        if (task?.isActive == true) {
            path?.let { File(it).delete() }
            return START_NOT_STICKY
        }
        if (path == null) { stopSelf(); return START_NOT_STICKY }
        val kind = intent?.getStringExtra("kind") ?: "photo"
        task = scope.launch { push(path, kind) }
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

    private suspend fun push(path: String, kind: String) {
        val started = System.currentTimeMillis()
        var message = "准备推送…"
        var ok = false
        var client: BleClient? = null
        state.value = State(true, message)
        try {
            report("正在准备卡片…", 0, true)
            val payload = withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeFile(path) ?: error("无法读取卡片图片")
                try { QuickLz.buildPayload(Payload.build(bitmap).controller) }
                finally { bitmap.recycle() }
            }
            val saved = PlanStore.deviceAddress
            val ble = BleClient(this).also { client = it }
            report("等待卡片广播，最长 120 秒", 0, true)
            var target: android.bluetooth.le.ScanResult? = null
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline && target == null) {
                val list = withContext(Dispatchers.IO) { ble.scanAll(10_000) }
                target = list.firstOrNull { (it.device.name ?: "").contains("todoo", true) }
                    ?: list.firstOrNull { it.device.address.startsWith("FF:FF", true) }
                    ?: list.firstOrNull { saved.isNotEmpty() && it.device.address == saved }
                    ?: list.firstOrNull { it.device.address.endsWith("39:01", true) }
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
            report("传输中（${payload.size / 1024} KB）…", force = true)
            var lastLog = ""
            withContext(Dispatchers.IO) {
                ble.send(payload,
                    onLog = { lastLog = it; report(it) },
                    onProgress = { report(if (it == 100) "正在等待显影确认…" else "正在传输 $it%", it) })
            }
            ok = lastLog == "已完成并收到显影确认"
            message = if (ok) "已完成并收到显影确认" else lastLog.ifBlank { "数据已发送，未收到显影确认" }
        } catch (error: Exception) {
            message = "失败：${error.message ?: "推送中断"}"
        } finally {
            client?.close()
            File(path).delete()
            PlanStore.addHistory(this, PlanStore.Hist(System.currentTimeMillis(), kind, ok,
                ((System.currentTimeMillis() - started) / 1000).toInt(), message))
            state.value = State(false, message, if (ok) 100 else state.value.progress)
            stopForeground(STOP_FOREGROUND_DETACH)
            PushStatus.update(this, message, finished = true)
            stopSelf()
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
