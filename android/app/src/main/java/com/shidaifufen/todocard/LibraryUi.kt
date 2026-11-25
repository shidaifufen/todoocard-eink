package com.shidaifufen.todocard

import android.bluetooth.le.ScanResult
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryScreen(
    photos: List<Uri>,
    photoPath: String,
    fields: PhotoCard.Fields,
    edit: PhotoEdit.Params,
    photoLocationRevision: Int,
    onPick: () -> Unit,
    onPickOriginal: () -> Unit,
    onCamera: () -> Unit,
    onAddAlbum: () -> Unit,
    onAddPhotos: () -> Unit,
    onAlbumPhoto: (Uri) -> Unit,
    onFields: (PhotoCard.Fields) -> Unit,
    onLocationSaved: () -> Unit,
    onEdit: (PhotoEdit.Params) -> Unit,
    onUse: () -> Unit,
) {
    val source by produceState<Bitmap?>(null, photoPath) {
        value = if (photoPath.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { PhotoEdit.decodePreview(photoPath) }.getOrNull()
        }
    }
    var locationRevision by remember { mutableIntStateOf(0) }
    var locationNote by remember(photoPath) { mutableStateOf(if (photoPath.isBlank()) "" else PhotoCard.locationNote(photoPath)) }
    val band = remember(photoPath, fields, locationRevision, photoLocationRevision) {
        if (photoPath.isBlank()) 0 else PhotoCard.bandHeight(photoPath, fields)
    }
    val currentEdit by rememberUpdatedState(edit)
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentSource by rememberUpdatedState(source)
    val currentBand by rememberUpdatedState(band)
    val infoBand by produceState<Bitmap?>(null, photoPath, fields, band, locationRevision, photoLocationRevision) {
        value = if (band == 0) null else withContext(Dispatchers.Default) {
            Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888).also {
                PhotoCard.drawBand(android.graphics.Canvas(it), photoPath, fields, band)
            }
        }
    }
    var tool by remember { mutableIntStateOf(0) }
    var sixColors by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    val panelPreview by produceState<Bitmap?>(null, source, edit, band, sixColors) {
        value = null
        val bitmap = source
        if (bitmap != null && sixColors) {
            delay(160)
            value = withContext(Dispatchers.Default) {
                val adjusted = PhotoEdit.renderBitmap(bitmap, edit, band)
                try { PhotoEdit.panelPreview(adjusted) } finally { adjusted.recycle() }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .background(StudioBackground).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("相册", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("选一张照片，调整画面后放上卡片", fontSize = 12.sp, color = StudioMuted)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPick, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp)); Text("选照片")
            }
            OutlinedButton(onClick = onCamera, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.CameraAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp)); Text("拍照")
            }
        }
        OutlinedButton(onClick = onAddPhotos, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("从系统相册多选，加入轮播")
        }
        TextButton(onClick = onAddAlbum, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("选择整个系统相册 / 文件夹")
        }
        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("相册照片", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${photos.size} 张可轮播", fontSize = 12.sp, color = StudioMuted)
            }
            Spacer(Modifier.height(8.dp))
            if (photos.isEmpty()) Text("相册里还没有可用照片", fontSize = 12.sp, color = StudioMuted)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos) { uri ->
                    AsyncImage(model = uri, contentDescription = "选择相册照片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(82.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                            .clickable { onAlbumPhoto(uri) })
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (photoPath.isBlank()) {
            Box(Modifier.fillMaxWidth().height(250.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Text("选一张照片开始编辑", color = StudioMuted)
            }
        } else {
            Text("照片编辑", fontWeight = FontWeight.SemiBold)
            Text("拖动调整位置，双指缩放",
                fontSize = 12.sp, color = StudioMuted)
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val width = minOf(maxWidth, 255.dp)
                Box(Modifier.width(width).aspectRatio(2f / 3f)
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .pointerInput(photoPath) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val params = currentEdit
                            val src = currentSource ?: return@detectTransformGestures
                            val nextZoom = (params.zoom * zoom).coerceIn(1f, 4f)
                            val w = size.width.toFloat()
                            val h = size.height * (Render.H - currentBand).toFloat() / Render.H
                            val cover = PhotoEdit.baseScale(w, h, src, params.fit)
                            val oldW = src.width * cover * params.zoom
                            val oldH = src.height * cover * params.zoom
                            val newW = src.width * cover * nextZoom
                            val newH = src.height * cover * nextZoom
                            val ratio = nextZoom / params.zoom
                            val oldX = params.dx * (oldW - w).coerceAtLeast(0f) / 2
                            val oldY = params.dy * (oldH - h).coerceAtLeast(0f) / 2
                            val nextX = (oldX * ratio + (centroid.x - w / 2) * (1 - ratio) + pan.x)
                            val nextY = (oldY * ratio + (centroid.y - h / 2) * (1 - ratio) + pan.y)
                            currentOnEdit(params.copy(
                                zoom = nextZoom,
                                dx = if (newW - w < 1f) 0f else (nextX * 2 / (newW - w)).coerceIn(-1f, 1f),
                                dy = if (newH - h < 1f) 0f else (nextY * 2 / (newH - h)).coerceIn(-1f, 1f),
                            ))
                        }
                    }) {
                    source?.let { bitmap ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                native.save()
                                native.scale(size.width / Render.W, size.height / Render.H)
                                val panel = panelPreview
                                val shown = if (sixColors) edit else PhotoEdit.Params(zoom = edit.zoom,
                                    dx = edit.dx, dy = edit.dy, fit = edit.fit)
                                if (panel != null && sixColors) native.drawBitmap(panel, 0f, 0f, null)
                                else PhotoEdit.drawInto(native, bitmap, shown, band)
                                infoBand?.let { native.drawBitmap(it, 0f, 0f, null) }
                                native.restore()
                            }
                            if (showGrid && tool == 0) {
                                val line = Color.White.copy(alpha = .5f)
                                val bottom = size.height * (Render.H - band) / Render.H
                                for (index in 1..2) {
                                    drawLine(line, androidx.compose.ui.geometry.Offset(size.width * index / 3, 0f),
                                        androidx.compose.ui.geometry.Offset(size.width * index / 3, bottom), 1.dp.toPx())
                                    drawLine(line, androidx.compose.ui.geometry.Offset(0f, bottom * index / 3),
                                        androidx.compose.ui.geometry.Offset(size.width, bottom * index / 3), 1.dp.toPx())
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilterChip(!sixColors, { sixColors = false }, label = { Text("原图色彩") })
                Spacer(Modifier.width(8.dp))
                FilterChip(sixColors, { sixColors = true }, label = { Text("六色预览") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("裁切", "调色", "信息带").forEachIndexed { index, title ->
                    FilterChip(tool == index, onClick = { tool = index }, label = { Text(title) },
                        shape = RoundedCornerShape(8.dp))
                }
            }
            if (sixColors && panelPreview == null && source != null) Text(
                "正在更新六色预览…",
                color = StudioMuted, fontSize = 12.sp)
            if (tool == 0) {
            Adjustment("缩放", edit.zoom * 100f, 100f..400f, { onEdit(edit.copy(zoom = it / 100f)) }, "%.0f%%")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(edit.fit, { onEdit(edit.copy(fit = true, zoom = 1f, dx = 0f, dy = 0f)) }, label = { Text("适应") })
                FilterChip(!edit.fit, { onEdit(edit.copy(fit = false, zoom = 1f, dx = 0f, dy = 0f)) }, label = { Text("铺满") })
                TextButton(onClick = { onEdit(edit.copy(zoom = 1f, dx = 0f, dy = 0f)) }) { Text("居中") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示构图网格", Modifier.weight(1f), fontSize = 13.sp)
                Switch(showGrid, onCheckedChange = { showGrid = it })
            }
            Adjustment("左右", edit.dx, -1f..1f, { onEdit(edit.copy(dx = it)) }, "%+.1f")
            Adjustment("上下", edit.dy, -1f..1f, { onEdit(edit.copy(dy = it)) }, "%+.1f")
            }
            Spacer(Modifier.height(16.dp))
            if (tool == 2) {
            Text("信息带", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("日期", fields.date, { v: Boolean -> fields.copy(date = v) }),
                    Triple("时间", fields.time, { v: Boolean -> fields.copy(time = v) }),
                    Triple("机身", fields.camera, { v: Boolean -> fields.copy(camera = v) }),
                    Triple("参数", fields.params, { v: Boolean -> fields.copy(params = v) }),
                ).forEach { (label, selected, update) ->
                    FilterChip(selected = selected, onClick = { onFields(update(!selected)) },
                        label = { Text(label, fontSize = 12.sp) }, shape = RoundedCornerShape(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示拍摄位置", Modifier.weight(1f))
                Switch(fields.location, onCheckedChange = { onFields(fields.copy(location = it)) })
            }
            OutlinedTextField(value = locationNote, onValueChange = { locationNote = it.take(120) },
                label = { Text("这张照片的地点备注（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { PhotoCard.saveLocationNote(photoPath, locationNote); locationRevision++; onLocationSaved() }) { Text("保存地点") }
            if (fields.location) {
                val detected = remember(photoPath, photoLocationRevision) { PhotoCard.embeddedLocation(photoPath) }
                Text(if (detected.isBlank()) "当前副本里读不到拍摄位置" else "照片位置 · $detected",
                    fontSize = 12.sp, color = StudioMuted)
                if (detected.isBlank()) TextButton(onClick = onPickOriginal) { Text("重新选择原图并读取位置") }
            }
            }
            if (tool == 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("自然", "插画净色", "黑白").forEachIndexed { index, label ->
                    OutlinedButton(onClick = {
                        val neutral = edit.copy(hue = 0f, contrast = 1f, saturation = 1f,
                            exposure = 0f, gamma = 1f, sharpness = 0f, snapThreshold = 0)
                        onEdit(when (index) {
                            1 -> neutral.copy(exposure = .10f, contrast = 1.18f, gamma = 1.05f,
                                saturation = 1.05f, sharpness = .12f, snapThreshold = 45)
                            2 -> neutral.copy(saturation = 0f)
                            else -> neutral
                        })
                        sixColors = true
                    },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp),
                        shape = RoundedCornerShape(8.dp)) { Text(label) }
                }
            }
            PhotoToneAdjustments(edit, onEdit)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("杂色吸附", Modifier.weight(1f), fontSize = 13.sp)
                Switch(edit.snapThreshold > 0, onCheckedChange = {
                    onEdit(edit.copy(snapThreshold = if (it) 60 else 0))
                })
            }
            Text("适合截图和文字图片，清理白底彩点、锐化灰色边缘。照片开启后会减少灰阶层次。新选图片和分享图片沿用此设置。",
                fontSize = 12.sp, color = StudioMuted)
            if (edit.snapThreshold > 0) Adjustment("强度", edit.snapThreshold.toFloat(), 1f..100f,
                { onEdit(edit.copy(snapThreshold = it.toInt())) }, "%.0f")
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "收起更多调整" else "更多调整")
            }
            if (showAdvanced) {
            Adjustment("色相", edit.hue, -180f..180f, { onEdit(edit.copy(hue = it)) }, "%+.0f°")
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEdit(PhotoEdit.Params()) }, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp)); Text("重置")
                }
                Button(onClick = onUse, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)) { Text("使用这张照片") }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun PhotoToneAdjustments(edit: PhotoEdit.Params, onEdit: (PhotoEdit.Params) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Adjustment("亮度", edit.exposure, -2f..2f, { onEdit(edit.copy(exposure = it)) }, "%+.2f EV")
        Adjustment("对比度", edit.contrast * 100f, 60f..160f, { onEdit(edit.copy(contrast = it / 100f)) }, "%.0f%%")
        Adjustment("Gamma", edit.gamma, .5f..2f, { onEdit(edit.copy(gamma = it)) }, "%.2f")
        Adjustment("锐化", edit.sharpness * 100f, 0f..100f, { onEdit(edit.copy(sharpness = it / 100f)) }, "%.0f%%")
        Adjustment("饱和度", edit.saturation * 100f, 0f..200f, { onEdit(edit.copy(saturation = it / 100f)) }, "%.0f%%")
    }
}

@Composable
private fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                       onChange: (Float) -> Unit, format: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text(String.format(java.util.Locale.CHINA, format, value), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
internal fun SettingsScreen(
    pairedName: String,
    pairedAddress: String,
    fields: PhotoCard.Fields,
    found: List<ScanResult>,
    selectedAddress: String?,
    status: String,
    theme: StudioTheme,
    onTheme: (StudioTheme) -> Unit,
    onScan: () -> Unit,
    onSelect: (ScanResult) -> Unit,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onFields: (PhotoCard.Fields) -> Unit,
    onScanAll: () -> Unit,
    scanTimedOut: Boolean,
) {
    var section by remember { mutableStateOf(0) }
    var aiSettings by remember { mutableStateOf(false) }
    val settingsContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(StudioBackground).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("应用主题", fontSize = 13.sp, color = StudioMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioTheme.entries.forEach { option ->
                FilterChip(selected = theme == option, onClick = { onTheme(option) }, label = { Text(option.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("设备", "外观", "关于").forEachIndexed { i, label ->
                FilterChip(selected = section == i, onClick = { section = i },
                    label = { Text(label) }, shape = RoundedCornerShape(8.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        AppUpdateRow()
        Spacer(Modifier.height(12.dp))
        when (section) {
            0 -> {
                Text("我的卡片", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(if (pairedAddress.isEmpty()) "尚未配对设备"
                     else pairedName.ifEmpty { "土豆片卡片" },
                    fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                if (pairedAddress.isNotEmpty()) Text(pairedAddress, fontSize = 12.sp, color = StudioMuted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan, shape = RoundedCornerShape(8.dp)) { Text("扫描附近设备") }
                    if (selectedAddress != null) OutlinedButton(onClick = onPair,
                        shape = RoundedCornerShape(8.dp)) { Text("配对选中设备") }
                }
                if (scanTimedOut) TextButton(onClick = onScanAll) { Text("查找所有设备（不限制 FF:FF）") }
                if (pairedAddress.isNotEmpty()) TextButton(onClick = onUnpair) {
                    Text("解除配对", color = StudioRed)
                }
                if (found.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("扫描结果", fontWeight = FontWeight.SemiBold)
                    found.forEach { result ->
                        val name = result.device.name ?: result.scanRecord?.deviceName ?: "未知设备"
                        Row(Modifier.fillMaxWidth().clickable { onSelect(result) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(result.device.address, fontSize = 12.sp, color = StudioMuted)
                            }
                            Text(if (selectedAddress == result.device.address) "已选" else "${result.rssi} dBm",
                                fontSize = 12.sp, color = StudioGreen)
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(status.lineSequence().lastOrNull().orEmpty(), fontSize = 12.sp,
                    color = StudioMuted)
            }
            1 -> {
                LayoutSettings()
                Spacer(Modifier.height(18.dp))
                Text("六色墨水屏", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("预览尺寸 528 × 792", fontSize = 13.sp, color = StudioMuted,
                    modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Color.White, Color.Black, Color.Red, Color.Blue, Color.Green,
                        Color.Yellow).forEach { color ->
                        Box(Modifier.size(34.dp).background(color, RoundedCornerShape(4.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("照片信息带", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                listOf(
                    Triple("拍摄日期", fields.date, { value: Boolean -> fields.copy(date = value) }),
                    Triple("拍摄时间", fields.time, { value: Boolean -> fields.copy(time = value) }),
                    Triple("相机机身", fields.camera, { value: Boolean -> fields.copy(camera = value) }),
                    Triple("拍摄参数", fields.params, { value: Boolean -> fields.copy(params = value) }),
                    Triple("拍摄位置（照片含 GPS 时）", fields.location, { value: Boolean -> fields.copy(location = value) }),
                ).forEach { (label, enabled, update) ->
                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        Switch(enabled, onCheckedChange = { onFields(update(it)) })
                    }
                }
                Text("位置从照片自身的 GPS 信息读取；系统照片选择器可能会移除定位数据。",
                    fontSize = 12.sp, color = StudioMuted)
            }
            else -> {
                Text("土豆片", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("六色墨水屏卡片工坊", fontSize = 14.sp)
                Text("Android · 528 × 792", fontSize = 12.sp, color = StudioMuted,
                    modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { aiSettings = true }) { Text("配置诗词赏析 / AI 生图接口") }
                TextButton(onClick = {
                    runCatching { settingsContext.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, settingsContext.packageName)) }
                }) { Text("系统通知与实时活动设置") }
                Text("推送会显示查找、连接、进度、显影与结果，结束后约 5 秒自动收起。胶囊样式由系统控制。",
                    fontSize = 12.sp, color = StudioMuted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (aiSettings) AiSettingsDialog { aiSettings = false }
}
