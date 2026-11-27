package com.shidaifufen.todocard

import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    var fraction by rememberSaveable { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    var animation by remember { mutableStateOf<Job?>(null) }
    fun showPreview(show: Boolean) {
        animation?.cancel()
        animation = scope.launch {
            animate(fraction, if (show) 1f else 0f, animationSpec = tween(240)) { value, _ ->
                fraction = value
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(StudioBackground)) {
        val previewHeight = (maxHeight * .41f).coerceIn(100.dp, 400.dp)
        val density = LocalDensity.current
        val collapseDistance = with(density) { (previewHeight + 104.dp).toPx() }
        val previewWidthPx = with(density) { (maxWidth - 40.dp).roundToPx().coerceAtLeast(1) }
        val previewHeightPx = with(density) { previewHeight.roundToPx() }
        val connection = remember(collapseDistance) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                    animation?.cancel()
                    val distance = minOf(abs(available.y), fraction * collapseDistance)
                    fraction = (fraction - distance / collapseDistance).coerceAtLeast(0f)
                    return Offset(0f, if (available.y < 0f) -distance else distance)
                }
            }
        }
        Column(Modifier.fillMaxSize().nestedScroll(connection).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("照片轮播", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDraw(); showPreview(true) },
                    enabled = photos.isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.Casino, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("抽一张")
                }
                OutlinedButton(onClick = onAddPhotos, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加照片")
                }
            }
            if (selected != null && fraction > 0f) {
                Spacer(Modifier.height(8.dp * fraction))
                val previewShape = RoundedCornerShape(12.dp)
                val ctx = LocalContext.current
                val request = remember(ctx, selected, previewWidthPx, previewHeightPx) {
                    ImageRequest.Builder(ctx).data(selected).size(previewWidthPx, previewHeightPx).build()
                }
                AsyncImage(model = request, contentDescription = "当前选中的轮播照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(previewHeight * fraction).clip(previewShape)
                        .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
                        .background(MaterialTheme.colorScheme.surface).padding(6.dp * fraction))
                val actions = ((fraction - .65f) / .35f).coerceIn(0f, 1f)
                if (actions > 0f) {
                    Box(Modifier.fillMaxWidth().height(100.dp * actions).clip(RoundedCornerShape(0.dp))) {
                        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                            .graphicsLayer { alpha = actions }) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onUse, modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)) { Text("编辑这张") }
                                Button(onClick = onSend, enabled = !busy, modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)) { Text("推送这张") }
                            }
                            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                                Text("从轮播中移除", color = StudioRed)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("图库 · ${photos.size} 张", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (selected != null) TextButton(onClick = { showPreview(fraction < .5f) }) {
                    Text(if (fraction < .5f) "展开预览" else "收起预览")
                }
            }
            if (photos.isEmpty()) {
                Text("添加照片后即可抽取或推送", color = StudioMuted, fontSize = 13.sp)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(photos, key = Uri::toString) { uri ->
                        val isSelected = uri == selected
                        val shape = RoundedCornerShape(6.dp)
                        AsyncImage(model = uri, contentDescription = "选择轮播照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(1f).clip(shape)
                                .background(MaterialTheme.colorScheme.surface)
                                .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                                    else Modifier)
                                .semantics { this.selected = isSelected }
                                .clickable { onSelect(uri); showPreview(true) })
                    }
                }
            }
        }
    }
}
