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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; message = "正在读取版式包…"
            try {
                pending = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(out.size() + read <= LayoutPack.MAX_BYTES) { "版式包不能超过 256 KB" }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    } ?: error("无法读取文件")
                    LayoutPack.parse(bytes.toString(Charsets.UTF_8))
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
        onDismiss = { pending = null }, onApply = { pending = null; perform { LayoutStore.apply(ctx, pack) } }) }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复内置版式") },
        text = { Text("恢复全部看板的内置排版。可通过“撤销上次更改”返回当前版式。") },
        confirmButton = { TextButton(onClick = { reset = false; perform { LayoutStore.reset(ctx) } }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}

@Composable
private fun LayoutPreview(pack: LayoutPack.Pack, onDismiss: () -> Unit, onApply: () -> Unit) {
    val ctx = LocalContext.current
    var kind by remember(pack) { mutableStateOf(pack.templates.keys.first()) }
    var preview by remember(pack) { mutableStateOf<Bitmap?>(null) }
    var error by remember(pack) { mutableStateOf("") }
    var ready by remember(pack) { mutableStateOf(false) }
    LaunchedEffect(pack, kind) {
        ready = false; error = ""
        val result = withContext(Dispatchers.Default) {
            runCatching {
                // Validate rendering for every changed card, not only the selected preview.
                val card = PlanStore.card(ctx)
                pack.templates.filterKeys { it != kind }.forEach { (k, t) -> LayoutRender.render(card.copy(kind = k), t).recycle() }
                LayoutRender.render(card.copy(kind = kind), pack.templates.getValue(kind))
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
                if (!ready && error.isEmpty()) CircularProgressIndicator()
                if (error.isNotEmpty()) Text("此版式无法应用：$error", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = onApply) { Text("应用版式") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
