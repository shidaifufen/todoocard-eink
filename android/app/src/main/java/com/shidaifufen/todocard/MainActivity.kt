package com.shidaifufen.todocard

import android.Manifest
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val TEMPLATES = listOf(
    "summary" to "每日总结",
    "quote" to "每日一句",
    "todo" to "今日待办",
    "progress" to "时间进度",
    "countdown" to "倒计时",
    "poem" to "每日诗词",
    "custom" to "自定义卡片",
    "photo" to "照片卡片",
)

private fun handleSharedIntent(ctx: Context, intent: Intent?,
                               onPhoto: (Uri) -> Unit, onText: (String) -> Unit) {
    when (intent?.action) {
        Intent.ACTION_SEND -> {
            val uri = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) onPhoto(uri)
            else intent.getStringExtra(Intent.EXTRA_TEXT)?.let(onText)
        }
        Intent.ACTION_VIEW -> intent.data?.let(onPhoto)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()?.let(onText)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    remember(ctx) { PlanStore.init(ctx); true }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf("summary") }
    var card by remember { mutableStateOf(PlanStore.card(ctx)) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var photoPath by remember { mutableStateOf(PlanStore.lastPhoto) }
    var fields by remember { mutableStateOf(PlanStore.photoFields) }
    var edit by remember { mutableStateOf(PhotoEdit.Params()) }
    var albums by remember { mutableStateOf(PlanStore.albums(ctx)) }
    var status by remember { mutableStateOf("选择模板并预览，再推送到卡片") }
    var progress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var device by remember { mutableStateOf<ScanResult?>(null) }
    var pairedAddress by remember { mutableStateOf(PlanStore.deviceAddress) }
    var pairedName by remember { mutableStateOf(PlanStore.deviceName) }

    val photoReady = photoPath.isNotBlank() && java.io.File(photoPath).isFile
    LaunchedEffect(kind, card, photoPath, fields, edit) {
        preview = withContext(Dispatchers.Default) {
            runCatching {
                if (kind == "photo") {
                    if (!photoReady) null else PhotoCard.render(photoPath, fields, edit)
                } else Render.render(card.copy(kind = kind))
            }.getOrNull()
        }
    }

    fun loadPhoto(uri: Uri) {
        val path = PhotoCard.importToFiles(ctx, uri)
        if (path == null) { status = "照片读取失败，请重新选择"; return }
        photoPath = path
        PlanStore.lastPhoto = path
        edit = PhotoEdit.Params()
        kind = "photo"
        status = "照片已载入"
    }

    LaunchedEffect(Unit) {
        handleSharedIntent(ctx, (ctx as? android.app.Activity)?.intent,
            onPhoto = { uri -> loadPhoto(uri); tab = 1 },
            onText = { text ->
                if (text.isNotBlank()) {
                    card = card.copy(customTitle = "分享内容",
                        customLines = text.lines().filter { it.isNotBlank() })
                    PlanStore.saveCard(ctx, card)
                    kind = "custom"
                    tab = 0
                    status = "分享文字已载入"
                }
            })
    }

    fun scan() {
        scope.launch {
            status = "扫描中，请唤醒卡片"
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
                pairedAddress = PlanStore.deviceAddress
                pairedName = PlanStore.deviceName
                status = "已选中 ${auto.device.name ?: auto.device.address}"
            } else status = "扫描到 ${list.size} 台设备，请唤醒卡片后重试"
        }
    }

    var pendingScan by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it } && pendingScan) { pendingScan = false; scan() }
        else if (!grants.values.all { it }) status = "需要蓝牙权限才能扫描设备"
    }
    fun requestScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        pendingScan = true
        permLauncher.launch(permissions)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadPhoto(it) }
    }
    val cameraUri = remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri.value?.let { loadPhoto(it) }
    }
    fun takePhoto() {
        val dir = java.io.File(ctx.cacheDir, "cam").apply { mkdirs() }
        val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", file)
        cameraUri.value = uri
        cameraLauncher.launch(uri)
    }
    val albumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PlanStore.addAlbum(ctx, uri.toString())
            albums = PlanStore.albums(ctx)
            status = "已添加轮播相册"
        }
    }

    fun selectDevice(result: ScanResult) {
        device = result
        PlanStore.deviceAddress = result.device.address
        PlanStore.deviceName = result.device.name ?: result.scanRecord?.deviceName ?: ""
        pairedAddress = PlanStore.deviceAddress
        pairedName = PlanStore.deviceName
        status = "已选中 ${pairedName.ifEmpty { pairedAddress }}"
    }

    fun pairDevice() {
        val selected = device ?: return
        scope.launch {
            status = "正在配对…"
            val ble = BleClient(ctx)
            val ok = withContext(Dispatchers.IO) { ble.connect(selected) }
            if (ok) {
                selectDevice(selected)
                status = "配对成功，可以推送卡片"
            } else status = "配对失败，请靠近卡片重试"
            ble.close()
        }
    }

    fun doSend() {
        val bmp = preview ?: return
        val sentKind = kind
        scope.launch {
            val started = System.currentTimeMillis()
            busy = true
            progress = 0
            try {
                status = "打包中…"
                val payload = withContext(Dispatchers.Default) {
                    QuickLz.buildPayload(Payload.build(bmp).controller)
                }
                val saved = PlanStore.deviceAddress
                val ble = BleClient(ctx)
                status = "等待卡片广播，最长 120 秒"
                var target: ScanResult? = null
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline && target == null) {
                    val list = withContext(Dispatchers.IO) { ble.scanAll(10_000) }
                    target = list.firstOrNull { (it.device.name ?: "").contains("todoo", true) }
                        ?: list.firstOrNull { it.device.address.startsWith("FF:FF", true) }
                        ?: list.firstOrNull { saved.isNotEmpty() && it.device.address == saved }
                        ?: list.firstOrNull { it.device.address.endsWith("39:01", true) }
                    if (target == null) {
                        val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        status = "等待卡片广播，剩余 ${left} 秒"
                    }
                }
                if (target == null) { status = "未找到卡片，请唤醒后重试"; return@launch }
                PlanStore.deviceAddress = target.device.address
                PlanStore.deviceName = target.device.name ?: ""
                pairedAddress = PlanStore.deviceAddress
                pairedName = PlanStore.deviceName
                status = "连接中…"
                if (!withContext(Dispatchers.IO) { ble.connect(target!!) }) {
                    status = "连接失败：卡片可能在休眠或被占用"; return@launch
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
            } finally {
                PlanStore.addHistory(ctx, PlanStore.Hist(
                    System.currentTimeMillis(), sentKind,
                    status.lineSequence().lastOrNull() == "已完成并收到显影确认",
                    ((System.currentTimeMillis() - started) / 1000).toInt(),
                    status.lineSequence().lastOrNull().orEmpty(),
                ))
                busy = false
            }
        }
    }

    val auto = (ctx as? android.app.Activity)?.intent?.getBooleanExtra("auto", false) == true
    LaunchedEffect(auto) {
        if (auto) {
            kotlinx.coroutines.delay(1500)
            scan()
            kotlinx.coroutines.delay(12_000)
            if (device != null) doSend()
        }
    }

    MaterialTheme(colorScheme = StudioColors) {
        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(
                    Triple("卡片", Icons.Outlined.Dashboard, 0),
                    Triple("相册", Icons.Outlined.PhotoLibrary, 1),
                    Triple("计划", Icons.Outlined.Schedule, 2),
                    Triple("设置", Icons.Outlined.Settings, 3),
                ).forEach { (label, icon, index) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        label = { Text(label) })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> StudioScreen(kind, card, preview, photoReady, status, progress, busy,
                        pairedName.ifEmpty { pairedAddress }, found, device?.device?.address,
                        onKind = { kind = it }, onCard = { card = it; PlanStore.saveCard(ctx, it) },
                        onPhoto = { tab = 1 },
                        onScan = ::requestScan, onSelectDevice = ::selectDevice,
                        onSend = ::doSend)
                    1 -> LibraryScreen(albums, photoPath, fields, edit,
                        onPick = { picker.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onCamera = ::takePhoto, onAddAlbum = { albumLauncher.launch(null) },
                        onAlbumPhoto = ::loadPhoto,
                        onFields = { fields = it; PlanStore.photoFields = it },
                        onEdit = { edit = it }, onUse = { kind = "photo"; tab = 0 })
                    2 -> PlanScreen(ctx)
                    else -> SettingsScreen(pairedName, pairedAddress, fields, found,
                        device?.device?.address, status,
                        onScan = ::requestScan, onSelect = ::selectDevice,
                        onPair = ::pairDevice,
                        onUnpair = {
                            PlanStore.deviceAddress = ""
                            PlanStore.deviceName = ""
                            pairedAddress = ""
                            pairedName = ""
                            device = null
                            status = "已解除配对"
                        },
                        onFields = { fields = it; PlanStore.photoFields = it })
                }
            }
        }
    }
}
