package com.shidaifufen.todocard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object OnlineCard {
    private fun file(ctx: Context, category: String?) =
        AtomicFile(File(ctx.filesDir, if (category == null) "online-card.png" else "online-$category.png"))

    @Synchronized
    fun read(ctx: Context, category: String? = null): Bitmap? = try {
        file(ctx, category).openRead().use { BitmapFactory.decodeStream(it) }
    } catch (_: java.io.FileNotFoundException) { null }

    fun decode(raw: String): Bitmap {
        require(raw.length <= 3 * 1024 * 1024) { "卡片文件过大" }
        val bytes = Base64.decode(raw.substringAfter('|'), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == Render.W && bounds.outHeight == Render.H && bounds.outMimeType == "image/png") {
            "无法读取当前卡面"
        }
        val source = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "无法读取当前卡面" }
        try {
            if ('|' !in raw) return PhotoEdit.panelPreview(source)
            val result = source.copy(Bitmap.Config.ARGB_8888, true)
            val area = raw.substringBefore('|')
            if (area.isEmpty()) return result
            val values = area.split(',').map(String::toInt)
            require(values.size == 4) { "图片区域无效" }
            val (x, y, width, height) = values
            require(x in 0 until Render.W && y in 0 until Render.H &&
                width in 1..(Render.W - x) && height in 1..(Render.H - y)) { "图片区域无效" }
            val artwork = Bitmap.createBitmap(source, x, y, width, height)
            try {
                val ink = PhotoEdit.panelPreview(artwork)
                try { android.graphics.Canvas(result).drawBitmap(ink, x.toFloat(), y.toFloat(), null) }
                finally { ink.recycle() }
            } finally { if (artwork !== source) artwork.recycle() }
            return result
        } finally { source.recycle() }
    }

    @Synchronized
    fun save(ctx: Context, bitmap: Bitmap, category: String? = null) {
        val disk = file(ctx, category)
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
    "online_rates" to "汇率速览", "online_holidays" to "假日倒数",
    "online_earthquake" to "一周地震", "online_television" to "随机剧集档案",
)

internal class OnlineBridge(
    private val onCard: (String, String, String) -> Unit,
    private val onHeight: (Int) -> Unit,
    private val onLyrics: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun card(category: String, png: String, itemId: String) {
        if (OnlinePlanCategories.any { it.first == "online_$category" }) onCard(category, png, itemId)
    }
    @JavascriptInterface
    fun error(message: String) = onError(message.take(160))

    @JavascriptInterface
    fun height(value: Int) = onHeight(value)

    @JavascriptInterface
    fun lyrics(value: String) = onLyrics(value)
}

@SuppressLint("SetJavaScriptEnabled")
private fun onlineWebView(
    ctx: Context,
    onCard: (String, String, String) -> Unit,
    onHeight: (Int) -> Unit = {},
    onReady: (WebView) -> Unit = {},
    onLyrics: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    onRendererGone: (WebView) -> Unit,
): WebView = WebView(ctx).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    addJavascriptInterface(OnlineBridge(onCard, onHeight, onLyrics, onError), "TodooScene")
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
            if (url.substringBefore('?') == "$LOCAL/online/index.html") onReady(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onError("在线卡片页面加载失败（${error.errorCode}）")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRendererGone(view)
            view.destroy()
            return true
        }
    }
}

private fun WebView.closeScene() {
    stopLoading()
    removeJavascriptInterface("TodooScene")
    destroy()
}

private fun sceneUrl(scheduled: Boolean = false): String {
    val presets = JSONObject().apply {
        PhotoEdit.Preset.entries.forEach { preset ->
            val tone = PlanStore.photoPreset(preset)
            put(preset.label, JSONObject()
                .put("brightness", (tone.brightness ?: 1f) * 100f)
                .put("contrast", tone.contrast * 100f).put("saturation", tone.saturation * 100f)
                .put("shadows", tone.shadows * 100f).put("snap", tone.snapThreshold)
                .put("hue", tone.hue).put("exposure", tone.exposure)
                .put("gamma", tone.gamma).put("sharpness", tone.sharpness))
        }
    }
    return android.net.Uri.parse("$LOCAL/online/index.html").buildUpon()
        .appendQueryParameter("scheduled", if (scheduled) "1" else "0")
        .appendQueryParameter("presets", presets.toString())
        .appendQueryParameter("defaultPreset", PlanStore.defaultOnlinePhotoPreset.label)
        .build().toString()
}

private data class LyricSelection(val token: Int, val lines: List<String>, val anchor: Int, val end: Int, val single: Boolean) {
    companion object {
        fun parse(raw: String): LyricSelection? = runCatching {
            require(raw.length <= 96_000)
            val data = JSONObject(raw)
            val array = data.getJSONArray("lines")
            require(array.length() in 1..180)
            val lines = List(array.length()) { array.getString(it).take(500) }
            LyricSelection(data.getInt("token"), lines, data.optInt("anchor", -1).coerceIn(-1, lines.lastIndex),
                data.optInt("end", -1).coerceIn(-1, lines.lastIndex), data.optBoolean("single"))
        }.getOrNull()
    }
}

