package com.shidaifufen.todocard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CloudBoardManager(
    boards: List<CloudBoards.Board>,
    onSave: suspend (List<CloudBoards.Board>) -> Unit,
    onSelect: (CloudBoards.Board) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(if (boards.isEmpty()) 1 else 0) }
    var catalog by remember { mutableStateOf<List<LayoutDownload.Scene>?>(null) }
    var catalogError by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<LayoutPack.Pack, String>?>(null) }
    var remove by remember { mutableStateOf<CloudBoards.Board?>(null) }

    fun runAction(action: suspend () -> Unit) {
        if (working) return
        working = true
        error = ""
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "操作失败，请重试"
            } finally {
                working = false
            }
        }
    }

    fun fetch(url: String) = runAction {
        val origin = LayoutDownload.resolve(url).toString()
        preview = withContext(Dispatchers.IO) { LayoutDownload.fetch(origin) } to origin
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            preview = withContext(Dispatchers.IO) {
                val input = ctx.contentResolver.openInputStream(uri) ?: error("无法读取文件")
                LayoutPack.parse(input.use(LayoutDownload::read))
            } to ""
        }
    }

    LaunchedEffect(page, retry) {
        if (page != 1 || catalog != null) return@LaunchedEffect
        catalogError = ""
        try {
            catalog = withContext(Dispatchers.IO) { LayoutDownload.scenes() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            catalogError = failure.message ?: "无法读取云端目录"
        }
    }

    fun move(index: Int, offset: Int) = runAction {
        val updated = boards.toMutableList()
        val item = updated.removeAt(index)
        updated.add(index + offset, item)
        onSave(updated.toList())
    }

    val pending = preview
    if (pending != null) {
        LayoutPreview(pending.first, onDismiss = { if (!working) preview = null }, onApply = { pack ->
            runAction {
                val (updated, selected) = withContext(Dispatchers.Default) {
                    CloudBoards.add(boards, pack, pending.second)
                }
                onSave(updated)
                onSelect(updated.first { it.id == selected })
                onDismiss()
            }
        }, actionLabel = "添加到侧栏", independent = true, saving = working, saveError = error)
        return
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("云端看板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(selectedTabIndex = page) {
                    listOf("我的看板", "官方场景", "导入").forEachIndexed { index, title ->
                        Tab(selected = page == index, enabled = !working, onClick = {
                            page = index
                            error = ""
                        }, text = { Text(title) })
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                when (page) {
                    0 -> if (boards.isEmpty()) {
                        Text("添加一个官方场景，或导入自己的版式。", color = StudioMuted)
                    } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(boards, key = { it.id }) { board ->
                            val index = boards.indexOf(board)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(board.title, Modifier.weight(1f), maxLines = 2)
                                IconButton(enabled = !working && index > 0, onClick = { move(index, -1) }) {
                                    Icon(Icons.Outlined.KeyboardArrowUp, "上移${board.title}")
                                }
                                IconButton(enabled = !working && index < boards.lastIndex, onClick = { move(index, 1) }) {
                                    Icon(Icons.Outlined.KeyboardArrowDown, "下移${board.title}")
                                }
                                IconButton(enabled = !working, onClick = { remove = board }) {
                                    Icon(Icons.Outlined.DeleteOutline, "移除${board.title}")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    1 -> when {
                        catalogError.isNotBlank() -> {
                            Text(catalogError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { retry++ }) { Text("重新加载") }
                        }
                        catalog == null -> Text("正在获取官方场景…", color = StudioMuted)
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                            catalog.orEmpty().groupBy { it.group }.forEach { (group, scenes) ->
                                if (group.isNotBlank()) item(key = "group-$group") {
                                    Text(group, style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                }
                                items(scenes, key = { it.url }) { scene ->
                                    val added = boards.any { it.origin == scene.url }
                                    ListItem(
                                        headlineContent = { Text(scene.title) },
                                        supportingContent = { Text(scene.description) },
                                        trailingContent = { Text(if (added) "已添加" else "预览", color = StudioGreen) },
                                        modifier = Modifier.clickable(enabled = !working && !added) { fetch(scene.url) },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(value = link, onValueChange = { link = it; error = "" },
                            label = { Text("GitHub JSON 文件链接") }, enabled = !working,
                            maxLines = 3, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { fetch(link.trim()) }, enabled = !working && link.isNotBlank()) {
                                Text("获取并预览")
                            }
                            TextButton(onClick = {
                                filePicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                            }, enabled = !working) { Text("导入文件") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("完成") } },
    )

    remove?.let { board ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除「${board.title}」？") },
            confirmButton = { TextButton(onClick = {
                remove = null
                runAction { onSave(boards.filterNot { it.id == board.id }) }
            }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } })
    }
}
