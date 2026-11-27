package com.shidaifufen.todocard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
