package com.shidaifufen.todocard

import android.bluetooth.le.ScanResult
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal val StudioInk = Color(0xFF252A2D)
internal val StudioMuted = Color(0xFF68737B)
internal val StudioGreen = Color(0xFF096B61)
internal val StudioRed = Color(0xFFC84B3B)
internal val StudioBackground = Color(0xFFF5F6F7)

internal val StudioColors = lightColorScheme(
    primary = StudioGreen,
    onPrimary = Color.White,
    secondary = StudioRed,
    background = StudioBackground,
    onBackground = StudioInk,
    surface = Color.White,
    onSurface = StudioInk,
    surfaceVariant = Color(0xFFE8ECEE),
    onSurfaceVariant = StudioMuted,
)

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
    onPhoto: () -> Unit,
    onScan: () -> Unit,
    onSelectDevice: (ScanResult) -> Unit,
    onSend: () -> Unit,
    carouselCount: Int,
    onDraw: () -> Unit,
    onAddPhotos: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(StudioBackground)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("卡片工坊", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = StudioInk)
            Spacer(Modifier.height(3.dp))
            Text("${templateName(kind)}  ·  528 × 792", fontSize = 12.sp, color = StudioMuted)
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TEMPLATES) { (key, title) ->
                FilterChip(
                    selected = kind == key,
                    onClick = { onKind(key) },
                    enabled = !busy,
                    label = { Text(title, maxLines = 1) },
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (kind == "photo") Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("轮播图库 · $carouselCount 张", color = StudioMuted, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onAddPhotos, enabled = !busy) { Text("添加") }
            FilledTonalButton(onClick = onDraw, enabled = carouselCount > 0 && !busy,
                shape = RoundedCornerShape(8.dp)) { Text("抽一张") }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val imageWidth = minOf(maxWidth - 20.dp, (maxHeight - 12.dp) * 2f / 3f)
            Box(
                Modifier.width(imageWidth).aspectRatio(2f / 3f)
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFD9DED8), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (kind == "photo" && !photoReady) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(32.dp), tint = StudioMuted)
                        Spacer(Modifier.height(10.dp))
                        Text("先选择一张照片", color = StudioMuted, fontSize = 13.sp)
                        TextButton(onClick = onPhoto) { Text("打开相册") }
                    }
                } else if (preview == null) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                else Image(preview.asImageBitmap(), "卡片预览", Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("屏幕预览", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (kind !in listOf("progress", "calendar")) TextButton(enabled = !busy, onClick = {
                    if (kind == "photo") onPhoto() else editing = true
                }) {
                    Icon(if (kind == "photo") Icons.Outlined.PhotoLibrary else Icons.Outlined.Edit,
                        null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (kind == "photo") "编辑照片" else "编辑内容")
                }
            }
            Text(
                if (deviceName.isBlank()) "尚未连接卡片" else "目标设备 · $deviceName",
                fontSize = 12.sp, color = StudioMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(status.lineSequence().lastOrNull().orEmpty(), fontSize = 12.sp,
                color = if (status.startsWith("失败")) StudioRed else StudioMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress in 1..99) {
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
                Button(onClick = onSend, enabled = preview != null && !busy,
                    modifier = Modifier.weight(1.2f).height(48.dp), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "推送中" else "推送到卡片")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (editing) CardEditorDialog(kind, card, onDismiss = { editing = false }, onSave = {
        onCard(it)
        editing = false
    })
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
                            Text("天气资料在天气看板中编辑，两张卡片共用。", fontSize = 12.sp,
                                color = StudioMuted)
                        }
                    }
                    "weather" -> {
                        EditorField("城市", weatherCity, { weatherCity = it })
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
                    }
                    "custom" -> {
                        EditorField("标题", customTitle, { customTitle = it })
                        EditorField("内容", customLines, { customLines = it }, "每行一段", 6)
                    }
                    else -> Text("这张卡片会根据当前日期自动更新。", color = StudioMuted)
                }
            }
        },
        confirmButton = {
            Button(enabled = !invalidCountdown && !weatherBusy, onClick = {
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
                    poemLines = poemLines.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6),
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
                ))
            }, shape = RoundedCornerShape(8.dp)) { Text("保存预览") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
