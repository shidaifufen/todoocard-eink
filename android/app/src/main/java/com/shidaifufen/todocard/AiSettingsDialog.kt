package com.shidaifufen.todocard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AiSettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val original = remember { AiConfig.load(ctx) }
    var value by remember { mutableStateOf(original) }
    var message by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("赏析与生图接口") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("每日一句、每日诗词和 Bing 图片无需密钥。自定义赏析和 AI 生图使用你配置的 OpenAI 兼容接口。")
            OutlinedTextField(value.endpoint, { value = value.copy(endpoint = it) }, label = { Text("HTTPS API 基址") })
            OutlinedTextField(value.model, { value = value.copy(model = it) }, label = { Text("文本模型") })
            OutlinedTextField(value.imageModel, { value = value.copy(imageModel = it) }, label = { Text("图片模型") })
            OutlinedTextField(value.key, { value = value.copy(key = it) }, label = { Text("API Key（可选）") },
                visualTransformation = PasswordVisualTransformation())
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { AiConfig.save(ctx, value) }.onSuccess { onDismiss() }
            .onFailure { message = it.message ?: "保存失败" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
