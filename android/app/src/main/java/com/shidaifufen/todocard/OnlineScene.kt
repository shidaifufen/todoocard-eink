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
import android.webkit.JavascriptInterface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object OnlineCard {
    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "online-card.png"))

    fun read(ctx: Context): Bitmap? = try {
        file(ctx).openRead().use { BitmapFactory.decodeStream(it) }
    } catch (_: java.io.FileNotFoundException) { null }

    fun decode(raw: String): Bitmap {
        require(raw.length <= 3 * 1024 * 1024) { "卡片文件过大" }
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == Render.W && bounds.outHeight == Render.H && bounds.outMimeType == "image/png") {
            "无法读取当前卡面"
        }
        val source = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "无法读取当前卡面" }
        try { return PhotoEdit.panelPreview(source) }
        finally { source.recycle() }
    }

    fun save(ctx: Context, bitmap: Bitmap) {
        val disk = file(ctx)
        val out = disk.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
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
    "/online/tone.js" to "text/javascript",
    "/online/inline.js" to "text/javascript",
    "/online/controls.css" to "text/css",
)

internal val OnlinePlanCategories = listOf(
    "online_music" to "随机音乐封面", "online_movie" to "随机电影海报",
    "online_english" to "随机英语短语", "online_idiom" to "随机成语",
)

internal class OnlineBridge(
    private val onCard: (String) -> Unit,
    private val onHeight: (Int) -> Unit,
) {
    @JavascriptInterface
    fun card(png: String) = onCard(png)

    @JavascriptInterface
    fun height(value: Int) = onHeight(value)
}

@SuppressLint("SetJavaScriptEnabled")
private fun onlineWebView(
    ctx: Context,
    onCard: (String) -> Unit,
    onHeight: (Int) -> Unit = {},
    onReady: (WebView) -> Unit = {},
): WebView = WebView(ctx).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    addJavascriptInterface(OnlineBridge(onCard, onHeight), "TodooScene")
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = true

        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (req.url.scheme != "https" || req.url.host != "appassets.androidplatform.net")
                return super.shouldInterceptRequest(view, req)
            val path = req.url.path ?: return null
            val mime = pages[path] ?: return null
            return WebResourceResponse(mime, "UTF-8", ctx.assets.open(path.removePrefix("/")))
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url == "$LOCAL/online/index.html") onReady(view)
        }
    }
}

private fun WebView.closeScene() {
    stopLoading()
    removeJavascriptInterface("TodooScene")
    destroy()
}

@Composable
internal fun OnlineSceneInline(enabled: Boolean, dark: Boolean, onCard: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onCard)
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val surface = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    var height by remember { mutableIntStateOf(232) }
    fun style(view: WebView) {
        view.evaluateJavascript("window.setTheme?.($dark,$foreground,$surface,$accent,$enabled)", null)
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height.dp),
        factory = {
            onlineWebView(ctx, { raw -> scope.launch { callback(raw) } },
                { px -> scope.launch { height = px.coerceIn(156, 700) } }, ::style)
                .apply { loadUrl("$LOCAL/online/index.html") }
        },
        onReset = null,
        onRelease = { it.closeScene() },
        update = ::style,
    )
}

internal suspend fun randomOnlineCard(ctx: Context, category: String): Bitmap {
    require(OnlinePlanCategories.any { it.first == "online_$category" })
    val raw = withContext(Dispatchers.Main) {
        var browser: WebView? = null
        try {
            withTimeout(70_000) {
                suspendCancellableCoroutine<String> { result ->
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    browser = onlineWebView(ctx, { encoded ->
                        handler.post {
                            if (result.isActive) {
                                if (encoded.isEmpty()) result.resumeWithException(IllegalStateException("此类别暂时没有可用的在线卡片"))
                                else result.resume(encoded)
                            }
                        }
                    }, onReady = { view ->
                        view.evaluateJavascript("window.drawRandom('$category')", null)
                    })
                    browser!!.loadUrl("$LOCAL/online/index.html")
                }
            }
        } finally { browser?.closeScene() }
    }
    return withContext(Dispatchers.Default) { OnlineCard.decode(raw) }
}
