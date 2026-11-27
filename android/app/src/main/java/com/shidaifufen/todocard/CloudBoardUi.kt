package com.shidaifufen.todocard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CloudBoardManager(
    boards: List<CloudBoards.Board>,
    onSave: suspend (List<CloudBoards.Board>) -> Unit,
    onSort: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(if (boards.isEmpty()) 1 else 0) }
    val catalogScroll = rememberLazyListState()
    val mineScroll = rememberLazyListState()
    val categoryScroll = rememberScrollState()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<LayoutDownload.Scene>?>(null) }
    var catalogError by remember { mutableStateOf("") }
    val previewCache = remember { mutableMapOf<String, LayoutPack.Pack>() }
    var retry by remember { mutableIntStateOf(0) }
    var link by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<LayoutPack.Pack, String>?>(null) }
    var remove by remember { mutableStateOf<CloudBoards.Board?>(null) }

    fun runAction(action: suspend () -> Unit) {
        if (working) return
        working = true
        error = ""
        confirmation = ""
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
        val pack = previewCache[origin] ?: withContext(Dispatchers.IO) { LayoutDownload.fetch(origin) }
        if (catalog.orEmpty().any { it.url == origin }) previewCache[origin] = pack
        preview = pack to origin
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

    val pending = preview
    if (pending != null) {
        LayoutPreview(pending.first, onDismiss = { if (!working) preview = null }, onApply = { pack ->
            runAction {
                val updated = withContext(Dispatchers.Default) {
                    CloudBoards.add(boards, pack, pending.second).first
                }
                onSave(updated)
                preview = null
                confirmation = "已添加到侧栏"
            }
        }, actionLabel = "添加到侧栏", independent = true, saving = working, saveError = error)
        return
    }

    Dialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 14.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("云端看板", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (page == 0 && boards.isNotEmpty()) {
                        TextButton(enabled = !working, onClick = onSort) { Text("排序") }
                    }
                    TextButton(enabled = !working, onClick = onDismiss) { Text("完成") }
                }
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
                if (confirmation.isNotBlank()) Text(confirmation, color = StudioGreen)
                when (page) {
                    0 -> if (boards.isEmpty()) {
                        Text("添加一个官方场景，或导入自己的版式。", color = StudioMuted)
                    } else LazyColumn(Modifier.fillMaxWidth().weight(1f), state = mineScroll) {
                        items(boards, key = { it.id }) { board ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(board.title, Modifier.weight(1f), maxLines = 2)
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
                        else -> {
                            val groups = catalog.orEmpty().map { it.group }.filter { it.isNotBlank() }.distinct()
                            val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            val matches = catalog.orEmpty().filter { scene ->
                                (category.isBlank() || scene.group == category) && words.all {
                                    scene.searchText.contains(it, ignoreCase = true)
                                }
                            }
                            OutlinedTextField(query, {
                                query = it
                                scope.launch { catalogScroll.scrollToItem(0) }
                            }, label = { Text("搜索看板，如工牌、历史、天气") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            Row(Modifier.horizontalScroll(categoryScroll),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (listOf("") + groups).forEach { group ->
                                    FilterChip(selected = category == group, onClick = {
                                        category = group
                                        scope.launch { catalogScroll.scrollToItem(0) }
                                    }, label = { Text(group.ifBlank { "全部" }) })
                                }
                            }
                            if (matches.isEmpty()) Text("没有匹配的看板", color = StudioMuted)
                            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = catalogScroll) {
                                matches.groupBy { it.group }.forEach { (group, scenes) ->
                                    if (group.isNotBlank()) item(key = "group-$group") {
                                        Text(group, style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                    }
                                    items(scenes, key = { it.url }) { scene ->
                                        val added = boards.any { it.origin == scene.url }
                                        ListItem(
                                            headlineContent = { Text(scene.title) },
                                            supportingContent = {
                                                Text(scene.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            },
                                            trailingContent = { Text(if (added) "已添加" else "预览", color = StudioGreen) },
                                            modifier = Modifier.clickable(enabled = !working) { fetch(scene.url) },
                                        )
                                        HorizontalDivider()
                                    }
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
        }
    }

    remove?.let { board ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除「${board.title}」？") },
            confirmButton = { TextButton(onClick = {
                remove = null
                runAction { onSave(boards.filterNot { it.id == board.id }) }
            }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } })
    }
}
