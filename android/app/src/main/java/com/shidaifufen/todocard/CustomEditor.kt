package com.shidaifufen.todocard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CustomEditorDialog(initial: Render.Card, onDismiss: () -> Unit, onSave: (Render.Card) -> Unit) {
    var card by remember { mutableStateOf(initial.copy(customBlocks = initial.customBlocks.ifEmpty { CustomBlocks.defaults(initial) })) }
    var add by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    fun update(b: Render.CustomBlock) { card = card.copy(customBlocks = card.customBlocks.map { if (it.id == b.id) b else it }) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = picking
        if (uri != null && id != null) scope.launch {
            val file = withContext(Dispatchers.IO) { PhotoCard.importToFiles(ctx, uri) }
            card.customBlocks.firstOrNull { it.id == id }?.let { if (file != null) update(it.copy(imagePath = file)) }
            if (file == null) message = "图片读取失败，请重新选择"
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(16.dp)) {
                Text("自由组合卡片", style = MaterialTheme.typography.titleLarge)
                Text("按顺序排列已有元素；内容超出时整体缩小适应屏幕。", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(card.customTitle, { card = card.copy(customTitle = it) }, label = { Text("卡片标题") })
                    Row {
                        listOf("red" to "红", "blue" to "蓝", "green" to "绿", "black" to "黑", "yellow" to "黄").forEach { (key, label) ->
                            FilterChip(card.customAccent == key, { card = card.copy(customAccent = key) }, label = { Text(label) })
                        }
                    }
                    card.customBlocks.forEachIndexed { index, b -> key(b.id) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Row {
                                    Text("${index + 1}. ${CustomBlocks.types[b.type] ?: b.type}", Modifier.weight(1f))
                                    Switch(b.enabled, { update(b.copy(enabled = it)) })
                                }
                                Row {
                                    TextButton(enabled = index > 0, onClick = {
                                        val list = card.customBlocks.toMutableList(); list.add(index - 1, list.removeAt(index)); card = card.copy(customBlocks = list)
                                    }) { Text("上移") }
                                    TextButton(enabled = index < card.customBlocks.lastIndex, onClick = {
                                        val list = card.customBlocks.toMutableList(); list.add(index + 1, list.removeAt(index)); card = card.copy(customBlocks = list)
                                    }) { Text("下移") }
                                    TextButton(onClick = { card = card.copy(customBlocks = card.customBlocks.filter { it.id != b.id }) }) { Text("删除") }
                                }
                                if (b.type in listOf("title", "text", "qr")) OutlinedTextField(b.text, { update(b.copy(text = it)) },
                                    label = { Text(if (b.type == "qr") "二维码文字或网址" else "内容") }, minLines = 2, maxLines = 6)
                                if (b.type == "image") TextButton(onClick = {
                                    picking = b.id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Text(if (b.imagePath.isBlank()) "选择图片" else "更换图片") }
                                if (b.type in listOf("image", "qr")) {
                                    Text("高度 ${b.height} 像素")
                                    Slider(b.height.toFloat(), { update(b.copy(height = it.toInt())) }, valueRange = 80f..360f)
                                } else if (b.type !in listOf("rule", "dots")) {
                                    Text("字号 ${b.size.toInt()}")
                                    Slider(b.size.coerceIn(14f, 64f), { update(b.copy(size = it)) }, valueRange = 14f..64f)
                                    Row { Text("居中", Modifier.weight(1f)); Switch(b.centered, { update(b.copy(centered = it)) }) }
                                }
                            }
                        }
                    } }
                    if (message.isNotBlank()) Text(message)
                    OutlinedButton(enabled = card.customBlocks.size < 20, onClick = { add = true }) { Text("添加元素 · ${card.customBlocks.size}/20") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onSave(card.copy(customComposition = true)) }) { Text("保存预览") }
                }
            }
        }
    }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("选择元素") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CustomBlocks.types.forEach { (type, label) -> TextButton(onClick = {
                card = card.copy(customBlocks = card.customBlocks + Render.CustomBlock(type = type,
                    size = if (type == "bigdate") 44f else 24f)); add = false
            }) { Text(label) } }
        }
    }, confirmButton = { TextButton(onClick = { add = false }) { Text("关闭") } })
}
