package com.shidaifufen.todocard

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object CloudBoards {
    data class Board(
        val id: String,
        val kind: String,
        val pack: LayoutPack.Pack,
        val origin: String,
    ) {
        val template get() = pack.templates.getValue(kind)
        val title get() = pack.name
        val planKey get() = "cloud:$id"
        val autoUpdates get() = origin.matches(publishedScene)
    }

    private val publishedScene = Regex(
        "https://raw\\.githubusercontent\\.com/shidaifufen/todoocard-eink/main/layouts/scenes/live-[a-z0-9-]+\\.json"
    )

    private fun cachedFile(ctx: Context, board: Board) =
        AtomicFile(File(ctx.cacheDir, "cloud-scene-${UUID.fromString(board.id)}.json"))

    private fun withPack(board: Board, pack: LayoutPack.Pack): Board {
        require(pack.templates.keys == setOf(board.kind)) { "云端看板的类型已更改" }
        return board.copy(pack = pack)
    }

    fun cached(ctx: Context, board: Board): Board {
        if (!board.autoUpdates) return board
        return runCatching {
            val raw = cachedFile(ctx, board).openRead().use(LayoutDownload::read)
            withPack(board, LayoutPack.parse(raw))
        }.getOrDefault(board)
    }

    suspend fun latest(ctx: Context, board: Board): Board = withContext(Dispatchers.IO) {
        if (!board.autoUpdates) return@withContext board
        val updated = withPack(board, LayoutDownload.fetch(board.origin))
        val file = cachedFile(ctx, board)
        synchronized(this@CloudBoards) {
            val out = file.startWrite()
            try {
                out.write(updated.pack.json.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (error: Exception) {
                file.failWrite(out)
                throw error
            }
        }
        updated
    }

    suspend fun scheduledBitmap(ctx: Context, key: String, onNotice: (String) -> Unit): android.graphics.Bitmap {
        val board = withContext(Dispatchers.IO) { load(ctx).firstOrNull { it.planKey == key } }
            ?: error("计划中的云端看板已移除，请重新选择")
        val active = try {
            latest(ctx, board)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onNotice("云端刷新失败，发送上次保存的看板")
            cached(ctx, board)
        }
        val card = CardSources.scheduledCard(ctx, active.kind, onNotice, template = active.template)
        return withContext(Dispatchers.Default) { Render.render(card.copy(kind = active.kind), active.template) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud_boards", Context.MODE_PRIVATE)

    fun savedOrder(ctx: Context): List<String> {
        val order = runCatching { JSONArray(prefs(ctx).getString("sidebar_order", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until order.length()).map { order.optString(it) }
    }

    fun saveOrder(ctx: Context, keys: List<String>) {
        check(prefs(ctx).edit().putString("sidebar_order", JSONArray(keys).toString()).commit()) { "卡片顺序保存失败" }
    }

    fun ordered(available: List<String>, saved: List<String>): List<String> =
        (saved.filter { it in available } + available).distinct()

    fun load(ctx: Context): List<Board> {
        val saved = runCatching { JSONArray(prefs(ctx).getString("boards", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until saved.length()).mapNotNull { index ->
            runCatching {
                val entry = saved.getJSONObject(index)
                val raw = entry.getString("pack")
                val pack = LayoutPack.parse(raw)
                val kind = entry.getString("kind")
                require(kind in pack.templates)
                Board(entry.getString("id"), kind, pack, entry.optString("origin"))
            }.getOrNull()
        }
    }

    fun add(current: List<Board>, pack: LayoutPack.Pack, origin: String): Pair<List<Board>, String> {
        val boards = current.toMutableList()
        val added = pack.templates.map { (kind, template) ->
            val name = if (pack.templates.size == 1) pack.name else template.name
            val singlePack = LayoutPack.parse(JSONObject().put("schemaVersion", 1).put("name", name)
                .put("templates", JSONObject().put(kind, JSONObject(template.json))).toString())
            val existing = boards.indexOfFirst {
                it.kind == kind && if (origin.isNotBlank()) {
                    it.origin == origin
                } else {
                    it.origin.isBlank() && it.title == name && it.template == template
                }
            }
            val id = if (existing >= 0) boards[existing].id else UUID.randomUUID().toString()
            val board = Board(id, kind, singlePack, origin)
            if (existing >= 0) boards[existing] = board else boards.add(board)
            id
        }
        return boards.toList() to added.first()
    }

    fun save(ctx: Context, boards: List<Board>) {
        val json = JSONArray()
        for (board in boards) {
            json.put(JSONObject().put("id", board.id).put("kind", board.kind)
                .put("pack", board.pack.json).put("origin", board.origin))
        }
        check(prefs(ctx).edit().putString("boards", json.toString()).commit()) { "看板保存失败" }
    }
}
