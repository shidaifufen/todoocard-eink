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
    var showHistory by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val history = remember(revision) { LayoutStore.history(ctx) }
    var reset by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val linkPrefs = remember(ctx) { ctx.getSharedPreferences("layout_links_v1", 0) }
    var autoCheck by remember { mutableStateOf(linkPrefs.getBoolean("autoCheck", true)) }
    var availableUpdate by remember { mutableStateOf<LayoutPack.Pack?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    var link by remember {
        val saved = linkPrefs.getString("last", null)
        mutableStateOf(saved.orEmpty())
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
    LaunchedEffect(autoCheck) {
        if (!autoCheck) return@LaunchedEffect
        val subscribed = linkPrefs.getString("last", null) ?: return@LaunchedEffect
        try {
            val pack = withContext(Dispatchers.IO) { LayoutDownload.fetch(subscribed) }
            availableUpdate = pack.takeUnless(LayoutStore::matches)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The manual check can retry and report network errors.
        }
    }
    SectionCard(title = "看板版式包") {
        Text(packName, fontSize = 15.sp)
        Text(if (changed.isEmpty()) "正在使用内置版式" else "已覆盖：${changed.joinToString { templateName(it) }}", fontSize = 12.sp, color = StudioMuted)
        Text("导入 JSON 调整排版，内容、相册、设备和计划单独保存。只替换包内列出的卡片。", fontSize = 12.sp, color = StudioMuted)
        OutlinedButton(enabled = !busy, onClick = { linkError = ""; linkDialog = true }) { Text("检查 GitHub 更新 / 导入版式") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("打开版式设置时检查更新", Modifier.weight(1f))
            Switch(autoCheck, onCheckedChange = {
                autoCheck = it
                linkPrefs.edit().putBoolean("autoCheck", it).apply()
            })
        }
        availableUpdate?.takeUnless { LayoutStore.matches(it) }?.let { update ->
            TextButton(enabled = !busy, onClick = { pending = update }) { Text("预览云端更新") }
        }
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
        if (history.isNotEmpty()) TextButton(enabled = !busy, onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "收起版式历史" else "预览历史版本（${history.size}）")
        }
        if (showHistory) history.forEach { (at, pack) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(at))} · ${pack.name}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                TextButton(enabled = !busy, onClick = {
                    restoring = true
                    pending = pack
                    message = "正在预览历史版式，确认后可应用"
                }) { Text("预览") }
            }
        }
        TextButton(enabled = !busy && changed.isNotEmpty(), onClick = { reset = true }) { Text("恢复内置版式") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, fontSize = 12.sp)
    }
    pending?.let { pack -> LayoutPreview(pack,
        onDismiss = { pending = null; restoring = false }, onApply = { edited ->
            pending = null
            val restore = restoring
            restoring = false
            perform { LayoutStore.apply(ctx, edited, restore) }
        }) }
    if (linkDialog) AlertDialog(onDismissRequest = { if (!busy) linkDialog = false },
        title = { Text("从 GitHub 获取版式") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("粘贴公开仓库的 JSON 文件链接或 Release 附件链接。每次获取最新文件，预览后再应用；上次链接会保留。", fontSize = 12.sp)
                OutlinedTextField(value = link, onValueChange = { link = it; linkError = "" }, enabled = !busy,
                    label = { Text("GitHub 链接") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    isError = linkError.isNotEmpty())
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
                    linkDialog = false
                    if (LayoutStore.matches(pack)) {
                        message = "云端内容与当前版式一致，已是最新版本"
                    } else {
                        pending = pack
                        message = "发现云端版式更新，请预览后应用"
                    }
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
internal fun LayoutPreview(
    pack: LayoutPack.Pack,
    onDismiss: () -> Unit,
    onApply: (LayoutPack.Pack) -> Unit,
    actionLabel: String = "应用版式",
    independent: Boolean = false,
    saving: Boolean = false,
    saveError: String = "",
) {
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
                if (!independent) Text("使用当前保存内容预览，应用后同时用于手动和定时推送。", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    pack.templates.keys.forEach { k -> FilterChip(selected = k == kind, onClick = { kind = k },
                        label = { Text(templateName(k)) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                preview?.let { Image(it.asImageBitmap(), "导入版式预览", Modifier.fillMaxWidth().aspectRatio(528f / 792f)) }
                optionState.SaveableStateProvider(kind) {
                    LayoutOptions(pack.templates.getValue(kind), independent) { templateJson ->
                        val json = JSONObject(edited.json)
                        json.getJSONObject("templates").put(kind, templateJson)
                        runCatching { LayoutPack.parse(json.toString()) }
                            .onSuccess { edited = it }
                            .onFailure { error = it.message.orEmpty(); ready = false }
                    }
                }
                if (!ready && error.isEmpty()) CircularProgressIndicator()
                if (error.isNotEmpty()) Text("此版式无法应用：$error", color = MaterialTheme.colorScheme.error)
                if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !saving, onClick = { onApply(edited) }) {
                Text(if (saving) "正在保存…" else actionLabel)
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun LayoutOptions(template: LayoutPack.Template, independent: Boolean, onChange: (JSONObject) -> Unit) {
    val nodes = remember(template) { template.nodes.map { JSONObject(it.json) } }
    val titleIndex = nodes.indexOfFirst { n ->
        n.optString("type") == "text" && n.optBoolean("bold") &&
            n.optString("text").let { it.isNotBlank() && !it.contains("{{") }
    }
    val staticIndices = if (independent) nodes.indices.filter { i ->
        i != titleIndex && nodes[i].optString("type") == "text" &&
            nodes[i].optString("text").let { it.isNotBlank() && !it.contains("{{") }
    } else emptyList()
    val colorKeys = listOf("color", "fill", "border")
    val blue = nodes.any { n -> colorKeys.any { n.optString(it) == "blue" } }
    val hasList = nodes.any { it.optString("type") == "list" }
    val groups = mapOf<String, (JSONObject) -> Boolean>(
        "小时趋势" to { it.optString("type") == "weatherHours" },
        "三天预报" to { it.optString("type") == "forecastBars" },
        "底部引文" to { it.optString("text").contains("{{quote}}") || it.optString("text").contains("{{quoteBy}}") },
        "诗词赏析" to { it.optString("showIf") == "poemNote" || it.optString("text").contains("{{poemNote}}") },
    ).filterValues { predicate -> nodes.any(predicate) }
    if (titleIndex < 0 && staticIndices.isEmpty() && !blue && !hasList && groups.isEmpty()) return
    var title by rememberSaveable { mutableStateOf(nodes.getOrNull(titleIndex)?.optString("text").orEmpty()) }
    var accent by rememberSaveable { mutableStateOf("blue") }
    var numbered by rememberSaveable { mutableStateOf(nodes.any { it.optString("type") == "list" && it.optBoolean("numbered") }) }
    var hidden by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var fixedText by rememberSaveable {
        mutableStateOf(ArrayList(staticIndices.map { nodes[it].optString("text") }))
    }
    Text("场景选项", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (titleIndex >= 0) OutlinedTextField(value = title, onValueChange = { title = it.take(48) },
        label = { Text("卡面标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    staticIndices.forEachIndexed { position, index ->
        OutlinedTextField(
            value = fixedText[position],
            onValueChange = { value -> fixedText = ArrayList(fixedText).apply { this[position] = value.take(4096) } },
            label = { Text(nodes[index].optString("text").take(24), maxLines = 1) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
            staticIndices.indexOf(index).takeIf { it >= 0 }?.let { node.put("text", fixedText[it].trim()) }
            colorKeys.filter { source.optString(it) == "blue" }.forEach { node.put(it, accent) }
            if (source.optString("type") == "list") node.put("numbered", numbered)
            changed.put(node)
        }
        onChange(JSONObject(template.json).put("nodes", changed))
    }) { Text("更新预览") }
}
