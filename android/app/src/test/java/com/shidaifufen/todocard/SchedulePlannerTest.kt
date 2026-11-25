package com.shidaifufen.todocard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulePlannerTest {
    @Test fun mixedIntervalCreatesOneSlotPerTimeAndKeepsItsCandidates() {
        val zone = ZoneId.of("Asia/Shanghai")
        val at = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val mixed = PlanStore.Interval(30, "09:00", "10:00", "all", listOf("quote", "online_music", "quote"))
        val slots = SchedulePlanner.slots(emptyList(), listOf(mixed), at, at + 3600_000, zone)
        assertEquals(listOf(at, at + 1800_000, at + 3600_000), slots.map { it.at })
        assertTrue(slots.all { it.templates == listOf("online_music", "quote") })
        val reversed = mixed.copy(templates = mixed.templates.reversed().distinct())
        assertEquals(slots, SchedulePlanner.slots(emptyList(), listOf(reversed), at, at + 3600_000, zone))
    }

    @Test fun overlappingRulesForSameCardOnlySendOnce() {
        val zone = ZoneId.of("Asia/Shanghai")
        val at = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val slots = SchedulePlanner.slots(listOf(PlanStore.Fixed("09:00", "quote")),
            listOf(PlanStore.Interval(60, "09:00", "10:00", "workday", "quote")), at, at, zone)
        assertEquals(1, slots.size)
    }

    @Test fun fixedAndIntervalSlotsAreSortedAndUnique() {
        val zone = ZoneId.of("Asia/Shanghai")
        val from = ZonedDateTime.of(2026, 11, 24, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val until = from + 3 * 60 * 60 * 1000L
        val fixed = listOf(PlanStore.Fixed("09:00", "quote"))
        val intervals = listOf(PlanStore.Interval(60, "09:00", "10:00", "all", "summary"))
        val slots = SchedulePlanner.slots(fixed, intervals, from, until, zone)
        assertEquals(listOf("quote", "summary", "summary"), slots.map { it.kind })
        assertEquals(slots.map { it.at }.sorted(), slots.map { it.at })
        assertEquals(slots.size, slots.distinctBy { it.key }.size)
    }

    @Test fun crossMidnightIntervalIncludesNextDayEnd() {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = ZonedDateTime.of(2026, 11, 24, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val slots = SchedulePlanner.slots(
            emptyList(), listOf(PlanStore.Interval(60, "23:00", "01:00", "all", "photo")),
            start, start + 3 * 60 * 60 * 1000L, zone)
        assertTrue(slots.any { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.at), zone).hour == 0 })
        assertEquals(3, slots.size)
    }
}
