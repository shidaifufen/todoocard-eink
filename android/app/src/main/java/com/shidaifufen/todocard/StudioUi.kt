package com.shidaifufen.todocard

import android.bluetooth.le.ScanResult
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal val StudioColors = lightColorScheme(
    primary = Color(0xFF096B61),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFDDEDE7),
    onSecondaryContainer = Color(0xFF096B61),
    tertiaryContainer = Color(0xFFF8E8D8),
    background = Color(0xFFF5F6F7),
    onBackground = Color(0xFF252A2D),
    surface = Color.White,
    onSurface = Color(0xFF252A2D),
    surfaceVariant = Color(0xFFE8ECEE),
    onSurfaceVariant = Color(0xFF56636B),
)

internal val StudioDarkColors = darkColorScheme(
    primary = Color(0xFF83D8C7), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF125047), onPrimaryContainer = Color(0xFFA6F2E2),
    secondary = Color(0xFFB1CCC4), onSecondary = Color(0xFF1D352F),
    secondaryContainer = Color(0xFF29453F), onSecondaryContainer = Color(0xFFD2F1E8),
    tertiary = Color(0xFFF1BA89), tertiaryContainer = Color(0xFF55371F),
    onTertiaryContainer = Color(0xFFFFDEC1),
    background = Color(0xFF101615), onBackground = Color(0xFFE2EAE6),
    surface = Color(0xFF171F1D), onSurface = Color(0xFFE2EAE6),
    surfaceVariant = Color(0xFF273330), onSurfaceVariant = Color(0xFFBBCBC5),
    outline = Color(0xFF889A94), outlineVariant = Color(0xFF3E4F49),
    error = Color(0xFFFFB4A9),
)

