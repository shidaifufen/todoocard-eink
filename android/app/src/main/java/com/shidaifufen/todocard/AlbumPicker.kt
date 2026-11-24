package com.shidaifufen.todocard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AlbumPicker(sources: List<String>, onChoose: (String) -> Unit,
                         onRemove: (String) -> Unit, onGrant: () -> Unit,
                         onFolder: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var albums by remember { mutableStateOf<List<MediaAlbums.Album>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MediaAlbums.list(ctx) } }
            .onSuccess { albums = it }.onFailure { error = it.message ?: "相册读取失败" }
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择整个相册") }, text = {
        Column {
            Text(if (MediaAlbums.limited(ctx)) "当前仅能看到你已授权的照片；允许访问全部照片后可以读取完整目录。"
                else "相册新增照片会自动进入轮播。每个目录最多读取最新 5000 张。")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onGrant) { Text("管理照片访问权限") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                if (loading) item { CircularProgressIndicator() }
                if (!loading && albums.isEmpty()) item { Text("没有可访问的相册，请检查照片权限。") }
                items(albums, key = { it.id }) { album ->
                    val source = MediaAlbums.source(album.id)
                    Row(Modifier.fillMaxWidth().clickable { onChoose(source) }.padding(vertical = 8.dp)) {
                        AsyncImage(model = album.cover, contentDescription = null, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(album.title)
                            Text("${album.count} 张${if (source in sources) " · 已加入轮播" else ""}")
                        }
                        if (source in sources) TextButton(onClick = { onRemove(source) }) { Text("移除") }
                    }
                }
                sources.filter { !it.startsWith("todocard-album:") && it.contains("/tree/") }
                    .forEach { source -> item {
                        Row { Text("文件夹：${android.net.Uri.decode(source.substringAfterLast('/'))}",
                            Modifier.weight(1f)); TextButton(onClick = { onRemove(source) }) { Text("移除") } }
                    } }
            }
            TextButton(onClick = onFolder) { Text("选择普通文件夹（文件管理器）") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
