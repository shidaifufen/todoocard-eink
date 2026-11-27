package com.shidaifufen.todocard

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast

internal object WidgetShuffle {
    val categories = listOf("photo", "quote", "poem").map { it to templateName(it) } + OnlinePlanCategories
    private val defaults = setOf("quote", "poem")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shuffle_widget", Context.MODE_PRIVATE)

    fun selected(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("categories", defaults).orEmpty().intersect(categories.map { it.first }.toSet())
            .ifEmpty { defaults }

    fun save(ctx: Context, selected: Set<String>) {
        prefs(ctx).edit().putStringSet("categories", selected).apply()
    }

    fun candidates(ctx: Context): List<String> {
        val last = prefs(ctx).getString("last_category", null)
        return selected(ctx).shuffled().sortedBy { it == last }
    }

    fun sent(ctx: Context, category: String) {
        prefs(ctx).edit().putString("last_category", category).apply()
    }

    fun lastItem(ctx: Context, category: String): String =
        prefs(ctx).getString("item_$category", "").orEmpty()

    fun rememberItem(ctx: Context, category: String, id: String) {
        prefs(ctx).edit().putString("item_$category", id).apply()
    }
}

class ShuffleWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_SHUFFLE) push(ctx) else super.onReceive(ctx, intent)
    }

    @SuppressLint("MissingPermission")
    private fun push(ctx: Context) {
        PlanStore.init(ctx)
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val message = when {
            PushService.status.value.running -> R.string.widget_push_busy
            permissions.any { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ->
                R.string.widget_bluetooth_permission
            PlanStore.deviceAddress.isBlank() -> R.string.widget_no_device
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true ->
                R.string.widget_bluetooth_off
            else -> {
                val started = runCatching {
                    ctx.startForegroundService(Intent(ctx, PushService::class.java).setAction(ACTION_SHUFFLE))
                }.isSuccess
                if (started) R.string.widget_push_started else R.string.widget_push_blocked
            }
        }
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val ACTION_SHUFFLE = "com.shidaifufen.todocard.WIDGET_SHUFFLE"

        private fun views(ctx: Context) = RemoteViews(ctx.packageName, R.layout.widget_shuffle).apply {
            val intent = Intent(ctx, ShuffleWidget::class.java).setAction(ACTION_SHUFFLE)
            setOnClickPendingIntent(R.id.widget_shuffle_logo,
                PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        }
    }
}
