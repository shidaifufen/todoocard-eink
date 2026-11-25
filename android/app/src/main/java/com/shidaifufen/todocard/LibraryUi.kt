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
    onPick: () -> Unit,
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
    val band = remember(photoPath, fields, locationRevision) {
        if (photoPath.isBlank()) 0 else PhotoCard.bandHeight(photoPath, fields)
    }
    val currentEdit by rememberUpdatedState(edit)
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentSource by rememberUpdatedState(source)
    val currentBand by rememberUpdatedState(band)
    val infoBand by produceState<Bitmap?>(null, photoPath, fields, band, locationRevision) {
        value = if (band == 0) null else withContext(Dispatchers.Default) {
            Bitmap.createBitmap(Render.W, Render.H, Bitmap.Config.ARGB_8888).also {
                PhotoCard.drawBand(android.graphics.Canvas(it), photoPath, fields, band)
            }
        }
    }
    var tool by remember { mutableIntStateOf(0) }
    var compare by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    val snapPreview by produceState<Bitmap?>(null, source, edit, band, compare) {
        value = null
        val bitmap = source
        if (bitmap != null && edit.snapThreshold > 0 && !compare) {
            delay(160)
            value = withContext(Dispatchers.Default) { PhotoEdit.renderBitmap(bitmap, edit, band) }
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
            Text("在画面上拖动调整位置，双指缩放；也可用下方按钮微调",
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
                            val cover = maxOf(w / src.width, h / src.height)
                            val oldW = src.width * cover * params.zoom
                            val oldH = src.height * cover * params.zoom
                            val newW = src.width * cover * nextZoom
                            val newH = src.height * cover * nextZoom
                            val ratio = nextZoom / params.zoom
                            val oldX = params.dx * (oldW - w) / 2
                            val oldY = params.dy * (oldH - h) / 2
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
                                val shown = if (compare) edit.copy(hue = 0f, exposure = 0f,
                                    saturation = 1f, contrast = 1f) else edit
                                val snapped = snapPreview
                                if (snapped != null && !compare) native.drawBitmap(snapped, 0f, 0f, null)
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
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("裁切", "调色", "信息带").forEachIndexed { index, title ->
                    FilterChip(tool == index, onClick = { tool = index }, label = { Text(title) },
                        shape = RoundedCornerShape(8.dp))
                }
            }
            if (edit.snapThreshold > 0 && !compare) Text(
                if (snapPreview == null) "调整中，停下后更新杂色吸附预览" else "杂色吸附预览已更新",
                color = StudioMuted, fontSize = 12.sp)
            if (tool == 0) {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onEdit(edit.copy(zoom = (edit.zoom - .15f).coerceAtLeast(1f))) }) {
                    Text("－")
                }
                Text("${"%.1f".format(edit.zoom)}×", fontSize = 13.sp)
                OutlinedButton(onClick = { onEdit(edit.copy(zoom = (edit.zoom + .15f).coerceAtMost(4f))) }) {
                    Text("＋")
                }
                TextButton(onClick = { onEdit(edit.copy(zoom = 1f, dx = 0f, dy = 0f)) }) {
                    Text("居中")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示构图网格", Modifier.weight(1f), fontSize = 13.sp)
                Switch(showGrid, onCheckedChange = { showGrid = it })
            }
            Adjustment("缩放", edit.zoom, 1f..4f, { onEdit(edit.copy(zoom = it)) }, "%.1f×")
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
            Text("备注优先显示；留空时显示照片中的 GPS 坐标。系统选择器可能移除 GPS。",
                fontSize = 12.sp, color = StudioMuted)
            }
            if (tool == 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("自然", "明亮", "黑白").forEachIndexed { index, label ->
                    OutlinedButton(onClick = { onEdit(edit.copy(hue = 0f, contrast = 1f,
                        saturation = if (index == 2) 0f else 1f,
                        exposure = if (index == 1) .35f else 0f)) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp),
                        shape = RoundedCornerShape(8.dp)) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("对比原图色彩", Modifier.weight(1f), fontSize = 13.sp)
                Switch(compare, onCheckedChange = { compare = it })
            }
            Adjustment("曝光", edit.exposure, -2f..2f, { onEdit(edit.copy(exposure = it)) }, "%+.1f")
            Adjustment("饱和", edit.saturation, 0f..2f, { onEdit(edit.copy(saturation = it)) }, "%.1f")
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
            Adjustment("对比", edit.contrast, 0.6f..1.6f, { onEdit(edit.copy(contrast = it)) }, "%.1f")
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
private fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                       onChange: (Float) -> Unit, format: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(44.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.weight(1f))
        Text(String.format(java.util.Locale.CHINA, format, value), fontSize = 12.sp,
            color = StudioMuted, modifier = Modifier.width(52.dp))
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
