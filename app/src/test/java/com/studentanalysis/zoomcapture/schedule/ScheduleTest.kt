package com.studentanalysis.zoomcapture.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {

    @Test
    fun roundRobinOrderAndTimes() {
        // N=3, T=3s, s=1s → A(0) B(1) C(2) A(3) B(4) C(5) A(6)
        val slots = SlotPlanner.slots(3, 3_000, 1_000).take(7).toList()
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), slots.map { it.index })
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L, 5000L, 6000L), slots.map { it.offsetMs })
        assertEquals(listOf(0L, 0L, 0L, 1L, 1L, 1L, 2L), slots.map { it.cycle })
    }

    @Test
    fun waitsAfterEachCycleWhenPeriodIsLonger() {
        // N=2, T=5s, s=1s → A(0) B(1) A(5) B(6)
        val slots = SlotPlanner.slots(2, 5_000, 1_000).take(4).toList()
        assertEquals(listOf(0L, 1000L, 5000L, 6000L), slots.map { it.offsetMs })
    }

    @Test
    fun defaultConfigIsValid() {
        val check = ScheduleValidator.check(ScheduleConfig(3.0, 1.0), listOf(500L, 600L, 700L))
        assertTrue(check.errors.toString(), check.ok)
        assertTrue(check.warnings.isEmpty())
    }

    @Test
    fun periodShorterThanNTimesStepIsRejected() {
        val check = ScheduleValidator.check(ScheduleConfig(3.0, 1.0), listOf(null, null, null, null))
        assertFalse(check.ok)
    }

    @Test
    fun stepShorterThanMeasuredLatencyIsRejected() {
        // 計測した最大 900ms + 余裕 300ms = 1.2 秒 > s = 1.0 秒
        val check = ScheduleValidator.check(ScheduleConfig(6.0, 1.0), listOf(500L, 900L))
        assertFalse(check.ok)
        assertEquals(1_200L, ScheduleValidator.measuredMinStepMs(listOf(500L, 900L, null)))
    }

    @Test
    fun unmeasuredPresetsGiveWarningOnly() {
        val check = ScheduleValidator.check(ScheduleConfig(3.0, 1.0), listOf(500L, null))
        assertTrue(check.ok)
        assertEquals(1, check.warnings.size)
    }

    @Test
    fun noPresetsIsRejected() {
        assertFalse(ScheduleValidator.check(ScheduleConfig(3.0, 1.0), emptyList()).ok)
    }
}
