package com.shidaifufen.todocard

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("选一张照片开始") }
    var progress by remember { mutableStateOf(0) }
    var device by remember { mutableStateOf<android.bluetooth.le.ScanResult?>(null) }
    var found by remember { mutableStateOf<List<android.bluetooth.le.ScanResult>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                bitmap = BitmapFactory.decodeStream(input)
            }
            status = "已选择照片"
        }
    }

    val scanNow: () -> Unit = {
        scope.launch {
            status = "扫描中…（长按卡片背面按钮唤醒，10 秒）"
            found = emptyList()
            val ble = BleClient(ctx)
            val list = withContext(Dispatchers.IO) { ble.scanAll(10_000) { line -> status = line } }
            found = list
            val card = list.firstOrNull { ble.isLikelyCard(it) }
            if (card != null) { device = card; status = "自动选中：${card.device.name ?: card.device.address}" }
            else status = "扫到 ${list.size} 台，卡片未自动识别——在下面列表里手动选"
        }
    }
    var pendingScan by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.all { it }
        if (!ok) status = "蓝牙权限被拒绝，无法扫描"
        else if (pendingScan) { pendingScan = false; scanNow() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("土豆片 · 安卓版 v0.1", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)

        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
        }

        Button(onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("从相册选照片") }

        Button(onClick = {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            pendingScan = true
            permLauncher.launch(perms)
        }) { Text("扫描卡片") }

        if (found.isNotEmpty()) {
            Text("扫到的设备：", style = MaterialTheme.typography.bodySmall)
            found.forEach { r ->
                val name = r.device.name ?: r.scanRecord?.deviceName ?: "(无名)"
                val tag = if (device?.device?.address == r.device.address) "✔ " else ""
                OutlinedButton(
                    onClick = { device = r },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("$tag$name  ${r.device.address}  RSSI ${r.rssi}")
                }
            }
        }

        Button(enabled = bitmap != null && device != null, onClick = {
            val bmp = bitmap ?: return@Button
            val d = device ?: return@Button
            scope.launch {
                status = "连接中…"
                val ble = BleClient(ctx)
                if (!withContext(Dispatchers.IO) { ble.connect(d) }) {
                    status = "连接失败：先长按卡片背面按钮唤醒"; return@launch
                }
                status = "打包中…"
                val payload = withContext(Dispatchers.Default) {
                    val built = Payload.build(bmp)
                    QuickLz.buildPayload(built.controller)
                }
                status = "传输中（压缩后 ${payload.size / 1024} KB）…"
                val logs = StringBuilder("payload=${payload.size} bytes\n")
                withContext(Dispatchers.IO) {
                    ble.send(payload,
                        onLog = { line -> logs.append(line).append("\n"); status = line },
                        onProgress = { p -> progress = p })
                }
                status = "完成：\n" + logs.toString().trim()
                ble.close()
            }
        }) { Text("推送到卡片") }

        if (progress > 0) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$progress%")
        }

        Text(
            "说明：v0.1 只做「照片直推」与协议验证；模板渲染、定时后台推送、相册批量在后续版本补齐。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