@Composable
private fun LyricsDialog(selection: LyricSelection, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var anchor by remember(selection) { mutableIntStateOf(selection.anchor) }
    var end by remember(selection) { mutableIntStateOf(selection.end) }
    var pickingEnd by remember(selection) { mutableStateOf(false) }
    val range = minOf(anchor, end)..maxOf(anchor, end)
    val hint = when {
        selection.single -> "点选一行歌词"
        pickingEnd -> "滑动列表并点选结束行"
        else -> "点选起止行，中间各行会自动选中"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择歌词") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (anchor < 0) hint else "已选 ${range.last - range.first + 1} 行 · $hint")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp),
                state = androidx.compose.foundation.lazy.rememberLazyListState(anchor.coerceAtLeast(0))) {
                itemsIndexed(selection.lines) { index, line ->
                    val selected = index in range
                    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Text(line, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = selected, role = Role.Checkbox, onValueChange = {
                                if (selection.single || !pickingEnd) {
                                    anchor = index
                                    end = index
                                } else end = index
                                pickingEnd = !selection.single && !pickingEnd
                            }).padding(horizontal = 10.dp, vertical = 13.dp))
                    }
                }
            }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(enabled = anchor >= 0, onClick = { onApply(anchor, end) }) { Text("应用歌词") } })
}

@Composable
internal fun OnlineSceneInline(enabled: Boolean, dark: Boolean, onCard: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onCard)
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val surface = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    var height by remember { mutableIntStateOf(232) }
    var lyrics by remember { mutableStateOf<LyricSelection?>(null) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var rendererGone by remember { mutableStateOf(false) }
    fun style(view: WebView) {
        view.evaluateJavascript("window.setTheme?.($dark,$foreground,$surface,$accent,$enabled)", null)
    }
    if (rendererGone) {
        TextButton(onClick = { rendererGone = false }) { Text("重新载入在线场景") }
    } else {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(height.dp),
            factory = {
                FrameLayout(ctx).apply {
                    val view = onlineWebView(ctx, { category, raw, _ -> scope.launch { callback(category, raw) } },
                        { px -> scope.launch { height = px.coerceIn(156, 1200) } }, ::style,
                        onLyrics = { raw -> scope.launch { lyrics = LyricSelection.parse(raw) } },
                        onRendererGone = {
                            removeView(it)
                            browser = null
                            lyrics = null
                            rendererGone = true
                        })
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    browser = view
                    view.loadUrl(sceneUrl())
                }
            },
            onReset = null,
            onRelease = { container ->
                (container.getChildAt(0) as? WebView)?.let {
                    container.removeView(it)
                    it.closeScene()
                }
                browser = null
            },
            update = { container -> (container.getChildAt(0) as? WebView)?.let(::style) },
        )
    }
    lyrics?.let { selection ->
        LyricsDialog(selection, onDismiss = { lyrics = null }, onApply = { start, end ->
            browser?.evaluateJavascript("window.applyLyrics(${selection.token},$start,$end)", null)
            lyrics = null
        })
    }
}

internal suspend fun randomOnlineCard(ctx: Context, category: String, requireFresh: Boolean,
                                      onNotice: (String) -> Unit): Bitmap {
    require(OnlinePlanCategories.any { it.first == "online_$category" })
    val bitmap = try {
        val excluded = if (requireFresh) WidgetShuffle.lastItem(ctx, category) else ""
        val (bitmap, itemId) = fetchRandomOnlineCard(ctx, category, excluded)
        if (requireFresh) WidgetShuffle.rememberItem(ctx, category, itemId)
        bitmap
    } catch (error: Exception) {
        if (error is CancellationException || requireFresh) throw error
        val cached = withContext(Dispatchers.IO) { OnlineCard.read(ctx, category) } ?: throw error
        onNotice("在线更新失败，使用上次缓存：${error.message.orEmpty().take(100)}")
        return cached
    }
    try { withContext(Dispatchers.IO) { OnlineCard.save(ctx, bitmap, category) } }
    catch (error: CancellationException) { bitmap.recycle(); throw error }
    catch (_: Exception) { onNotice("卡片已生成，缓存写入失败") }
    return bitmap
}

private suspend fun fetchRandomOnlineCard(ctx: Context, category: String, excluded: String): Pair<Bitmap, String> {
    val (raw, itemId) = withContext(Dispatchers.Main) {
        var browser: WebView? = null
        try {
            withTimeout(70_000) {
                suspendCancellableCoroutine<Pair<String, String>> { result ->
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    fun fail(message: String) = handler.post {
                        if (result.isActive) result.resumeWithException(IllegalStateException(message))
                    }
                    browser = onlineWebView(ctx, { selected, encoded, id ->
                        handler.post {
                            if (result.isActive && selected == category) result.resume(encoded to id)
                        }
                    }, onReady = { view ->
                        view.evaluateJavascript(
                            "typeof window.drawRandom === 'function' && (window.drawRandom('$category', ${JSONObject.quote(excluded)}), true)"
                        ) {
                            if (it != "true") fail("在线卡片脚本未能加载")
                        }
                    }, onError = { fail(it) }, onRendererGone = {
                        browser = null
                        fail("在线卡片渲染进程已退出")
                    })
                    browser!!.loadUrl(sceneUrl(scheduled = true))
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("在线卡片获取超时", error)
        } finally { browser?.closeScene() }
    }
    return withContext(Dispatchers.Default) { OnlineCard.decode(raw) to itemId }
}
