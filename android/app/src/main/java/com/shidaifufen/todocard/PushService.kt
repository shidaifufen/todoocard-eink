package com.shidaifufen.todocard

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
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
        PhotoCard.initialize(this)
        PushStatus.ensureChannel(this)
        startForeground(PushStatus.ID, PushStatus.notification(this, "准备推送…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ShuffleWidget.ACTION_SHUFFLE &&
            (state.value.running || queue.any { it.action == ShuffleWidget.ACTION_SHUFFLE })) return START_NOT_STICKY
        queue.add(intent)
        if (task?.isActive != true) task = scope.launch {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                push(next.getStringExtra("bitmapPath"), next.getStringExtra("kind") ?: "photo",
                    next.getLongExtra("scheduledAt", 0), next.getBooleanExtra("replay", false),
                    widget = next.action == ShuffleWidget.ACTION_SHUFFLE)
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

    private suspend fun push(path: String?, requestedKind: String, scheduledAt: Long, replay: Boolean, widget: Boolean) {
        var kind = requestedKind
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "Push started: kind=$kind, scheduledAt=$scheduledAt, widget=$widget")
        var stage = "准备卡片"
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
            val source = when {
                widget -> {
                    withTimeoutOrNull(90_000L) {
                        val candidates = WidgetShuffle.candidates(this@PushService)
                        var failure: Exception? = null
                        var bitmap: android.graphics.Bitmap? = null
                        for (category in candidates) {
                            kind = category
                            report("正在抽取 · ${templateName(category)}", 0, true)
                            try {
                                bitmap = CardSources.scheduledBitmap(this@PushService, category, requireFresh = true)
                                break
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = error
                            }
                        }
                        bitmap ?: throw (failure ?: IllegalStateException("请先设置小组件的随机范围"))
                    } ?: error("抽取新卡片超时")
                }
                path == null -> {
                    report(if (kind.startsWith("online_")) "正在获取在线卡片…" else "正在准备卡片…", 0, true)
                    CardSources.scheduledBitmap(this, kind) { notice = it }
                }
                else -> {
                    report("正在准备卡片…", 0, true)
                    BitmapFactory.decodeFile(path) ?: error("无法读取卡片图片")
                }
            }
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
            stage = "扫描"
            report("查找中 · 请唤醒卡片", 0, true)
            Log.i(TAG, "Scanning for selected card; paired address available=${saved.isNotBlank()}")
            val selected = withContext(Dispatchers.IO) {
                ble.scanForPush(saved, 120_000, allowUnfiltered = scheduledAt <= 0 && !widget)
                    ?: error("未找到卡片，请唤醒后重试")
            }
            Log.i(TAG, "Found card after ${SystemClock.elapsedRealtime() - started} ms")
            PlanStore.deviceAddress = selected.device.address
            PlanStore.deviceName = selected.device.name ?: ""
            stage = "连接"
            report("连接中…", force = true)
            if (!withContext(Dispatchers.IO) { ble.connect(selected) })
                error("连接失败：卡片可能在休眠或被占用")
            report("已连接 · 准备推送", 0, true)
            delay(700)
            stage = "传图"
            report("推送中（${payload.size / 1024} KB）", force = true)
            var lastLog = ""
            withContext(Dispatchers.IO) {
                ble.send(payload,
                    onLog = { lastLog = it },
                    onProgress = { report(if (it == 100) "显影中 · 等待设备确认" else "推送中 · $it%", it) })
            }
            ok = lastLog == "已完成并收到显影确认"
            if (ok && widget) WidgetShuffle.sent(this, kind)
            message = if (ok) "推送成功 · 已收到显影确认" else lastLog.ifBlank { "数据已发送，未收到显影确认" }
        } catch (cancelled: CancellationException) {
            message = "推送已中断"
            throw cancelled
        } catch (error: Exception) {
            message = "失败（$stage）：${error.message ?: "推送中断"}"
            Log.w(TAG, "Push failed: kind=$kind, stage=$stage", error)
        } finally {
            client?.close()
            path?.let { File(it).delete() }
            if (wake.isHeld) wake.release()
            val result = listOf(if (replay) "原图重推" else if (widget) "桌面小组件" else "", message, notice)
                .filter(String::isNotBlank).joinToString(" · ")
            val detail = if (scheduledAt > 0) "计划 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(scheduledAt))} · $result" else result
            Log.i(TAG, "Push finished: kind=$kind, confirmed=$ok")
            PlanStore.addHistory(this, PlanStore.Hist(System.currentTimeMillis(), kind, ok,
                ((SystemClock.elapsedRealtime() - started) / 1000).toInt(), detail, snapshot, replay))
            state.value = State(false, message, if (ok) 100 else state.value.progress)
            PushStatus.update(this, message, finished = true)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    data class State(val running: Boolean = false, val message: String = "", val progress: Int = 0)
    companion object {
        private const val TAG = "TodooPush"
        private val state = MutableStateFlow(State())
        val status = state.asStateFlow()
    }
}
