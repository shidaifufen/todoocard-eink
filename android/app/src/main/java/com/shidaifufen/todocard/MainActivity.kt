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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
    "photo" to "照片轮播",
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


/** 每日计划 + 间隔推送 + 时间轴 */
@Composable
private fun PlanScreen(ctx: android.content.Context) {
    PlanStore.init(ctx)
    var fixed by remember { mutableStateOf(PlanStore.fixed(ctx)) }
    var itvs by remember { mutableStateOf(PlanStore.intervals(ctx)) }
    var hist by remember { mutableStateOf(PlanStore.history(ctx)) }
    var tick by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("时间轴（前 24h ~ 后 24h）", fontWeight = FontWeight.Bold)
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(64.dp).padding(vertical = 8.dp)
        ) {
            val now = System.currentTimeMillis()
            val start = now - 24 * 3600_000L
            val span = 48 * 3600_000f
            fun xOf(ts: Long) = ((ts - start) / span) * size.width
            drawRect(Color(0xFF1A1D24), size = size)
            // 现在线
            val nx = xOf(now)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(nx, 0f),
                androidx.compose.ui.geometry.Offset(nx, size.height), strokeWidth = 2f)
            // 历史
            hist.forEach { h ->
                val x = xOf(h.ts)
                if (x in 0f..size.width) {
                    drawLine(if (h.ok) Color(0xFF2FBF71) else Color(0xFFD9534F),
                        androidx.compose.ui.geometry.Offset(x, size.height * 0.72f),
                        androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 4f)
                }
            }
            // 未来计划
            planNext(fixed, itvs, now, 48).forEach { ts ->
                val x = xOf(ts)
                if (x in 0f..size.width) {
                    drawLine(Color(0xFF4F7CFF),
                        androidx.compose.ui.geometry.Offset(x, size.height * 0.12f),
                        androidx.compose.ui.geometry.Offset(x, size.height * 0.62f), strokeWidth = 4f)
                }
            }
        }
        val next = planNext(fixed, itvs, System.currentTimeMillis(), 48).take(4)
            .joinToString("  ") {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it))
            }
        Text("接下来：${next.ifEmpty { "无" }}", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("每日计划", fontWeight = FontWeight.Bold)
        fixed.forEachIndexed { i, f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(f.time, { v ->
                    fixed = fixed.toMutableList().also { it[i] = f.copy(time = v) }
                }, Modifier.width(110.dp), singleLine = true, label = { Text("时间") })
                Spacer(Modifier.width(8.dp))
                Text(templateName(f.template), Modifier.weight(1f), fontSize = 13.sp)
                Switch(f.enabled, { v -> fixed = fixed.toMutableList().also { it[i] = f.copy(enabled = v) } })
                TextButton({ fixed = fixed.toMutableList().also { it.removeAt(i) } }) { Text("删") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({
                fixed = fixed + PlanStore.Fixed("08:00", "summary")
            }) { Text("+ 加一条") }
            Button({
                PlanStore.saveFixed(ctx, fixed); SchedulerWorker.schedule(ctx)
            }) { Text("保存计划") }
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("间隔推送", fontWeight = FontWeight.Bold)
        itvs.forEachIndexed { i, it ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(it.everyMin.toString(), { v ->
                    itvs = itvs.toMutableList().also { l -> l[i] = it.copy(everyMin = v.toIntOrNull() ?: 60) }
                }, Modifier.width(78.dp), singleLine = true, label = { Text("分钟") })
                Spacer(Modifier.width(8.dp))
                Text("${it.start}~${it.end} ${when (it.days) { "workday" -> "工作日"; "weekend" -> "周末"; else -> "每天" }}",
                    Modifier.weight(1f), fontSize = 12.sp)
                Switch(it.enabled, { v -> itvs = itvs.toMutableList().also { l -> l[i] = it.copy(enabled = v) } })
                TextButton({ itvs = itvs.toMutableList().also { l -> l.removeAt(i) } }) { Text("删") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ itvs = itvs + PlanStore.Interval(60, "09:00", "22:00", "all", "summary") }) { Text("+ 加一条间隔") }
            Button({ PlanStore.saveIntervals(ctx, itvs); SchedulerWorker.schedule(ctx) }) { Text("保存间隔") }
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row {
            Text("发送历史", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ hist = PlanStore.history(ctx) }) { Text("刷新") }
        }
        hist.takeLast(12).reversed().forEach { h ->
            Text("${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(h.ts))}  " +
                 "${templateName(h.template)}  ${if (h.ok) "✓" else "✕"}  ${h.seconds}s",
                fontSize = 12.sp, color = if (h.ok) MaterialTheme.colorScheme.onSurface else Color(0xFFD9534F))
        }
    }
}


