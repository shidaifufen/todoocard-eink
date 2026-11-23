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
import androidx.compose.material.icons.outlined.Casino
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
    "weather" to "天气看板",
    "calendar" to "月历",
    "progress" to "时间进度",
    "countdown" to "倒计时",
    "poem" to "每日诗词",
    "custom" to "自定义卡片",
    "photo" to "照片轮播",
)

internal fun handleSharedIntent(intent: Intent?,
                               onPhotos: (List<Uri>) -> Unit, onText: (String) -> Unit) {
    when (intent?.action) {
        Intent.ACTION_SEND -> {
            val uri = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) onPhotos(listOf(uri))
            else intent.getStringExtra(Intent.EXTRA_TEXT)?.let(onText)
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            uris?.distinct()?.take(30)?.takeIf { it.isNotEmpty() }?.let(onPhotos)
        }
        Intent.ACTION_VIEW -> intent.data?.let { onPhotos(listOf(it)) }
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()?.let(onText)
    }
}

class MainActivity : ComponentActivity() {
    private var incomingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntent = if (savedInstanceState == null) intent else null
        setContent { App(incomingIntent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent = intent
    }
}

@Composable
private fun App(incomingIntent: Intent?) {
    val ctx = LocalContext.current
    remember(ctx) { PlanStore.init(ctx); true }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf("summary") }
    var card by remember { mutableStateOf(PlanStore.card(ctx)) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var photoPath by remember { mutableStateOf(PlanStore.lastPhoto) }
    var fields by remember { mutableStateOf(PlanStore.photoFields) }
    var edit by remember { mutableStateOf(PlanStore.photoEdit) }
    var albums by remember { mutableStateOf(PlanStore.albums(ctx)) }
    var carouselPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedCarousel by remember { mutableStateOf<Uri?>(null) }
    var pendingSharedPush by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("选择模板并预览，再推送到卡片") }
    var progress by remember { mutableIntStateOf(0) }
    var preparing by remember { mutableStateOf(false) }
    val pushState by PushService.status.collectAsState()
    val busy = preparing || pushState.running
    var found by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var device by remember { mutableStateOf<ScanResult?>(null) }
    var pairedAddress by remember { mutableStateOf(PlanStore.deviceAddress) }
    var pairedName by remember { mutableStateOf(PlanStore.deviceName) }

    LaunchedEffect(pushState) {
        if (pushState.message.isNotBlank()) {
            status = pushState.message
            progress = pushState.progress
            pairedAddress = PlanStore.deviceAddress
            pairedName = PlanStore.deviceName
        }
    }

