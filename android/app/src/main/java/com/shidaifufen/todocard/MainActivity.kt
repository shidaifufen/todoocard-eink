package com.shidaifufen.todocard

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TEMPLATES = listOf(
    "summary" to "每日总结",
    "quote" to "每日一句",
    "todo" to "今日待办",
    "progress" to "时间进度",
    "countdown" to "倒计时",
    "poem" to "每日诗词",
    "custom" to "自定义卡片",
)


    /** 处理系统分享 / 荣耀传送门传来的图片或文字 */
    private fun handleIntent(ctx: android.content.Context, intent: android.content.Intent?,
                             onPhoto: (Bitmap?) -> Unit, onText: (String) -> Unit) {
        if (intent == null) return
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                if (uri != null) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { onPhoto(BitmapFactory.decodeStream(it)) }
                    }
                } else {
                    intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let(onText)
                }
            }
            android.content.Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { onPhoto(BitmapFactory.decodeStream(it)) }
                    }
                }
            }
            android.content.Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.let { onText(it.toString()) }
            }
        }
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf("summary") }
    var card by remember { mutableStateOf(Render.Card()) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("选模板 → 预览 → 推送") }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<android.bluetooth.le.ScanResult>>(emptyList()) }
    var device by remember { mutableStateOf<android.bluetooth.le.ScanResult?>(null) }
    var tab by remember { mutableStateOf(0) }

    fun refreshPreview() {
        scope.launch {
            val bmp = withContext(Dispatchers.Default) {
                if (kind == "photo") photo else Render.render(card.copy(kind = kind))
            }
            preview = bmp
        }
    }
    LaunchedEffect(kind, card, photo) { refreshPreview() }

    // 系统分享 / 荣耀传送门：把图片或文字直接带进来
    LaunchedEffect(Unit) {
        handleIntent(ctx, (ctx as? android.app.Activity)?.intent,
            onPhoto = { bmp -> if (bmp != null) { photo = bmp; kind = "photo"; tab = 0; status = "已从分享导入照片" } },
            onText = { text -> if (text.isNotBlank()) {
                card = card.copy(kind = "custom", customTitle = "分享内容",
                                 customLines = text.lines().filter { it.isNotBlank() })
                kind = "custom"; tab = 0; status = "已从分享导入文字"
            } })
    }


    fun scan() {
        scope.launch {
            status = "扫描中…（长按卡片背面按钮唤醒）"
            found = emptyList()
            val ble = BleClient(ctx)
            val list = withContext(Dispatchers.IO) { ble.scanAll(10_000) { status = it } }
            found = list
            val auto = list.firstOrNull { ble.isLikelyCard(it) }
            if (auto != null) { device = auto; status = "已选中 ${auto.device.address}" }
            else status = "扫到 ${list.size} 台，请在下面选择卡片"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { photo = BitmapFactory.decodeStream(it) }
            kind = "photo"; tab = 0; status = "已选择照片"
        }
    }
    var pendingScan by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { g ->
        if (g.values.all { it } && pendingScan) { pendingScan = false; scan() }
        else if (!g.values.all { it }) status = "蓝牙权限被拒绝"
    }

    fun doSend() {
        val bmp = preview ?: return
        scope.launch {
            busy = true
            try {
                status = "打包中…"
                val payload = withContext(Dispatchers.Default) {
                    QuickLz.buildPayload(Payload.build(bmp).controller)
                }
                val d = device ?: run { status = "先扫描并选中卡片"; return@launch }
                status = "连接中…"
                val ble = BleClient(ctx)
                if (!withContext(Dispatchers.IO) { ble.connect(d) }) {
                    status = "连接失败：靠近卡片、长按背面按钮唤醒后重试"; return@launch
                }
                status = "传输中（${payload.size / 1024} KB）…"
                val logs = StringBuilder("payload=${payload.size}B\n")
                withContext(Dispatchers.IO) {
                    ble.send(payload,
                        onLog = { logs.append(it).append('\n'); status = it },
                        onProgress = { progress = it })
                }
                ble.close()
                status = logs.toString().trim()
            } catch (e: Exception) {
                status = "失败：${e.message}"
            } finally { busy = false }
        }
    }

    // 调试用：adb 带 --ez auto true 启动时，自动扫描+连接+推送
    val auto = (ctx as? android.app.Activity)?.intent?.getBooleanExtra("auto", false) == true
    LaunchedEffect(auto) {
        if (!auto) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        scan()
        kotlinx.coroutines.delay(12_000)
        if (device != null) doSend()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▦") }, label = { Text("卡片") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▤") }, label = { Text("相册") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("设置") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            when (tab) {
                0 -> Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.width(150.dp).fillMaxHeight()) {
                        items(TEMPLATES) { (k, name) ->
                            val on = k == kind
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { kind = k }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Text(name, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(start = 10.dp).verticalScroll(rememberScrollState())) {
                        preview?.let {
                            Image(it.asImageBitmap(), null,
                                Modifier.fillMaxWidth().aspectRatio(528f / 792f),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                pendingScan = true; permLauncher.launch(perms)
                            }, enabled = !busy) { Text("扫描") }
                            Button(onClick = { doSend() }, enabled = preview != null && device != null && !busy) {
                                Text(if (busy) "推送中…" else "推送")
                            }
                        }
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
                1 -> Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    Button(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("从相册选择照片") }
                    photo?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(top = 12.dp)
                            .aspectRatio(528f / 792f), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    }
                    Text("选照片后会自动切到「照片」模板。批量与收藏在后续版本。",
                        fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                else -> Column(Modifier.fillMaxSize().padding(top = 12.dp).verticalScroll(rememberScrollState())) {
                    Text("设备", fontWeight = FontWeight.Bold)
                    found.forEach { r ->
                        val name = r.device.name ?: r.scanRecord?.deviceName ?: "(无名)"
                        TextButton(onClick = { device = r }) {
                            Text("${if (device?.device?.address == r.device.address) "✔ " else ""}$name  ${r.device.address}  ${r.rssi}dBm",
                                fontSize = 13.sp)
                        }
                    }
                    if (found.isEmpty()) Text("还没扫描过", fontSize = 12.sp)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("关于", fontWeight = FontWeight.Bold)
                    Text("土豆片 · 安卓版 0.5.0\n六色墨水屏卡片工坊\n\n模板：总结/一句/待办/进度/倒计时/诗词/自定义/照片\n传输：QuickLZ level-3，约 21KB",
                        fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