internal enum class StudioTheme(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

internal val StudioInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val StudioMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val StudioGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val StudioRed: Color @Composable get() = MaterialTheme.colorScheme.error
internal val StudioBackground: Color @Composable get() = MaterialTheme.colorScheme.background

@Composable
internal fun StudioScreen(
    kind: String,
    card: Render.Card,
    preview: Bitmap?,
    photoReady: Boolean,
    status: String,
    progress: Int,
    busy: Boolean,
    deviceName: String,
    found: List<ScanResult>,
    selectedAddress: String?,
    onKind: (String) -> Unit,
    onCard: (Render.Card) -> Unit,
    onLayout: (Render.Card) -> Unit,
    onPhoto: () -> Unit,
    onScan: () -> Unit,
    onSelectDevice: (ScanResult) -> Unit,
    onSend: () -> Unit,
    carouselCount: Int,
    onDraw: () -> Unit,
    onAddPhotos: () -> Unit,
    onRefresh: () -> Unit,
    onAnalysis: () -> Unit,
    refreshing: Boolean,
    onScanAll: () -> Unit,
    scanTimedOut: Boolean,
    onGenerate: (String) -> Unit,
    onlineControls: @Composable () -> Unit,
    onlineSaving: Boolean,
    photoEdit: PhotoEdit.Params,
    onPhotoEdit: (PhotoEdit.Params) -> Unit,
    onlineContent: Boolean,
    onSourceMode: (Boolean) -> Unit,
) {
    var layoutOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    val isImage = kind in listOf("photo", "bing", "ai")
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val cards = rememberLazyListState()
    LaunchedEffect(drawer.targetValue) {
        if (drawer.targetValue == DrawerValue.Open) {
            val selected = TEMPLATES.indexOfFirst { it.first == kind }.coerceAtLeast(0)
            if (cards.layoutInfo.visibleItemsInfo.none { it.index == selected }) cards.scrollToItem(selected)
        }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(224.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("切换卡片", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { scope.launch { drawer.close() } }) { Icon(Icons.Outlined.Close, "收起侧栏") }
                }
                LazyColumn(state = cards, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(TEMPLATES, key = { it.first }) { (key, title) ->
                        NavigationDrawerItem(label = { Text(title) }, selected = kind == key, onClick = {
                            if (!busy) {
                                onKind(key)
                                scope.launch { drawer.close() }
                            }
                        })
                    }
                }
            }
        }) {
    BoxWithConstraints(Modifier.fillMaxSize().background(StudioBackground)) {
    val previewHeight = (maxHeight - 320.dp).coerceIn(200.dp, 560.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "展开卡片侧栏") }
            Text(templateName(kind), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = StudioInk)
        }
        if (kind in listOf("summary", "quote", "poem", "weather", "weather2", "custom") || LayoutStore.sources(kind)?.isNotEmpty() == true) Row(
            Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = onlineContent, onClick = { onSourceMode(true) }, enabled = !busy, label = { Text("联网更新") })
            FilterChip(selected = !onlineContent, onClick = { onSourceMode(false) }, enabled = !busy, label = { Text("手动内容") })
        }
        Spacer(Modifier.height(8.dp))
        if (kind == "online") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { onlineControls() }
        }
        if (kind == "photo") Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("轮播图库 · $carouselCount 张", color = StudioMuted, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onAddPhotos, enabled = !busy) { Text("添加") }
            FilledTonalButton(onClick = onDraw, enabled = carouselCount > 0 && !busy,
                shape = RoundedCornerShape(8.dp)) { Text("抽一张") }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(previewHeight).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val imageWidth = minOf(maxWidth - 20.dp, (maxHeight - 12.dp).coerceAtLeast(80.dp) * 2f / 3f)
            Box(
                Modifier.width(imageWidth).aspectRatio(2f / 3f)
                    .background(if (preview != null) Color.White else MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .clickable(enabled = onlineContent && kind in listOf("quote", "poem") && !busy && !refreshing,
                        onClickLabel = "换一条内容", onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                if (isImage && !photoReady) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(32.dp), tint = StudioMuted)
                        Spacer(Modifier.height(10.dp))
                        Text(if (kind == "bing") "点击获取每日壁纸" else if (kind == "ai") "输入提示词生成图片" else "先选择一张照片", color = StudioMuted, fontSize = 13.sp)
                        TextButton(onClick = { if (kind == "bing") onRefresh() else if (kind == "ai") generating = true else onPhoto() }) { Text(if (kind == "bing") "获取壁纸" else if (kind == "ai") "开始生成" else "打开相册") }
                    }
                } else if (preview == null) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    else Text(if (kind == "online") "先搜索或抽取一张卡片" else "暂时无法获取内容，点击刷新重试", color = StudioMuted,
                        modifier = Modifier.padding(20.dp))
                }
                else Image(preview.asImageBitmap(), "卡片预览", Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("屏幕预览", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (onlineContent && (kind == "bing" || LayoutStore.sources(kind)?.isNotEmpty() == true || kind in listOf("quote", "poem", "weather", "weather2", "summary", "custom"))) TextButton(
                    enabled = !busy && !refreshing, onClick = onRefresh) {
                    Text(if (refreshing) "更新中…" else if (kind in listOf("weather", "weather2")) "刷新天气" else "换一张")
                }
                if (kind !in listOf("progress", "calendar", "online")) TextButton(enabled = !busy, onClick = {
                    if (isImage) onPhoto() else editing = true
                }) {
                    Icon(if (isImage) Icons.Outlined.PhotoLibrary else Icons.Outlined.Edit,
                        null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (isImage) "编辑照片" else "编辑内容")
                }
            }
            if (kind == "poem") TextButton(onClick = onAnalysis, enabled = !busy && !refreshing) {
                Text(if (refreshing) "赏析获取中…" else if (card.poemNote.isBlank()) "为这首诗写 AI 赏析" else "重新生成 AI 赏析")
            }
            if (isImage && photoReady) {
                TextButton(enabled = !busy, onClick = { layoutOpen = !layoutOpen }) {
                    Text(if (layoutOpen) "收起调色" else "图片调色")
                }
                if (layoutOpen) {
                    PhotoToneAdjustments(photoEdit, onPhotoEdit)
                    LayoutSlider("杂色吸附", photoEdit.snapThreshold / 100f, 0f..1f) { onPhotoEdit(photoEdit.copy(snapThreshold = (it * 100).toInt())) }
                }
            }
            if (!isImage && kind != "online") {
                TextButton(onClick = { layoutOpen = !layoutOpen }) { Text(if (layoutOpen) "收起排版" else "字号、行距与元素间距") }
                if (layoutOpen) {
                    LayoutSlider("文字大小", card.fontScale, .8f..1.3f) { onLayout(card.copy(fontScale = it)) }
                    LayoutSlider("行间距", card.lineSpacing, .8f..1.5f) { onLayout(card.copy(lineSpacing = it)) }
                    LayoutSlider("元素间距", card.elementSpacing, .7f..1.4f) { onLayout(card.copy(elementSpacing = it)) }
                    TextButton(onClick = { onLayout(card.copy(fontScale = 1f, lineSpacing = 1f, elementSpacing = 1f)) }) { Text("恢复默认排版") }
                }
            }
            if (kind == "ai") TextButton(onClick = { generating = true }, enabled = !busy && !refreshing) {
                Text("输入提示词生成图片")
            }
            Text(
                if (deviceName.isBlank()) "尚未连接卡片" else "目标设备 · $deviceName",
                fontSize = 12.sp, color = StudioMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(status.lineSequence().lastOrNull().orEmpty(), fontSize = 12.sp,
                color = if (status.startsWith("失败")) StudioRed else StudioMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (scanTimedOut) TextButton(onClick = onScanAll, enabled = !busy) { Text("未找到卡片？查找所有设备") }
            if (busy && progress in 1..99) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onScan, enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.BluetoothSearching, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫描设备")
                }
                Button(onClick = onSend, enabled = preview != null && !busy && (kind != "online" || !onlineSaving),
                    modifier = Modifier.weight(1.2f).height(48.dp), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "推送中" else "推送到卡片")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    }
    }
    if (editing && kind == "custom") CustomEditorDialog(card, onDismiss = { editing = false }, onSave = { onCard(it); editing = false })
    if (editing && kind != "custom") CardEditorDialog(kind, card, onDismiss = { editing = false }, onSave = {
        onCard(it)
        editing = false
    })
    if (generating) AlertDialog(
        onDismissRequest = { generating = false },
        title = { Text("生成一张图片") },
        text = {
            OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                label = { Text("提示词") }, placeholder = { Text("例如：雨后窗边的绿植，简洁构图") },
                minLines = 3, maxLines = 5)
        },
        confirmButton = {
            TextButton(enabled = prompt.isNotBlank(), onClick = {
                generating = false
                onGenerate(prompt.trim())
            }) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = { generating = false }) { Text("取消") } },
    )
    if (found.isNotEmpty() && selectedAddress == null) {
        DevicePicker(found, onSelectDevice)
    }
}

