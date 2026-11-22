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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
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
    private var ready = CompletableDeferred<Boolean>()

    @SuppressLint("MissingPermission")
    suspend fun scan(timeoutMs: Long = 12_000): ScanResult? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<ScanResult?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                val manu = result.scanRecord?.manufacturerSpecificData
                val hasVendor = (0 until (manu?.size() ?: 0)).any { manu!!.get(it) != null }
                if (name.contains("Todoo", true) || name.contains("T3", true) || hasVendor) {
                    if (!found.isCompleted) found.complete(result)
                }
            }
            override fun onScanFailed(errorCode: Int) { if (!found.isCompleted) found.complete(null) }
        }
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        return try {
            withTimeout(timeoutMs) { found.await() }
        } catch (e: Exception) { null } finally { scanner.stopScan(cb) }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(result: ScanResult): Boolean {
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
                if (!ready.isCompleted) ready.complete(ctrl != null && data != null)
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                if (value.size >= 5 && value[0].toInt() == 0x05) {
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
    private fun write(ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = bytes
        return gatt?.writeCharacteristic(ch) ?: false
    }

    /** 发送 legacy 包（不压缩，218 KB 左右，慢但实现简单） */
    @SuppressLint("MissingPermission")
    suspend fun send(legacy: ByteArray, onProgress: (Int) -> Unit = {}) {
        val c = ctrl ?: error("控制点未就绪")
        val d = data ?: error("数据点未就绪")
        runCatching { write(c, byteArrayOf(0x01, 0x00, 0x00)) }
        runCatching { write(c, byteArrayOf(0x03)) }

        val chunk = 512
        var idx = 0
        var off = 0
        while (off < legacy.size) {
            val end = minOf(off + chunk, legacy.size)
            val pkt = ByteArray(4 + (end - off))
            pkt[0] = (idx and 0xFF).toByte()
            pkt[1] = (idx shr 8 and 0xFF).toByte()
            pkt[2] = (idx shr 16 and 0xFF).toByte()
            pkt[3] = (idx shr 24 and 0xFF).toByte()
            legacy.copyInto(pkt, 4, off, end)
            write(d, pkt)
            idx++; off = end
            onProgress((off * 100 / legacy.size))
            // 每 20 个包等一次回执（窗口流控）
            if (idx % 20 == 0) {
                runCatching { withTimeout(4000) { acks.receive() } }
            }
        }
        runCatching { withTimeout(15_000) { acks.receive() } }
    }

    @SuppressLint("MissingPermission")
    fun close() { runCatching { gatt?.close() }; gatt = null }
}
