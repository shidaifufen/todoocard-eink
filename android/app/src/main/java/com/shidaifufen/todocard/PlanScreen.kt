package com.shidaifufen.todocard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 每日计划 / 间隔推送 / 时间轴 / 历史 —— 统一视觉：卡片分组 + 标准时间选择器 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(ctx: android.content.Context) {
    PlanStore.init(ctx)
    var fixed by remember { mutableStateOf(PlanStore.fixed(ctx)) }
    var itvs by remember { mutableStateOf(PlanStore.intervals(ctx)) }
    var hist by remember { mutableStateOf(PlanStore.history(ctx)) }

    var editFixed by remember { mutableStateOf<Int?>(null) }   // null=关闭, -1=新增, >=0 编辑
    var editItv by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier.fillMaxSize().background(StudioBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("计划", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("按时间自动更新卡片", fontSize = 12.sp, color = StudioMuted)
        Spacer(Modifier.height(18.dp))
        TimelineCard(fixed, itvs, hist, onRefresh = { hist = PlanStore.history(ctx) })

        SectionCard(title = "每日计划", action = {
            FilledTonalButton(onClick = { editFixed = -1 }) { Text("新增", fontSize = 13.sp) }
        }) {
            if (fixed.isEmpty()) {
                Text("还没有计划，点右上「新增」", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            fixed.forEachIndexed { i, f ->
                PlanRow(
                    time = f.time,
                    title = templateName(f.template),
                    enabled = f.enabled,
                    onToggle = { v ->
                        fixed = fixed.toMutableList().also { it[i] = f.copy(enabled = v) }
                        PlanStore.saveFixed(ctx, fixed); SchedulerWorker.schedule(ctx)
                    },
                    onEdit = { editFixed = i },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "间隔推送", action = {
            FilledTonalButton(onClick = { editItv = -1 }) { Text("新增", fontSize = 13.sp) }
        }) {
            if (itvs.isEmpty()) {
                Text("例如：工作日 09:00–22:00 每 60 分钟", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itvs.forEachIndexed { i, t ->
                val days = when (t.days) { "workday" -> "工作日"; "weekend" -> "周末"; else -> "每天" }
                PlanRow(
                    time = "${t.start}–${t.end}",
                    title = "每 ${t.everyMin} 分钟 · $days · ${templateName(t.template)}",
                    enabled = t.enabled,
                    onToggle = { v ->
                        itvs = itvs.toMutableList().also { it[i] = t.copy(enabled = v) }
                        PlanStore.saveIntervals(ctx, itvs); SchedulerWorker.schedule(ctx)
                    },
                    onEdit = { editItv = i },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "发送历史", action = {
            TextButton(onClick = { hist = PlanStore.history(ctx) }) { Text("刷新", fontSize = 13.sp) }
        }) {
            if (hist.isEmpty()) Text("还没有发送记录", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            hist.takeLast(10).reversed().forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(h.ts)),
                        fontSize = 12.sp, modifier = Modifier.width(96.dp))
                    Text(templateName(h.template), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (h.ok) "✓ ${h.seconds}s" else "✕", fontSize = 12.sp,
                        color = if (h.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    // 计划编辑弹窗
    editFixed?.let { idx ->
        val existing = if (idx >= 0) fixed[idx] else PlanStore.Fixed("08:00", "summary")
        PlanEditDialog(
            initial = existing,
            title = if (idx >= 0) "编辑计划" else "新增计划",
            onDismiss = { editFixed = null },
            onConfirm = { item ->
                fixed = if (idx >= 0) fixed.toMutableList().also { it[idx] = item } else fixed + item
                PlanStore.saveFixed(ctx, fixed); SchedulerWorker.schedule(ctx)
                editFixed = null
            },
            onDelete = if (idx >= 0) {
                {
                    fixed = fixed.toMutableList().also { it.removeAt(idx) }
                    PlanStore.saveFixed(ctx, fixed); SchedulerWorker.schedule(ctx)
                    editFixed = null
                }
            } else null,
        )
    }

    editItv?.let { idx ->
        val existing = if (idx >= 0) itvs[idx] else PlanStore.Interval(60, "09:00", "22:00", "all", "summary")
        IntervalEditDialog(
            initial = existing,
            title = if (idx >= 0) "编辑间隔推送" else "新增间隔推送",
            onDismiss = { editItv = null },
            onConfirm = { item ->
                itvs = if (idx >= 0) itvs.toMutableList().also { it[idx] = item } else itvs + item
                PlanStore.saveIntervals(ctx, itvs); SchedulerWorker.schedule(ctx)
                editItv = null
            },
            onDelete = if (idx >= 0) {
                {
                    itvs = itvs.toMutableList().also { it.removeAt(idx) }
                    PlanStore.saveIntervals(ctx, itvs); SchedulerWorker.schedule(ctx)
                    editItv = null
                }
            } else null,
        )
    }
}

@Composable
private fun SectionCard(title: String, action: @Composable (() -> Unit)? = null,
                        content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFDCE3DC))
    }
}

@Composable
private fun PlanRow(time: String, title: String, enabled: Boolean,
                    onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onEdit() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(time, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(104.dp))
        Text(title, fontSize = 12.sp, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Switch(enabled, onToggle, Modifier.height(28.dp))
    }
}

/** 时间选择：点击按钮弹 Material3 钟表（24 小时制） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val parts = value.split(":")
    val state = rememberTimePickerState(
        initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 8,
        initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
        is24Hour = true,
    )
    OutlinedButton(onClick = { showPicker = true }, shape = RoundedCornerShape(8.dp)) {
        Text("$label  $value", fontSize = 13.sp)
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(state.hour, state.minute)); showPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun TemplatePicker(current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TEMPLATES.forEach { (key, name) ->
            FilterChip(selected = current == key, onClick = { onPick(key) }, label = { Text(name, fontSize = 12.sp) })
        }
    }
}

@Composable
private fun PlanEditDialog(initial: PlanStore.Fixed, title: String, onDismiss: () -> Unit,
                           onConfirm: (PlanStore.Fixed) -> Unit, onDelete: (() -> Unit)?) {
    var item by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                TimeField("时间", item.time) { item = item.copy(time = it) }
                Spacer(Modifier.height(10.dp))
                Text("模板", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                TemplatePicker(item.template) { item = item.copy(template = it) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(item.enabled, { v -> item = item.copy(enabled = v) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(item) }) { Text("保存") } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun IntervalEditDialog(initial: PlanStore.Interval, title: String, onDismiss: () -> Unit,
                               onConfirm: (PlanStore.Interval) -> Unit, onDelete: (() -> Unit)?) {
    var item by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                TimeField("开始", item.start) { item = item.copy(start = it) }
                TimeField("结束", item.end) { item = item.copy(end = it) }
                Spacer(Modifier.height(10.dp))
                Text("间隔 ${item.everyMin} 分钟", fontSize = 13.sp)
                Slider(value = item.everyMin.toFloat(), onValueChange = {
                    item = item.copy(everyMin = it.toInt().coerceIn(5, 240))
                }, valueRange = 5f..240f)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "每天", "workday" to "工作日", "weekend" to "周末").forEach { (k, n) ->
                        FilterChip(selected = item.days == k, onClick = { item = item.copy(days = k) },
                            label = { Text(n, fontSize = 12.sp) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("模板", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TemplatePicker(item.template) { item = item.copy(template = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(item) }) { Text("保存") } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun TimelineCard(fixed: List<PlanStore.Fixed>, itvs: List<PlanStore.Interval>,
                         hist: List<PlanStore.Hist>, onRefresh: () -> Unit) {
    val now = System.currentTimeMillis()
    val next = planNext(fixed, itvs, now, 48)
    Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间轴", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("刷新", fontSize = 13.sp) }
            }
            Text("前 24 小时 ~ 后 24 小时 · 白线=现在 · 蓝=计划 · 绿/红=已发送",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val start = now - 24 * 3600_000L
                val span = 48 * 3600_000f
                fun xOf(ts: Long) = ((ts - start) / span) * size.width
                drawRect(Color(0xFF12141A), size = size)
                hist.forEach { h ->
                    val x = xOf(h.ts)
                    if (x in 0f..size.width) drawLine(
                        if (h.ok) Color(0xFF2FBF71) else Color(0xFFD9534F),
                        androidx.compose.ui.geometry.Offset(x, size.height * 0.68f),
                        androidx.compose.ui.geometry.Offset(x, size.height), 3f)
                }
                next.forEach { ts ->
                    val x = xOf(ts)
                    if (x in 0f..size.width) drawLine(Color(0xFF4F7CFF),
                        androidx.compose.ui.geometry.Offset(x, size.height * 0.14f),
                        androidx.compose.ui.geometry.Offset(x, size.height * 0.56f), 3f)
                }
                val nx = xOf(now)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(nx, 0f),
                    androidx.compose.ui.geometry.Offset(nx, size.height), 2f)
            }
            Spacer(Modifier.height(6.dp))
            Text("接下来：${next.take(3).joinToString("   ") { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it)) }.ifEmpty { "无" }}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun templateName(k: String): String = TEMPLATES.firstOrNull { it.first == k }?.second ?: k

fun planNext(fixed: List<PlanStore.Fixed>, itvs: List<PlanStore.Interval>,
             now: Long, hours: Int): List<Long> {
    val out = mutableListOf<Long>()
    val cal = Calendar.getInstance()
    for (f in fixed.filter { it.enabled }) {
        val p = f.time.split(":")
        repeat(hours / 24 + 2) { d ->
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, d)
            cal.set(Calendar.HOUR_OF_DAY, p.getOrNull(0)?.toIntOrNull() ?: 8)
            cal.set(Calendar.MINUTE, p.getOrNull(1)?.toIntOrNull() ?: 0)
            cal.set(Calendar.SECOND, 0)
            if (cal.timeInMillis > now) out.add(cal.timeInMillis)
        }
    }
    for (t in itvs.filter { it.enabled }) {
        val s = t.start.split(":"); val e = t.end.split(":")
        repeat(hours / 24 + 2) { d ->
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, d)
            cal.set(Calendar.HOUR_OF_DAY, s.getOrNull(0)?.toIntOrNull() ?: 9)
            cal.set(Calendar.MINUTE, s.getOrNull(1)?.toIntOrNull() ?: 0)
            val endCal = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, e.getOrNull(0)?.toIntOrNull() ?: 22)
                set(Calendar.MINUTE, e.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            var x = cal.timeInMillis
            while (x <= endCal.timeInMillis) {
                if (x > now) out.add(x)
                x += maxOf(5, t.everyMin) * 60_000L
            }
        }
    }
    return out.sorted()
}
