package com.shidaifufen.todocard

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LayoutSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by LayoutStore.revision.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<LayoutPack.Pack?>(null) }
    var reset by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val linkPrefs = remember(ctx) { ctx.getSharedPreferences("layout_links_v1", 0) }
    var linkDialog by remember { mutableStateOf(false) }
    var link by remember {
        val saved = linkPrefs.getString("last", null)
        val oldDefault = "https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/default.json"
        mutableStateOf(saved?.takeUnless { it == oldDefault } ?: LayoutDownload.DEFAULT_URL)
    }
    var linkError by remember { mutableStateOf("") }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; message = "正在读取版式包…"
            try {
                pending = withContext(Dispatchers.IO) {
                    val raw = ctx.contentResolver.openInputStream(uri)?.use(LayoutDownload::read) ?: error("无法读取文件")
                    LayoutPack.parse(raw)
                }
                message = "校验通过，请预览后应用"
            } catch (e: Exception) { message = "导入失败：${e.message}" }
            finally { busy = false }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val output = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入文件")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(exportText) }
                }
                message = "版式已导出"
            } catch (e: Exception) { message = "导出失败：${e.message}" }
            finally { busy = false }
        }
    }
    fun perform(action: () -> Unit) {
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { action() }; message = "版式已更新，下次预览与推送立即使用" }
            catch (e: Exception) { message = "操作失败：${e.message}" }
            finally { busy = false }
        }
    }
    // Reading revision makes the status update when a pack is applied from this dialog.
    val packName = remember(revision) { LayoutStore.name() }
    val changed = remember(revision) { LayoutStore.changedKinds() }
    SectionCard(title = "看板版式包") {
        Text(packName, fontSize = 15.sp)
        Text(if (changed.isEmpty()) "正在使用内置版式" else "已覆盖：${changed.joinToString { templateName(it) }}", fontSize = 12.sp, color = StudioMuted)
        Text("导入 JSON 调整排版，内容、相册、设备和计划单独保存。只替换包内列出的卡片。", fontSize = 12.sp, color = StudioMuted)
        OutlinedButton(enabled = !busy, onClick = { linkError = ""; linkDialog = true }) { Text("从 GitHub 导入 / 更新") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("导入版式") }
            OutlinedButton(enabled = !busy, onClick = {
                exportText = LayoutStore.export(ctx)
                exportFile.launch("todocard-layouts.json")
            }) { Text("导出当前") }
        }
        Row {
            TextButton(enabled = !busy, onClick = {
                exportText = LayoutStore.export(ctx, defaultsOnly = true)
                exportFile.launch("todocard-default-layouts.json")
            }) { Text("导出默认") }
            TextButton(enabled = !busy && LayoutStore.canUndo(ctx), onClick = { perform { LayoutStore.undo(ctx) } }) { Text("撤销上次更改") }
        }
        TextButton(enabled = !busy && changed.isNotEmpty(), onClick = { reset = true }) { Text("恢复内置版式") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, fontSize = 12.sp)
    }
    pending?.let { pack -> LayoutPreview(pack,
        onDismiss = { pending = null }, onApply = { edited ->
            pending = null
            perform { LayoutStore.apply(ctx, edited) }
        }) }
    if (linkDialog) AlertDialog(onDismissRequest = { if (!busy) linkDialog = false },
        title = { Text("从 GitHub 获取版式") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("粘贴公开仓库的 JSON 文件链接或 Release 附件链接。每次获取最新文件，预览后再应用；上次链接会保留。", fontSize = 12.sp)
                OutlinedTextField(value = link, onValueChange = { link = it; linkError = "" }, enabled = !busy,
                    label = { Text("GitHub 链接") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    isError = linkError.isNotEmpty())
                Text("仓库已有版式", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("六色场景精选" to "featured", "晨间简报" to "daily-brief", "出门天气" to "weather-field", "天气色卡" to "weather-tiles",
                        "专注清单" to "focus-list", "诗词日历" to "verse-almanac", "蓝色天气" to "weather-blue",
                        "一句＋天气" to "quote-weather", "默认版式" to "default").forEach { (name, file) ->
                        TextButton(enabled = !busy, onClick = {
                            link = "https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/$file.json"; linkError = ""
                        }) { Text(name) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (linkError.isNotEmpty()) Text(linkError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = !busy && link.isNotBlank(), onClick = {
            val requested = link.trim()
            scope.launch {
                busy = true; linkError = ""; message = "正在从 GitHub 获取版式…"
                try {
                    val pack = withContext(Dispatchers.IO) { LayoutDownload.fetch(requested) }
                    linkPrefs.edit().putString("last", requested).apply()
                    linkDialog = false; pending = pack; message = "下载并校验通过，请预览后应用"
                } catch (e: Exception) {
                    linkError = "获取失败：${e.message ?: "请检查网络连接"}"
                    message = "获取失败，当前版式继续使用"
                } finally { busy = false }
            }
        }) { Text("获取并预览") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { linkDialog = false }) { Text("取消") } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复内置版式") },
        text = { Text("恢复全部看板的内置排版。可通过“撤销上次更改”返回当前版式。") },
        confirmButton = { TextButton(onClick = { reset = false; perform { LayoutStore.reset(ctx) } }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}

@Composable
private fun LayoutPreview(pack: LayoutPack.Pack, onDismiss: () -> Unit, onApply: (LayoutPack.Pack) -> Unit) {
    val ctx = LocalContext.current
    var kind by remember(pack) { mutableStateOf(pack.templates.keys.first()) }
    var edited by remember(pack) { mutableStateOf(pack) }
    val optionState = rememberSaveableStateHolder()
    var preview by remember(edited, kind) { mutableStateOf<Bitmap?>(null) }
    var error by remember(edited, kind) { mutableStateOf("") }
    var ready by remember(edited, kind) { mutableStateOf(false) }
    LaunchedEffect(edited, kind) {
        ready = false; error = ""
        val result = withContext(Dispatchers.Default) {
            runCatching {
                // Validate rendering for every changed card, not only the selected preview.
                val card = PlanStore.card(ctx)
                edited.templates.filterKeys { it != kind }.forEach { (k, t) -> LayoutRender.render(card.copy(kind = k), t).recycle() }
                LayoutRender.render(card.copy(kind = kind), edited.templates.getValue(kind))
            }
        }
        preview = result.getOrNull()
        error = result.exceptionOrNull()?.message.orEmpty()
        ready = result.isSuccess
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(pack.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("使用当前保存内容预览；应用后同时用于手动和定时推送。", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    pack.templates.keys.forEach { k -> FilterChip(selected = k == kind, onClick = { kind = k },
                        label = { Text(templateName(k)) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                preview?.let { Image(it.asImageBitmap(), "导入版式预览", Modifier.fillMaxWidth().aspectRatio(528f / 792f)) }
                optionState.SaveableStateProvider(kind) {
                    LayoutOptions(pack.templates.getValue(kind)) { templateJson ->
                        val json = JSONObject(edited.json)
                        json.getJSONObject("templates").put(kind, templateJson)
                        runCatching { LayoutPack.parse(json.toString()) }
                            .onSuccess { edited = it }
                            .onFailure { error = it.message.orEmpty(); ready = false }
                    }
                }
                if (!ready && error.isEmpty()) CircularProgressIndicator()
                if (error.isNotEmpty()) Text("此版式无法应用：$error", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onApply(edited) }) { Text("应用版式") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun LayoutOptions(template: LayoutPack.Template, onChange: (JSONObject) -> Unit) {
    val nodes = remember(template) { template.nodes.map { JSONObject(it.json) } }
    val titleIndex = nodes.indexOfFirst { n ->
        n.optString("type") == "text" && n.optBoolean("bold") &&
            n.optString("text").let { it.isNotBlank() && !it.contains("{{") }
    }
    val colorKeys = listOf("color", "fill", "border")
    val blue = nodes.any { n -> colorKeys.any { n.optString(it) == "blue" } }
    val hasList = nodes.any { it.optString("type") == "list" }
    val groups = mapOf<String, (JSONObject) -> Boolean>(
        "小时趋势" to { it.optString("type") == "weatherHours" },
        "三天预报" to { it.optString("type") == "forecastBars" },
        "底部引文" to { it.optString("text").contains("{{quote}}") || it.optString("text").contains("{{quoteBy}}") },
        "诗词赏析" to { it.optString("showIf") == "poemNote" || it.optString("text").contains("{{poemNote}}") },
    ).filterValues { predicate -> nodes.any(predicate) }
    if (titleIndex < 0 && !blue && !hasList && groups.isEmpty()) return
    var title by rememberSaveable { mutableStateOf(nodes.getOrNull(titleIndex)?.optString("text").orEmpty()) }
    var accent by rememberSaveable { mutableStateOf("blue") }
    var numbered by rememberSaveable { mutableStateOf(nodes.any { it.optString("type") == "list" && it.optBoolean("numbered") }) }
    var hidden by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    Text("场景选项", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (titleIndex >= 0) OutlinedTextField(value = title, onValueChange = { title = it.take(48) },
        label = { Text("卡面标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (blue) {
        Text("强调色", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("蓝" to "blue", "红" to "red", "黑" to "black").forEach { (label, value) ->
                FilterChip(selected = accent == value, onClick = { accent = value }, label = { Text(label) })
            }
        }
    }
    if (hasList) FilterChip(selected = numbered, onClick = { numbered = !numbered }, label = { Text("列表编号") })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.keys.forEach { label ->
            FilterChip(selected = label !in hidden, onClick = {
                hidden = ArrayList(if (label in hidden) hidden - label else hidden + label)
            },
                label = { Text(label) })
        }
    }
    OutlinedButton(onClick = {
        val changed = JSONArray()
        nodes.forEachIndexed { index, source ->
            if (groups.any { (label, match) -> label in hidden && match(source) }) return@forEachIndexed
            val node = JSONObject(source.toString())
            if (index == titleIndex && title.isNotBlank()) node.put("text", title.trim())
            colorKeys.filter { source.optString(it) == "blue" }.forEach { node.put(it, accent) }
            if (source.optString("type") == "list") node.put("numbered", numbered)
            changed.put(node)
        }
        onChange(JSONObject(template.json).put("nodes", changed))
    }) { Text("更新预览") }
}
