package com.shidaifufen.todocard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

internal object OnlineCard {
    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "online-card.png"))

    fun read(ctx: Context): Bitmap? = try {
        file(ctx).openRead().use { BitmapFactory.decodeStream(it) }
    } catch (_: java.io.FileNotFoundException) { null }

    fun save(ctx: Context, raw: String) {
        require(raw.length <= 3 * 1024 * 1024) { "卡片文件过大" }
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == Render.W && bounds.outHeight == Render.H && bounds.outMimeType == "image/png") {
            "无法读取当前卡面"
        }
        val disk = file(ctx)
        val out = disk.startWrite()
        try {
            out.write(bytes)
            disk.finishWrite(out)
        } catch (error: Exception) {
            disk.failWrite(out)
            throw error
        }
    }
}

private const val LOCAL = "https://appassets.androidplatform.net"
private val pages = mapOf(
    "/online/index.html" to "text/html",
    "/online/ui.js" to "text/javascript",
    "/online/sources.js" to "text/javascript",
    "/online/render.js" to "text/javascript",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OnlineSceneDialog(ctx: Context, dark: Boolean, onDismiss: () -> Unit, onUse: () -> Unit) {
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !saving, onClick = onDismiss) { Text("返回") }
                    TextButton(enabled = browser != null && !saving, onClick = {
                        saving = true
                        error = ""
                        browser?.evaluateJavascript("window.exportCard?.() ?? null") { result ->
                            val encoded = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                            if (encoded.isNullOrEmpty()) {
                                error = "请先搜索或随机抽取一张卡片"
                                saving = false
                            } else scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { OnlineCard.save(ctx, encoded) }
                                    onUse()
                                } catch (e: Exception) {
                                    error = e.message ?: "保存失败"
                                    saving = false
                                }
                            }
                        }
                    }) { Text("应用卡片") }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) =
                                    req.url.toString() != "$LOCAL/online/index.html"

                                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                                    if (req.url.scheme != "https" || req.url.host != "appassets.androidplatform.net")
                                        return super.shouldInterceptRequest(view, req)
                                    val path = req.url.path ?: return null
                                    val mime = pages[path] ?: return null
                                    return WebResourceResponse(mime, "UTF-8", context.assets.open(path.removePrefix("/")))
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    view.evaluateJavascript("window.setDark?.($dark)", null)
                                }
                            }
                            browser = this
                            loadUrl("$LOCAL/online/index.html")
                        }
                    },
                    onReset = null,
                    onRelease = { it.stopLoading(); it.destroy() },
                    update = { it.evaluateJavascript("window.setDark?.($dark)", null) },
                )
            }
        }
    }
}
