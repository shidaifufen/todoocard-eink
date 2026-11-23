package com.shidaifufen.todocard

import android.bluetooth.le.ScanResult
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryScreen(
    albums: List<String>,
    photoPath: String,
    fields: PhotoCard.Fields,
    edit: PhotoEdit.Params,
    onPick: () -> Unit,
    onCamera: () -> Unit,
    onAddAlbum: () -> Unit,
    onAlbumPhoto: (Uri) -> Unit,
    onFields: (PhotoCard.Fields) -> Unit,
    onEdit: (PhotoEdit.Params) -> Unit,
    onUse: () -> Unit,
) {
    val ctx = LocalContext.current
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    LaunchedEffect(albums) {
        photos = withContext(Dispatchers.IO) { AlbumStore.listImages(ctx, albums) }
    }
    val photoPreview by produceState<Bitmap?>(null, photoPath, fields, edit) {
        value = if (photoPath.isBlank()) null else withContext(Dispatchers.Default) {
            runCatching { PhotoCard.render(photoPath, fields, edit) }.getOrNull()
        }
    }
    val currentEdit by rememberUpdatedState(edit)
    val currentOnEdit by rememberUpdatedState(onEdit)
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
        OutlinedButton(onClick = onAddAlbum, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("添加轮播相册")
        }
        if (albums.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("相册照片", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${albums.size} 个相册 · ${photos.size} 张", fontSize = 12.sp, color = StudioMuted)
            }
            Spacer(Modifier.height(8.dp))
            if (photos.isEmpty()) Text("相册里还没有可用照片", fontSize = 12.sp, color = StudioMuted)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos) { uri ->
                    AsyncImage(model = uri, contentDescription = "选择相册照片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(82.dp).background(Color.White, RoundedCornerShape(4.dp))
                            .clickable { onAlbumPhoto(uri) })
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (photoPath.isBlank()) {
            Box(Modifier.fillMaxWidth().height(250.dp)
                .border(1.dp, Color(0xFFD9DED8), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Text("选一张照片开始编辑", color = StudioMuted)
            }
        } else {
            Text("照片编辑", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val width = minOf(maxWidth, 255.dp)
                Box(Modifier.width(width).aspectRatio(2f / 3f)
                    .background(Color.White)
                    .border(1.dp, Color(0xFFD9DED8))
                    .pointerInput(photoPath) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val params = currentEdit
                            val nextZoom = (params.zoom * zoom).coerceIn(1f, 4f)
                            currentOnEdit(params.copy(
                                zoom = nextZoom,
                                dx = (params.dx + pan.x / size.width * 2f).coerceIn(-1f, 1f),
                                dy = (params.dy + pan.y / size.height * 2f).coerceIn(-1f, 1f),
                            ))
                        }
                    }) {
                    photoPreview?.let {
                        Image(it.asImageBitmap(), "照片卡片预览", Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
            Adjustment("缩放", edit.zoom, 1f..4f, { onEdit(edit.copy(zoom = it)) }, "%.1f×")
            Adjustment("曝光", edit.exposure, -2f..2f, { onEdit(edit.copy(exposure = it)) }, "%+.1f")
            Adjustment("对比", edit.contrast, 0.6f..1.6f, { onEdit(edit.copy(contrast = it)) }, "%.1f")
            Adjustment("饱和", edit.saturation, 0f..2f, { onEdit(edit.copy(saturation = it)) }, "%.1f")
            Adjustment("色相", edit.hue, -180f..180f, { onEdit(edit.copy(hue = it)) }, "%+.0f°")
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
    onScan: () -> Unit,
    onSelect: (ScanResult) -> Unit,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onFields: (PhotoCard.Fields) -> Unit,
) {
    var section by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(StudioBackground).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
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
                Text("六色墨水屏", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("预览尺寸 528 × 792", fontSize = 13.sp, color = StudioMuted,
                    modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Color.White, Color.Black, Color.Red, Color.Blue, Color.Green,
                        Color.Yellow).forEach { color ->
                        Box(Modifier.size(34.dp).background(color, RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFFCED5CE), RoundedCornerShape(4.dp)))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("照片信息带", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                listOf(
                    Triple("拍摄日期", fields.date, { value: Boolean -> fields.copy(date = value) }),
                    Triple("拍摄时间", fields.time, { value: Boolean -> fields.copy(time = value) }),
                    Triple("相机机身", fields.camera, { value: Boolean -> fields.copy(camera = value) }),
                    Triple("拍摄参数", fields.params, { value: Boolean -> fields.copy(params = value) }),
                ).forEach { (label, enabled, update) ->
                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        Switch(enabled, onCheckedChange = { onFields(update(it)) })
                    }
                }
            }
            else -> {
                Text("土豆片", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("六色墨水屏卡片工坊", fontSize = 14.sp)
                Text("Android · 528 × 792", fontSize = 12.sp, color = StudioMuted,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
