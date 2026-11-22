package com.shidaifufen.todocard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * TodooCard BLE 客户端（协议见桌面版 sender.py / Swift 工具）
 *
 * 服务 0xFEF0/0xFDF0，控制点 0xFEF1/0xFDF1，数据点 0xFEF2/0xFDF2
 * 命令：01+块大小 / 02+三字节长度 / 03 开始 / 数据块 [idx LE32]+chunk
 * 回执：05 00 <idx LE32> / 05 08
 */
class BleClient(private val ctx: Context) {

    companion object {
        val SVC_PRIMARY: UUID = UUID.fromString("0000fef0-0000-1000-8000-00805f9b34fb")
        val SVC_FALLBACK: UUID = UUID.fromString("0000fdf0-0000-1000-8000-00805f9b34fb")
        val CTRL_PRIMARY: UUID = UUID.fromString("0000fef1-0000-1000-8000-00805f9b34fb")
        val CTRL_FALLBACK: UUID = UUID.fromString("0000fdf1-0000-1000-8000-00805f9b34fb")
        val DATA_PRIMARY: UUID = UUID.fromString("0000fef2-0000-1000-8000-00805f9b34fb")
        val DATA_FALLBACK: UUID = UUID.fromString("0000fdf2-0000-1000-8000-00805f9b34fb")
    }

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter
    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private var data: BluetoothGattCharacteristic? = null
    private val acks = Channel<Int>(Channel.UNLIMITED)
    private val writeOk = Channel<Unit>(Channel.CONFLATED)
    private var ready = CompletableDeferred<Boolean>()
    private var blockSize = 0
    private var lastAckAt = 0L

