package com.shidaifufen.todocard

import java.time.*

/** 时间轴和实际闹钟共用的计划计算，按窗口开始日期判断工作日及跨午夜时段。 */
object SchedulePlanner {
    data class Slot(val key: String, val at: Long, val kind: String, val templates: List<String> = listOf(kind))
    fun slots(fixed: List<PlanStore.Fixed>, intervals: List<PlanStore.Interval>,
              from: Long, until: Long, zone: ZoneId = ZoneId.systemDefault()): List<Slot> {
        val result = mutableListOf<Slot>()
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        var day = first
        fun add(rule: String, time: ZonedDateTime, templates: List<String>) {
            val at = time.toInstant().toEpochMilli()
            if (at in from..until) result.add(Slot("$rule:$at", at, templates.first(), templates))
        }
        while (!day.isAfter(last)) {
            fixed.filter { it.enabled }.distinct().forEach { item ->
                runCatching { LocalTime.parse(item.time) }.getOrNull()?.let {
                    add("fixed:${item.time}:${item.template}", day.atTime(it).atZone(zone), listOf(item.template))
                }
            }
            intervals.filter { it.enabled }.distinct().forEach { item ->
                val templates = item.templates.distinct().sorted()
                if (templates.isEmpty()) return@forEach
                val weekday = day.dayOfWeek.value <= 5
                if (item.days == "workday" && !weekday || item.days == "weekend" && weekday) return@forEach
                val start = runCatching { LocalTime.parse(item.start) }.getOrNull() ?: return@forEach
                val end = runCatching { LocalTime.parse(item.end) }.getOrNull() ?: return@forEach
                var time = day.atTime(start).atZone(zone)
                val endTime = (if (end < start) day.plusDays(1) else day).atTime(end).atZone(zone)
                while (!time.isAfter(endTime)) {
                    add("interval:${item.start}:${item.end}:${item.everyMin}:${item.days}:${templates.joinToString(",")}",
                        time, templates)
                    time = time.plusMinutes(item.everyMin.coerceAtLeast(5).toLong())
                }
            }
            day = day.plusDays(1)
        }
        return result.distinctBy { it.at to it.templates }.sortedBy { it.at }
    }
}
