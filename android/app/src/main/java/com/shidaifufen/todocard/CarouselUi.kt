package com.shidaifufen.todocard

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
internal fun CarouselScreen(
    photos: List<Uri>,
    selected: Uri?,
    busy: Boolean,
    onAddPhotos: () -> Unit,
    onDraw: () -> Unit,
    onSelect: (Uri) -> Unit,
    onUse: () -> Unit,
    onSend: () -> Unit,
    onRemove: () -> Unit,
) {
    var previewFraction by rememberSaveable { mutableFloatStateOf(.43f) }
    var lastExpanded by rememberSaveable { mutableFloatStateOf(.43f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val availablePreview = (maxHeight - 330.dp).coerceAtLeast(80.dp)
    Column(Modifier.fillMaxSize().background(StudioBackground).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("照片轮播", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("从系统相册选照片，抽一张放上墨水屏", fontSize = 12.sp, color = StudioMuted)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDraw, enabled = photos.isNotEmpty() && !busy,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Outlined.Casino, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("抽一张")
            }
            OutlinedButton(onClick = onAddPhotos, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("添加照片")
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预览", Modifier.weight(1f), fontSize = 12.sp)
            TextButton(onClick = { previewFraction = if (previewFraction > 0f) 0f else lastExpanded }) { Text(if (previewFraction > 0f) "隐藏大图" else "展开大图") }
        }
        Slider(previewFraction, { previewFraction = it; if (it > .05f) lastExpanded = it }, valueRange = 0f..1f)
        if (previewFraction > .01f) Box(Modifier.fillMaxWidth().height(availablePreview * previewFraction).background(Color.White, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            if (selected == null) Text("还没有照片\n从系统相册添加一些吧", color = StudioMuted)
            else AsyncImage(model = selected, contentDescription = "当前抽中的照片",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (selected != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUse, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)) { Text("编辑这张") }
                Button(onClick = onSend, enabled = !busy, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)) { Text("推送这张") }
            }
            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("从轮播中移除当前照片", color = StudioRed)
            }
        }
        Text("图库 · ${photos.size} 张", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(photos.chunked(4)) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { uri ->
                        AsyncImage(model = uri, contentDescription = "选择轮播照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(1f)
                                .background(Color.White, RoundedCornerShape(5.dp))
                                .clickable { previewFraction = lastExpanded.coerceAtLeast(.65f); onSelect(uri) })
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

}
