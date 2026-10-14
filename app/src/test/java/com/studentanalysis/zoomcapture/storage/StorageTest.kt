package com.studentanalysis.zoomcapture.storage

import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.DefaultPresets
import com.studentanalysis.zoomcapture.zoom.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {

    private val pixels12mp = OutputPixels(still = 4032L * 3024, maxResolution = null, raw = 4032L * 3024)

    @Test
    fun standardJpegLectureEstimateMatchesSpec() {
        // N=3, T=3 → 1秒に1枚。90 分で 16〜22GB 程度(CLAUDE.md の見積もり)
        val specs = List(3) { ShotSpec(CaptureMode.STANDARD) }
        val perSecond = StorageEstimator.bytesPerSecond(specs, pixels12mp, 95, 3.0)
        val lecture = StorageEstimator.bytesFor(90 * 60.0, perSecond)
        assertTrue("$lecture", lecture in 14_000_000_000L..22_000_000_000L)
    }

    @Test
    fun burstAndRawCostMore() {
        val std = StorageEstimator.bytesPerShot(ShotSpec(CaptureMode.STANDARD), pixels12mp, 95)
        val burst = StorageEstimator.bytesPerShot(ShotSpec(CaptureMode.BURST, 4), pixels12mp, 95)
        assertEquals((std * 4).toDouble(), burst.toDouble(), 4.0)
        assertTrue(StorageEstimator.bytesPerShot(ShotSpec(CaptureMode.RAW), pixels12mp, 95) > std * 5)
    }

    @Test
    fun secondsRemainingKeepsReserve() {
        assertEquals(100.0, StorageEstimator.secondsRemaining(2_000, 1_000, 10.0), 1e-9)
        assertEquals(0.0, StorageEstimator.secondsRemaining(500, 1_000, 10.0), 1e-9)
    }

    @Test
    fun csvLineHasAllColumnsAndEscapes() {
        val row = CaptureLogRow(
            seq = 1, cycle = 0, presetId = "P01", scheduledMs = 0, capturedMs = 12, wallTime = "2026-10-14T10:30:00.012+09:00",
            status = "ok", captureMode = "standard", physicalCameraId = "2", zoomRatio = 1.5f,
            crop = RectI(10, 20, 110, 95), fov = NormRect(0f, 0f, 0.5f, 0.5f), imageSize = SizeI(4032, 3024),
            focusDistance = 0.25f, exposureTimeNs = 16_000_000, iso = 400, afState = "PASSIVE_FOCUSED",
            aeState = "CONVERGED", file = "P01_全体/a,b.jpg",
        )
        val line = CaptureLogFormat.toCsvLine(row)
        assertTrue(line, line.startsWith("1,0,P01,0,12,"))
        assertTrue(line, line.contains(",10,20,100,75,"))
        assertTrue(line, line.endsWith(",\"P01_全体/a,b.jpg\""))
    }

    @Test
    fun csvHeaderMatchesRowLength() {
        val row = CaptureLogRow(
            1, 0, "P01", 0, null, null, "skipped", "standard", null, null, null,
            NormRect.FULL, null, null, null, null, null, null, null,
        )
        assertEquals(CaptureLogFormat.HEADER.size, CaptureLogFormat.toCsvLine(row).split(",").size)
    }

    @Test
    fun sanitizeReplacesUnsafeCharacters() {
        assertEquals("講義室_A_B", sanitizeFileName(" 講義室 A/B "))
    }

    @Test
    fun defaultPresetsOnUltraWideDeviceUseOneXFrame() {
        val set = DefaultPresets.create("t", "m", zoomMin = 0.5f)
        assertEquals(NormRect(0.25f, 0.25f, 0.75f, 0.75f), set.presets[0].targetRect)
        assertEquals(NormRect(0.25f, 0.25f, 0.5f, 0.5f), set.presets[1].targetRect)
        assertEquals("P04", set.nextPresetId())
    }
}