    /** 扫描 timeout 毫秒，返回**全部**设备（按 RSSI 排序），由界面让用户挑。 */
    @SuppressLint("MissingPermission")
    suspend fun scanAll(timeoutMs: Long = 12_000, onLog: (String) -> Unit = {}): List<ScanResult> {
        val scanner = adapter?.bluetoothLeScanner ?: run { onLog("蓝牙未开启"); return emptyList() }
        val found = java.util.concurrent.ConcurrentHashMap<String, ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = result
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { found[it.device.address] = it }
            }
            override fun onScanFailed(errorCode: Int) {
                onLog("扫描失败 code=$errorCode")
            }
        }
        onLog("开始扫描 ${timeoutMs / 1000}s…")
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build(), cb)
        kotlinx.coroutines.delay(timeoutMs)
        scanner.stopScan(cb)
        onLog("扫到 ${found.size} 台设备")
        return found.values.sortedByDescending { it.rssi }
    }

    fun isLikelyCard(r: ScanResult): Boolean {
        val name = (r.device.name ?: r.scanRecord?.deviceName ?: "").lowercase()
        if (name.contains("todoo") || name.contains("potato") || name.contains("t3")) return true
        val manu = r.scanRecord?.manufacturerSpecificData ?: return false
        for (i in 0 until manu.size()) if (manu.keyAt(i) == 0x5053) return true
        return false
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(result: ScanResult): Boolean {
        repeat(2) { attempt ->
            if (tryConnect(result)) return true
            if (attempt == 1) return false
            kotlinx.coroutines.delay(1200)
            close()
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryConnect(result: ScanResult): Boolean {
        ready = CompletableDeferred()
        gatt = result.device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (!ready.isCompleted) ready.complete(false)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SVC_PRIMARY) ?: g.getService(SVC_FALLBACK)
                ctrl = svc?.getCharacteristic(CTRL_PRIMARY) ?: svc?.getCharacteristic(CTRL_FALLBACK)
                data = svc?.getCharacteristic(DATA_PRIMARY) ?: svc?.getCharacteristic(DATA_FALLBACK)
                val notify = ctrl ?: return
                g.setCharacteristicNotification(notify, true)
                val desc = notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                desc?.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                desc?.let { g.writeDescriptor(it) }
                if (desc == null && !ready.isCompleted) ready.complete(ctrl != null && data != null)
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                writeOk.trySend(Unit)
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
                if (!ready.isCompleted) ready.complete(status == android.bluetooth.BluetoothGatt.GATT_SUCCESS && ctrl != null && data != null)
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                if (value.size >= 3 && value[0].toInt() == 0x01) {
                    blockSize = maxOf(0, (value[1].toInt() and 0xFF) - 4)
                    acks.trySend(-2)
                } else if (value.size >= 5 && value[0].toInt() == 0x05) {
                    if (value[1].toInt() == 0x08) acks.trySend(-1) else {
                        val idx = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8) or
                                  ((value[4].toInt() and 0xFF) shl 16)
                        acks.trySend(idx)
                    }
                }
            }
        })
        return try { withTimeout(25_000) { ready.await() } } catch (e: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private fun write(ch: BluetoothGattCharacteristic, bytes: ByteArray, noResponse: Boolean = false): Boolean {
        val g = gatt ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                       else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch, bytes, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                           else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = bytes
            g.writeCharacteristic(ch)
        }
    }

    /** 发送 legacy 包（未压缩，218 KB；握手流程与桌面版 sender.py 一致） */
    @SuppressLint("MissingPermission")
    suspend fun send(legacy: ByteArray, onLog: (String) -> Unit = {}, onProgress: (Int) -> Unit = {}) {
        val c = ctrl ?: error("控制点未就绪")
        val d = data ?: error("数据点未就绪")
        val g = gatt ?: error("未连接")

        // 1) 请求 MTU（能大一点就大一点，Android 会自动协商）
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { g.requestMtu(517) }
        }
        delay(300)
        onLog("MTU 已请求（实际 ${g.device.name ?: ""}）")
        Log.i("BleClient", "mtu requested; blockSize=$blockSize")

        // 2) 问块大小：设备回 [0x01, blockSize, 0x00]
        blockSize = 0
        write(c, byteArrayOf(0x01))
        val ok = withTimeoutOrNull(5000) {
            while (blockSize <= 0) { acks.receive() }
            true
        } ?: false
        if (!ok) throw IllegalStateException("等不到 block size 响应（通知没订阅成功？）")
        val bs = blockSize
        onLog("block payload = $bs")
        Log.i("BleClient", "cmd01 ack: blockSize=$blockSize payload=$bs length=${legacy.size}")

        // 3) 补齐到块大小整数倍，报长度，再开始
        var body = legacy
        if (body.size % bs != 0) {
            body = body + ByteArray(bs - body.size % bs)
        }
        val nBlocks = body.size / bs
        var sent = 0
        write(c, byteArrayOf(0x02, (body.size and 0xFF).toByte(),
            (body.size shr 8 and 0xFF).toByte(), (body.size shr 16 and 0xFF).toByte()))
        delay(200)
        write(c, byteArrayOf(0x03))
        onLog("共 $nBlocks 块")
        Log.i("BleClient", "cmd02 len=${body.size} cmd03 start nBlocks=$nBlocks")

        // 4) 数据阶段：窗口流控（与 sender.py 一致：设备回执是"进度"，不是逐块确认）
        var acked = -1
        var lastAckAt = System.currentTimeMillis()
        val window = 64   // 设备每 32 块才回一次 checkpoint，窗口要放宽
        while (sent < nBlocks) {
            // 领先设备太多就等回执
            while (sent - acked > window) {
                val v = withTimeoutOrNull(200) { acks.receive() }
                if (v != null && v >= 0) { acked = v; lastAckAt = System.currentTimeMillis() }
                if (System.currentTimeMillis() - lastAckAt > 30000) {
                    onLog("回执停滞：已发 $sent/$nBlocks，设备回到第 ${acked + 1} 块")
                    Log.w("BleClient", "stall sent=$sent acked=$acked")
                    postWait(c, false, onLog)
                    return
                }
            }
            val pkt = ByteArray(4 + bs)
            pkt[0] = (sent and 0xFF).toByte()
            pkt[1] = (sent shr 8 and 0xFF).toByte()
            pkt[2] = (sent shr 16 and 0xFF).toByte()
            pkt[3] = (sent shr 24 and 0xFF).toByte()
            body.copyInto(pkt, 4, sent * bs, sent * bs + bs)
            // 数据块用"不带响应"写 + 微延时，和桌面版 sender.py 的节奏一致
            val wrote = write(d, pkt, noResponse = true)
            if (!wrote) {
                // 有些机型不支持无响应写，退回带响应
                write(d, pkt)
                withTimeoutOrNull(500) { writeOk.receive() }
            } else {
                delay(4)
            }
            sent++
            onProgress(sent * 100 / nBlocks)
            if (sent % 8 == 0) Log.i("BleClient", "sent=$sent/$nBlocks acked=$acked")
            // 顺手把已到的回执读掉
            while (true) {
                val v = try { acks.tryReceive().getOrNull() } catch (e: Exception) { null }
                if (v == null) break
                if (v >= 0) { acked = v; lastAckAt = System.currentTimeMillis() }
            }
        }
        Log.i("BleClient", "all blocks written sent=$sent acked=$acked, waiting refresh")

        // 5) 等收尾确认 05 08
        val refreshed = postWait(c, true, onLog)
        Log.i("BleClient", "send done nBlocks=$nBlocks sent=$sent lastAck=$acked refreshed=$refreshed")
    }


    /** 数据写完后等显影确认 */
    private suspend fun postWait(c: BluetoothGattCharacteristic, refreshed: Boolean, onLog: (String) -> Unit): Boolean {
        val got = withTimeoutOrNull(60_000) {
            while (true) {
                val v = acks.receive()
                if (v < 0) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        onLog(if (got) "已完成并收到显影确认" else "数据写完，但没等到显影确认")
        Log.i("BleClient", "postWait refreshed=$got")
        return got
    }

    @SuppressLint("MissingPermission")
    fun close() { runCatching { gatt?.close() }; gatt = null }
}