/** 色域面板：横向=色相，纵向=曝光 */
private fun applyPad(pos: androidx.compose.ui.geometry.Offset,
                     size: androidx.compose.ui.unit.IntSize,
                     cur: PhotoEdit.Params): PhotoEdit.Params? {
    if (size.width == 0 || size.height == 0) return null
    val fx = (pos.x / size.width).coerceIn(0f, 1f)
    val fy = (pos.y / size.height).coerceIn(0f, 1f)
    val hue = fx * 360f - 180f
    val exposure = (1f - fy) * 4f - 2f
    val sat = 1f - (fy - 0.5f).coerceIn(-0.5f, 0.5f)
    return cur.copy(hue = hue, exposure = exposure, saturation = sat.coerceIn(0.4f, 1.8f))
}

private fun templateName(k: String): String = TEMPLATES.firstOrNull { it.first == k }?.second ?: k

/** 计算未来 hours 小时内的计划时间点（固定 + 间隔） */
private fun planNext(fixed: List<PlanStore.Fixed>, itvs: List<PlanStore.Interval>,
                     now: Long, hours: Int): List<Long> {
    val out = mutableListOf<Long>()
    val cal = java.util.Calendar.getInstance()
    for (f in fixed.filter { it.enabled }) {
        val p = f.time.split(":")
        repeat(hours / 24 + 2) { d ->
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, d)
            cal.set(java.util.Calendar.HOUR_OF_DAY, p.getOrNull(0)?.toIntOrNull() ?: 8)
            cal.set(java.util.Calendar.MINUTE, p.getOrNull(1)?.toIntOrNull() ?: 0)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis > now) out.add(cal.timeInMillis)
        }
    }
    for (it in itvs.filter { it.enabled }) {
        val s = it.start.split(":"); val e = it.end.split(":")
        repeat(hours / 24 + 2) { d ->
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, d)
            cal.set(java.util.Calendar.HOUR_OF_DAY, s.getOrNull(0)?.toIntOrNull() ?: 9)
            cal.set(java.util.Calendar.MINUTE, s.getOrNull(1)?.toIntOrNull() ?: 0)
            val dayStart = cal.timeInMillis
            val endCal = (cal.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, e.getOrNull(0)?.toIntOrNull() ?: 22)
                set(java.util.Calendar.MINUTE, e.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            var t = dayStart
            while (t <= endCal.timeInMillis) {
                if (t > now) out.add(t)
                t += maxOf(5, it.everyMin) * 60_000L
            }
        }
    }
    return out.sorted()
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    remember { PlanStore.init(ctx); true }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf("summary") }
    var card by remember { mutableStateOf(Render.Card()) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoPath by remember { mutableStateOf(PlanStore.lastPhoto) }
    var pfields by remember { mutableStateOf(PlanStore.photoFields) }
    var edit by remember { mutableStateOf(PhotoEdit.Params()) }
    var albums by remember { mutableStateOf(PlanStore.albums(ctx)) }
    var status by remember { mutableStateOf("选模板 → 预览 → 推送") }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<android.bluetooth.le.ScanResult>>(emptyList()) }
    var device by remember { mutableStateOf<android.bluetooth.le.ScanResult?>(null) }
    var tab by remember { mutableStateOf(0) }

    fun refreshPreview() {
        scope.launch {
            val bmp = withContext(Dispatchers.Default) {
                if (kind == "photo" && photoPath.isNotEmpty()) {
                    PhotoCard.render(photoPath, pfields, edit)
                } else Render.render(card.copy(kind = kind))
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
            val saved = PlanStore.deviceAddress
            val auto = list.firstOrNull { (it.device.name ?: "").contains("todoo", true) }
                ?: list.firstOrNull { it.device.address.startsWith("FF:FF", true) }
                ?: list.firstOrNull { saved.isNotEmpty() && it.device.address == saved }
                ?: list.firstOrNull { it.device.address.endsWith("39:01", ignoreCase = true) }
            device = auto
            if (auto != null) {
                PlanStore.deviceAddress = auto.device.address
                PlanStore.deviceName = auto.device.name ?: ""
                status = "已选中 ${auto.device.name ?: auto.device.address}"
            } else status = "扫到 ${list.size} 台，但没有识别到卡片：长按卡片背面按钮唤醒后重扫"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { photo = BitmapFactory.decodeStream(it) }
            PhotoCard.importToFiles(ctx, uri)?.let {
                photoPath = it
                PlanStore.lastPhoto = it
            }
            kind = "photo"; tab = 0; status = "已选择照片"
        }
    }
    val cameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val uri = cameraUri.value
            if (uri != null) {
                ctx.contentResolver.openInputStream(uri)?.use { photo = BitmapFactory.decodeStream(it) }
                PhotoCard.importToFiles(ctx, uri)?.let { photoPath = it; PlanStore.lastPhoto = it }
                kind = "photo"; tab = 0; status = "拍照完成，可直接推送"
            }
        }
    }
    fun takePhoto() {
        val dir = java.io.File(ctx.cacheDir, "cam").apply { mkdirs() }
        val f = java.io.File(dir, "shot_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        cameraUri.value = uri
        cameraLauncher.launch(uri)
    }
    val albumLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PlanStore.addAlbum(ctx, uri.toString())
            albums = PlanStore.albums(ctx)
            status = "已添加相册"
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
                    QuickLz.buildPayload(Payload.build(bmp).controller)   // 压缩包（官方固件 v157+）
                }
                val saved = PlanStore.deviceAddress
                val ble = BleClient(ctx)
                status = "等待卡片广播…（最长 120 秒，随时会连接）"
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
                        status = "还没扫到卡片，继续等（剩 ${left}s，可长按卡片背面按钮唤醒）"
                    }
                }
                if (target == null) { status = "等了 120 秒仍没扫到卡片，请唤醒后重试"; return@launch }
                PlanStore.deviceAddress = target.device.address
                PlanStore.deviceName = target.device.name ?: ""
                status = "连接中…"
                if (!withContext(Dispatchers.IO) { ble.connect(target!!) }) {
                    status = "连接失败：卡片可能在休眠或被别的设备占用"; return@launch
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
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.Dashboard, null, Modifier.size(24.dp)) },
                    label = { Text("卡片", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(24.dp)) },
                    label = { Text("相册", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(24.dp)) },
                    label = { Text("计划", fontSize = 12.sp) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Outlined.Settings, null, Modifier.size(24.dp)) },
                    label = { Text("设置", fontSize = 12.sp) })
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
                            Button(onClick = { doSend() }, enabled = preview != null && (device != null || PlanStore.deviceAddress.isNotEmpty()) && !busy) {
                                Text(if (busy) "推送中…" else "推送")
                            }
                        }
                        if (kind == "photo" && photoPath.isNotEmpty()) {
                            Text("信息带显示项（勾掉不占高度）", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(pfields.date, { v -> pfields = pfields.copy(date = v); PlanStore.photoFields = pfields })
                                Text("日期", fontSize = 12.sp)
                                Checkbox(pfields.time, { v -> pfields = pfields.copy(time = v); PlanStore.photoFields = pfields })
                                Text("时间", fontSize = 12.sp)
                                Checkbox(pfields.camera, { v -> pfields = pfields.copy(camera = v); PlanStore.photoFields = pfields })
                                Text("机身", fontSize = 12.sp)
                                Checkbox(pfields.params, { v -> pfields = pfields.copy(params = v); PlanStore.photoFields = pfields })
                                Text("参数", fontSize = 12.sp)
                            }
                        }

                        if (found.isNotEmpty()) {
                            Text("设备（点选）", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                            LazyColumn(Modifier.heightIn(max = 160.dp)) {
                                items(found) { r ->
                                    val nm = r.device.name ?: r.scanRecord?.deviceName ?: "(无名)"
                                    val sel = device?.device?.address == r.device.address
                                    TextButton(onClick = {
                                        device = r
                                        PlanStore.deviceAddress = r.device.address
                                        status = "已选中 $nm"
                                    }) { Text("${if (sel) "✔ " else ""}$nm  ${r.device.address}  ${r.rssi}dBm", fontSize = 12.sp) }
                                }
                            }
                        }
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
                1 -> Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("选择照片") }
                        Button(onClick = { takePhoto() }) { Text("拍照") }
                        OutlinedButton(onClick = { albumLauncher.launch(null) }) { Text("添加相册") }
                    }
                    if (albums.isNotEmpty()) {
                        var photos by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
                        LaunchedEffect(albums) {
                            photos = withContext(Dispatchers.IO) { AlbumStore.listImages(ctx, albums) }
                        }
                        if (photos.isNotEmpty()) {
                            Text("相册里有 ${photos.size} 张，点选载入", fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 6.dp)) {
                                items(photos.size) { i ->
                                    val u = photos[i]
                                    Box(Modifier.padding(end = 8.dp).clickable {
                                        PhotoCard.importToFiles(ctx, u)?.let {
                                            photoPath = it; PlanStore.lastPhoto = it
                                            status = "已载入相册照片"
                                        }
                                    }.width(86.dp).height(86.dp)
                                        .background(androidx.compose.ui.graphics.Color(0xFF2A2D35))) {
                                        coil.compose.AsyncImage(u, null, Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    }
                                }
                            }
                        }
                        Text("已添加 ${albums.size} 个相册", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    if (photoPath.isNotEmpty()) {
                        val previewBmp = remember(photoPath, edit) {
                            runCatching { PhotoCard.render(photoPath, pfields, edit) }.getOrNull()
                        }
                        // 卡片框 + 胶片网格，手指直接拖
                        Box(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .aspectRatio(528f / 792f)
                                .align(Alignment.CenterHorizontally)
                                .background(androidx.compose.ui.graphics.Color.Black)
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val nz = (edit.zoom * zoom).coerceIn(1f, 4f)
                                        val lim = (nz - 1f) / 2f
                                        edit = edit.copy(
                                            zoom = nz,
                                            dx = (edit.dx + pan.x / size.width * 2f).coerceIn(-lim, lim),
                                            dy = (edit.dy + pan.y / size.height * 2f).coerceIn(-lim, lim),
                                        )
                                    }
                                }
                        ) {
                            previewBmp?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                            }
                            // 三分线 + 边框
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val w = size.width; val h = size.height
                                val stroke = 1.dp.toPx()
                                drawRect(androidx.compose.ui.graphics.Color.White,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                                val c = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)
                                drawLine(c, androidx.compose.ui.geometry.Offset(w / 3, 0f),
                                    androidx.compose.ui.geometry.Offset(w / 3, h), stroke)
                                drawLine(c, androidx.compose.ui.geometry.Offset(w * 2 / 3, 0f),
                                    androidx.compose.ui.geometry.Offset(w * 2 / 3, h), stroke)
                                drawLine(c, androidx.compose.ui.geometry.Offset(0f, h / 3),
                                    androidx.compose.ui.geometry.Offset(w, h / 3), stroke)
                                drawLine(c, androidx.compose.ui.geometry.Offset(0f, h * 2 / 3),
                                    androidx.compose.ui.geometry.Offset(w, h * 2 / 3), stroke)
                            }
                            Text("拖动平移 · 双指缩放", fontSize = 10.sp,
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                        }

                        // 色彩偏好面板（参考官方：色相/饱和度色域 + 曝光）
                        Text("色彩偏好", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp))
                        var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                        Box(
                            Modifier.fillMaxWidth().height(150.dp).padding(top = 6.dp)
                                .onSizeChanged { padSize = it }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { off -> applyPad(off, padSize, edit)?.let { edit = it } },
                                        onDrag = { change, _ -> applyPad(change.position, padSize, edit)?.let { edit = it } }
                                    )
                                }
                        ) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val w = size.width; val h = size.height
                                // 横向色相带
                                drawRect(
                                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(
                                            androidx.compose.ui.graphics.Color(0xFF3B82F6),
                                            androidx.compose.ui.graphics.Color(0xFF22D3EE),
                                            androidx.compose.ui.graphics.Color(0xFF4ADE80),
                                            androidx.compose.ui.graphics.Color(0xFFFACC15),
                                            androidx.compose.ui.graphics.Color(0xFFF87171),
                                            androidx.compose.ui.graphics.Color(0xFFC084FC),
                                        )
                                    )
                                )
                                // 上下叠加白/黑，纵向表示明暗（饱和度/曝光）
                                drawRect(brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                                        androidx.compose.ui.graphics.Color.Transparent,
                                        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
                                ))
                                // 当前点
                                val px = (edit.hue + 180f) / 360f * w
                                val py = (1f - (edit.exposure + 2f) / 4f) * h
                                drawCircle(androidx.compose.ui.graphics.Color.White, 9f,
                                    androidx.compose.ui.geometry.Offset(px, py),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
                                drawCircle(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), 4f,
                                    androidx.compose.ui.geometry.Offset(px, py))
                            }
                        }
                        Text("色彩 ${edit.hue.toInt()}    曝光 ${"%.1f".format(edit.exposure)}    饱和 ${"%.2f".format(edit.saturation)}",
                            fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Button(onClick = { kind = "photo"; tab = 0 }) { Text("保存并推送") }
                            OutlinedButton(onClick = { edit = PhotoEdit.Params() }) { Text("重置") }
                        }
                    }
                }
                2 -> PlanScreen(ctx)
                else -> Column(Modifier.fillMaxSize().padding(top = 12.dp).verticalScroll(rememberScrollState())) {
                    Text("配对卡片", fontWeight = FontWeight.Bold)
                    Text(if (PlanStore.deviceAddress.isEmpty()) "未配对"
                         else "已配对：${PlanStore.deviceName.ifEmpty { PlanStore.deviceAddress }}",
                        fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            pendingScan = true; permLauncher.launch(perms)
                        }) { Text("扫描") }
                        Button(enabled = device != null, onClick = {
                            val d = device ?: return@Button
                            scope.launch {
                                status = "配对中…"
                                val ble = BleClient(ctx)
                                val ok = withContext(Dispatchers.IO) { ble.connect(d) }
                                if (ok) {
                                    PlanStore.deviceAddress = d.device.address
                                    PlanStore.deviceName = d.device.name ?: ""
                                    status = "配对成功，回「卡片」页点推送即可"
                                } else status = "配对失败，靠近卡片再试"
                                ble.close()
                            }
                        }) { Text("配对选中设备") }
                        TextButton(onClick = {
                            PlanStore.deviceAddress = ""; PlanStore.deviceName = ""
                            status = "已解除配对"
                        }) { Text("解除") }
                    }

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