@Composable
private fun DevicePicker(found: List<ScanResult>, onSelect: (ScanResult) -> Unit) {
    // The scan list is also available from Settings; this prompt makes first pairing immediate.
    var dismissed by remember(found) { mutableStateOf(false) }
    if (!dismissed) AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("选择卡片") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                found.forEach { result ->
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: "未知设备"
                    Column(Modifier.fillMaxWidth().clickable {
                        onSelect(result)
                        dismissed = true
                    }.padding(vertical = 10.dp)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text("${result.device.address}  ·  ${result.rssi} dBm", fontSize = 12.sp,
                            color = StudioMuted)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { dismissed = true }) { Text("完成") } },
    )
}

@Composable
private fun CardEditorDialog(
    kind: String,
    initial: Render.Card,
    onDismiss: () -> Unit,
    onSave: (Render.Card) -> Unit,
) {
    val ctx = LocalContext.current
    var quote by remember(kind, initial) { mutableStateOf(initial.quote) }
    var quoteBy by remember(kind, initial) { mutableStateOf(initial.quoteBy) }
    var todos by remember(kind, initial) { mutableStateOf(initial.todos.joinToString("\n")) }
    var countdowns by remember(kind, initial) {
        mutableStateOf(initial.countdowns.joinToString("\n") { "${it.first},${it.second}" })
    }
    var poemTitle by remember(kind, initial) { mutableStateOf(initial.poemTitle) }
    var poemBy by remember(kind, initial) { mutableStateOf(initial.poemBy) }
    var poemLines by remember(kind, initial) { mutableStateOf(initial.poemLines.joinToString("\n")) }
    var poemNote by remember(kind, initial) { mutableStateOf(initial.poemNote) }
    var customTitle by remember(kind, initial) { mutableStateOf(initial.customTitle) }
    var customLines by remember(kind, initial) { mutableStateOf(initial.customLines.joinToString("\n")) }
    var weatherCity by remember(kind, initial) { mutableStateOf(initial.weatherCity) }
    var weatherText by remember(kind, initial) { mutableStateOf(initial.weatherText) }
    var weatherTemp by remember(kind, initial) { mutableStateOf(initial.weatherTemp) }
    var weatherHigh by remember(kind, initial) { mutableStateOf(initial.weatherHigh) }
    var weatherLow by remember(kind, initial) { mutableStateOf(initial.weatherLow) }
    var weatherAqi by remember(kind, initial) { mutableStateOf(initial.weatherAqi) }
    var weatherForecast by remember(kind, initial) {
        mutableStateOf(initial.weatherForecast.joinToString("\n"))
    }
    val scope = rememberCoroutineScope()
    var weatherBusy by remember { mutableStateOf(false) }
    var weatherStatus by remember { mutableStateOf("") }
    var contentBusy by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf<List<WeatherData.Place>>(emptyList()) }
    var weatherLocation by remember(initial) { mutableStateOf(initial) }
    val invalidCountdown = kind == "countdown" && countdowns.lines().any { line ->
        if (line.isBlank()) false else {
            val parts = line.split(",", limit = 2)
            parts.size != 2 || parts[0].isBlank() ||
                !Regex("\\d{4}-\\d{2}-\\d{2}").matches(parts[1].trim())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑${templateName(kind)}", fontSize = 18.sp) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (kind) {
                    "summary", "todo" -> {
                        EditorField("待办事项", todos, { todos = it }, "每行一项", 5)
                        if (kind == "summary") {
                            EditorField("今日一句", quote, { quote = it }, lines = 2)
                            EditorField("署名", quoteBy, { quoteBy = it })
                            RefreshContentButton(contentBusy) {
                                scope.launch {
                                    contentBusy = true
                                    runCatching { CardSources.nextQuote(ctx) }.onSuccess {
                                        quote = it.text; quoteBy = it.author
                                    }.onFailure { weatherStatus = it.message ?: "每日一句获取失败" }
                                    contentBusy = false
                                }
                            }
                            Text("天气资料在天气看板中编辑，两张卡片共用。", fontSize = 12.sp,
                                color = StudioMuted)
                        }
                    }
                    "weather", "weather2" -> {
                        EditorField("城市", weatherCity, { weatherCity = it })
                        Text("默认按网络所在城市获取天气；城市可能受 VPN 影响，可在这里改为指定城市。",
                            fontSize = 12.sp, color = StudioMuted)
                        TextButton(enabled = !weatherBusy, onClick = {
                            scope.launch {
                                weatherBusy = true
                                runCatching { WeatherData.automatic(initial.copy(weatherLatitude = "", weatherLongitude = "")) }.onSuccess { value ->
                                    weatherCity = value.weatherCity; weatherText = value.weatherText
                                    weatherTemp = value.weatherTemp; weatherHigh = value.weatherHigh
                                    weatherLow = value.weatherLow; weatherAqi = value.weatherAqi
                                    weatherForecast = value.weatherForecast.joinToString("\n")
                                    weatherLocation = value; places = emptyList()
                                    weatherStatus = "已恢复自动城市，保存后生效"
                                }.onFailure { weatherStatus = it.message ?: "自动城市获取失败" }
                                weatherBusy = false
                            }
                        }) { Text("使用网络所在城市") }
                        Button(enabled = weatherCity.isNotBlank() && !weatherBusy, onClick = {
                            scope.launch {
                                weatherBusy = true
                                weatherStatus = "正在查找城市…"
                                runCatching { WeatherData.search(weatherCity) }
                                    .onSuccess {
                                        places = it
                                        weatherStatus = if (it.isEmpty()) "没有找到，请换一个城市名" else "选择城市获取实时天气"
                                    }.onFailure { weatherStatus = it.message ?: "查找失败" }
                                weatherBusy = false
                            }
                        }) { Text(if (weatherBusy) "加载中…" else "查找城市并更新天气") }
                        places.forEach { place ->
                            TextButton(enabled = !weatherBusy, onClick = {
                                scope.launch {
                                    weatherBusy = true
                                    weatherStatus = "正在获取天气…"
                                    runCatching { WeatherData.load(place, initial) }
                                        .onSuccess { value ->
                                            weatherCity = value.weatherCity
                                            weatherText = value.weatherText
                                            weatherTemp = value.weatherTemp
                                            weatherHigh = value.weatherHigh
                                            weatherLow = value.weatherLow
                                            weatherAqi = value.weatherAqi
                                            weatherForecast = value.weatherForecast.joinToString("\n")
                                            weatherLocation = value
                                            places = emptyList()
                                            weatherStatus = "天气已更新，保存后应用到卡片"
                                        }.onFailure { weatherStatus = it.message ?: "天气获取失败" }
                                    weatherBusy = false
                                }
                            }) { Text(place.label) }
                        }
                        if (weatherStatus.isNotBlank()) Text(weatherStatus, fontSize = 12.sp,
                            color = StudioMuted)
                        EditorField("天气", weatherText, { weatherText = it }, "例如：多云")
                        EditorField("当前温度", weatherTemp, { weatherTemp = it }, "数字，例如 26")
                        EditorField("最高温度", weatherHigh, { weatherHigh = it })
                        EditorField("最低温度", weatherLow, { weatherLow = it })
                        EditorField("空气 AQI", weatherAqi, { weatherAqi = it })
                        EditorField("未来三天", weatherForecast, { weatherForecast = it },
                            "每行一天，例如：周一 晴 22~28°", 3)
                    }
                    "quote" -> {
                        EditorField("句子", quote, { quote = it }, "写一句想展示的话", 4)
                        EditorField("署名", quoteBy, { quoteBy = it })
                        RefreshContentButton(contentBusy) {
                            scope.launch {
                                contentBusy = true
                                runCatching { ContentData.quote() }.onSuccess {
                                    quote = it.text; quoteBy = it.author
                                }.onFailure { weatherStatus = it.message ?: "每日一句获取失败" }
                                contentBusy = false
                            }
                        }
                    }
                    "countdown" -> {
                        EditorField("纪念日", countdowns, { countdowns = it },
                            "每行：名称,2027-01-01", 5)
                        if (invalidCountdown) Text("请按“名称,年-月-日”填写每一行",
                            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    "poem" -> {
                        EditorField("标题", poemTitle, { poemTitle = it })
                        EditorField("作者", poemBy, { poemBy = it })
                        EditorField("诗句", poemLines, { poemLines = it }, "每行一句", 5)
                        EditorField("赏析", poemNote, { poemNote = it }, "选填", 3)
                        RefreshContentButton(contentBusy) {
                            scope.launch {
                                contentBusy = true
                                runCatching { CardSources.nextPoem(ctx) }.onSuccess {
                                    poemTitle = it.title; poemBy = listOf(it.dynasty, it.author)
                                        .filter { value -> value.isNotBlank() }.joinToString(" · ")
                                    poemLines = it.lines.joinToString("\n"); poemNote = it.note
                                }.onFailure { weatherStatus = it.message ?: "诗词获取失败" }
                                contentBusy = false
                            }
                        }
                    }
                    "custom" -> {
                        EditorField("标题", customTitle, { customTitle = it })
                        EditorField("内容", customLines, { customLines = it }, "每行一段", 6)
                    }
                    else -> Text("这张卡片会根据当前日期自动更新。", color = StudioMuted)
                }
                if (kind !in listOf("weather", "weather2") && weatherStatus.isNotBlank()) Text(weatherStatus,
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(enabled = !invalidCountdown && !weatherBusy && !contentBusy, onClick = {
                onSave(initial.copy(
                    quote = quote.trim(), quoteBy = quoteBy.trim(),
                    todos = todos.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6),
                    countdowns = countdowns.lines().mapNotNull { line ->
                        val parts = line.split(",", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() &&
                            Regex("\\d{4}-\\d{2}-\\d{2}").matches(parts[1].trim()))
                            parts[0].trim() to parts[1].trim() else null
                    }.take(5),
                    poemTitle = poemTitle.trim(), poemBy = poemBy.trim(),
                    poemLines = poemLines.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(80),
                    poemNote = poemNote.trim(), customTitle = customTitle.trim(),
                    customLines = customLines.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8),
                    weatherCity = weatherCity.trim(), weatherText = weatherText.trim(),
                    weatherTemp = weatherTemp.trim(), weatherHigh = weatherHigh.trim(),
                    weatherLow = weatherLow.trim(), weatherAqi = weatherAqi.trim(),
                    weatherForecast = weatherForecast.lines().map { it.trim() }
                        .filter { it.isNotEmpty() }.take(3),
                    weatherLatitude = weatherLocation.weatherLatitude,
                    weatherLongitude = weatherLocation.weatherLongitude,
                    weatherUpdated = weatherLocation.weatherUpdated,
                    weatherAuto = weatherLocation.weatherAuto,
                    weatherHumidity = weatherLocation.weatherHumidity,
                    weatherFeels = weatherLocation.weatherFeels,
                    weatherSunrise = weatherLocation.weatherSunrise,
                    weatherSunset = weatherLocation.weatherSunset,
                    weatherCode = weatherLocation.weatherCode,
                    weatherHours = weatherLocation.weatherHours,
                ))
            }, shape = RoundedCornerShape(8.dp)) { Text("保存预览") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RefreshContentButton(busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, shape = RoundedCornerShape(8.dp)) {
        Text(if (busy) "获取中…" else "从 API 换一条")
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    lines: Int = 1,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = if (hint.isEmpty()) null else { { Text(hint) } },
        modifier = Modifier.fillMaxWidth(), minLines = lines, maxLines = lines,
        shape = RoundedCornerShape(8.dp))
}

@Composable
internal fun LayoutSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label · "+"%.0f%%".format(value * 100), fontSize = 12.sp, color = StudioMuted)
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}