    val photoReady = photoPath.isNotBlank() && java.io.File(photoPath).isFile
    LaunchedEffect(albums) {
        carouselPhotos = withContext(Dispatchers.IO) { AlbumStore.listImages(ctx, albums) }
        if (selectedCarousel !in carouselPhotos) selectedCarousel = carouselPhotos.firstOrNull()
    }
    LaunchedEffect(kind, card, photoPath, fields, edit, tab) {
        if (tab != 0) return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            runCatching {
                if (kind == "photo") {
                    if (!photoReady) null else PhotoCard.render(photoPath, fields, edit)
                } else Render.render(card.copy(kind = kind))
            }.getOrNull()
        }
    }
    LaunchedEffect(edit) {
        kotlinx.coroutines.delay(300)
        PlanStore.photoEdit = edit
    }

    fun loadPhoto(uri: Uri, autoSend: Boolean = false, openEditor: Boolean = true) {
        scope.launch {
            status = "正在载入照片…"
            val path = withContext(Dispatchers.IO) { PhotoCard.importToFiles(ctx, uri) }
            if (path == null) { status = "照片读取失败，请重新选择"; return@launch }
            photoPath = path
            PlanStore.lastPhoto = path
            edit = PhotoEdit.Params()
            PlanStore.photoEdit = edit
            kind = "photo"
            tab = if (autoSend || !openEditor) 0 else 2
            if (autoSend) pendingSharedPush = path
            status = if (autoSend) "分享照片已收到，准备直接推送…" else "照片已载入"
        }
    }

    fun loadSharedPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            status = "正在导入 ${uris.size} 张分享照片…"
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> PhotoCard.importToFiles(ctx, uri) }
            }
            if (imported.isEmpty()) {
                status = "照片读取失败，请在应用内重新选择"
                return@launch
            }
            imported.forEach { path -> PlanStore.addAlbum(ctx, Uri.fromFile(java.io.File(path)).toString()) }
            val first = imported.first()
            photoPath = first
            PlanStore.lastPhoto = first
            edit = PhotoEdit.Params()
            PlanStore.photoEdit = edit
            kind = "photo"
            tab = 0
            pendingSharedPush = first
            albums = PlanStore.albums(ctx)
            status = if (imported.size == uris.size) "已导入 ${imported.size} 张，正准备推送第一张" else
                "已导入 ${imported.size}/${uris.size} 张，正准备推送第一张"
        }
    }

    LaunchedEffect(incomingIntent) {
        handleSharedIntent(incomingIntent,
            onPhotos = ::loadSharedPhotos,
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

    var onPermissionsGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            val action = onPermissionsGranted
            onPermissionsGranted = null
            action?.invoke()
        }
        else if (!grants.values.all { it }) status = "需要蓝牙权限才能扫描设备"
    }
    fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun requestScan() {
        onPermissionsGranted = { scan() }
        permLauncher.launch(bluetoothPermissions())
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadPhoto(it) }
    }
    val multiplePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                status = "正在添加轮播照片…"
                val retained = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val persisted = runCatching {
                            ctx.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }.isSuccess
                        if (persisted) uri else PhotoCard.importToFiles(ctx, uri)
                            ?.let { Uri.fromFile(java.io.File(it)) }
                    }
                }
                retained.forEach { PlanStore.addAlbum(ctx, it.toString()) }
                albums = PlanStore.albums(ctx)
                selectedCarousel = retained.firstOrNull()
                status = if (retained.size == uris.size) "已添加 ${retained.size} 张轮播照片" else
                    "已添加 ${retained.size}/${uris.size} 张，其余照片无法读取"
            }
        }
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
            runCatching { ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            PlanStore.addAlbum(ctx, uri.toString())
            albums = PlanStore.albums(ctx)
            status = "已添加轮播相册"
        }
    }
    fun addCarouselPhotos() {
        multiplePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun drawCarousel(): Uri? {
        val chosen = carouselPhotos.filter { it.toString() != PlanStore.lastDrawnUri }
            .ifEmpty { carouselPhotos }.randomOrNull() ?: return null
        selectedCarousel = chosen
        PlanStore.lastDrawnUri = chosen.toString()
        status = "已抽到一张照片"
        return chosen
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
        if (busy) return
        val sentKind = kind
        val sentCard = card.copy(kind = kind)
        val sentPath = photoPath
        val sentFields = fields
        val sentEdit = edit
        preparing = true
        scope.launch {
            try {
                status = "正在准备卡片…"
                val file = withContext(Dispatchers.IO) {
                    val bmp = if (sentKind == "photo") {
                        check(java.io.File(sentPath).isFile) { "请先选择照片" }
                        PhotoCard.render(sentPath, sentFields, sentEdit)
                    } else Render.render(sentCard)
                    val output = java.io.File.createTempFile("push_", ".png", ctx.cacheDir)
                    try {
                        output.outputStream().use {
                            check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally {
                        bmp.recycle()
                    }
                    output
                }
                ctx.startForegroundService(Intent(ctx, PushService::class.java)
                    .putExtra("bitmapPath", file.absolutePath).putExtra("kind", sentKind))
            } catch (e: Exception) {
                status = "失败：${e.message}"
            } finally {
                preparing = false
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        onPermissionsGranted = { doSend() }
        permLauncher.launch(bluetoothPermissions())
    }

    fun requestSend() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPermissionsGranted = { doSend() }
            permLauncher.launch(bluetoothPermissions())
        }
    }

    LaunchedEffect(pendingSharedPush, photoPath, busy) {
        if (pendingSharedPush != null && pendingSharedPush == photoPath && !busy) {
            pendingSharedPush = null
            requestSend()
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
                    Triple("轮播", Icons.Outlined.Casino, 1),
                    Triple("相册", Icons.Outlined.PhotoLibrary, 2),
                    Triple("计划", Icons.Outlined.Schedule, 3),
                    Triple("设置", Icons.Outlined.Settings, 4),
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
                        onPhoto = { tab = 2 },
                        onScan = ::requestScan, onSelectDevice = ::selectDevice,
                        onSend = ::requestSend,
                        carouselCount = carouselPhotos.size,
                        onDraw = { drawCarousel()?.let { loadPhoto(it, openEditor = false) } },
                        onAddPhotos = ::addCarouselPhotos)
                    1 -> CarouselScreen(carouselPhotos, selectedCarousel, busy,
                        onAddPhotos = ::addCarouselPhotos,
                        onDraw = { drawCarousel() },
                        onSelect = { selectedCarousel = it },
                        onUse = { selectedCarousel?.let { loadPhoto(it) } },
                        onSend = { selectedCarousel?.let { loadPhoto(it, autoSend = true) } },
                        onRemove = {
                            selectedCarousel?.let { PlanStore.removeAlbum(it.toString()) }
                            carouselPhotos = carouselPhotos.filter { it != selectedCarousel }
                            albums = PlanStore.albums(ctx)
                            selectedCarousel = carouselPhotos.firstOrNull()
                        })
                    2 -> LibraryScreen(carouselPhotos, photoPath, fields, edit,
                        onPick = { picker.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onCamera = ::takePhoto, onAddAlbum = { albumLauncher.launch(null) },
                        onAddPhotos = ::addCarouselPhotos,
                        onAlbumPhoto = { loadPhoto(it) },
                        onFields = { fields = it; PlanStore.photoFields = it },
                        onEdit = { edit = it }, onUse = {
                            PlanStore.photoEdit = edit
                            kind = "photo"; tab = 0
                        })
                    3 -> PlanScreen(ctx)
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
