package com.studentanalysis.zoomcapture.zoom

import com.studentanalysis.zoomcapture.capability.PhysicalLens
import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.capability.SizeI
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun relativeRotationForTypicalBackCamera() {
        // 背面カメラ(取り付け向き 90°)を横向き(ROTATION_90)で使うと、回転は不要
        assertEquals(0, CoordinateMapper.relativeRotation(90, 90))
        // 取り付け向きが 270° の端末では上下逆になる
        assertEquals(180, CoordinateMapper.relativeRotation(270, 90))
        assertEquals(90, CoordinateMapper.relativeRotation(90, 0))
    }

    @Test
    fun displayAndSensorAreInverse() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val (x, y) = CoordinateMapper.displayToSensor(0.2f, 0.7f, rotation)
            val (u, v) = CoordinateMapper.sensorToDisplay(x, y, rotation)
            assertEquals("rotation=$rotation", 0.2f, u, 1e-6f)
            assertEquals("rotation=$rotation", 0.7f, v, 1e-6f)
        }
    }

    @Test
    fun rect180IsFlipped() {
        val r = CoordinateMapper.displayRectToSensor(NormRect(0f, 0f, 0.25f, 0.5f), 180)
        assertEquals(NormRect(0.75f, 0.5f, 1f, 1f), r)
    }

    @Test
    fun displayAspectSwapsFor90() {
        assertEquals(4f / 3f, CoordinateMapper.displayAspect(4f / 3f, 0), 1e-6f)
        assertEquals(3f / 4f, CoordinateMapper.displayAspect(4f / 3f, 90), 1e-6f)
    }

    @Test
    fun normToPixelsRoundsAndClamps() {
        assertEquals(
            RectI(0, 750, 4000, 3000),
            CoordinateMapper.normToPixels(NormRect(-0.1f, 0.25f, 1.2f, 1f), SizeI(4000, 3000)),
        )
    }

    @Test
    fun expectedLensPicksHighestNotExceedingZoom() {
        val lenses = listOf(0.6f, 1f, 2.5f, 5f).mapIndexed { i, z -> PhysicalLens("$i", 1f, 1f, SizeI(1, 1), z) }
        assertEquals("0", LensModel.expectedLens(lenses, 0.6f)?.id)
        assertEquals("1", LensModel.expectedLens(lenses, 2.4f)?.id)
        assertEquals("2", LensModel.expectedLens(lenses, 2.5f)?.id)
        assertEquals("3", LensModel.expectedLens(lenses, 10f)?.id)
    }

    @Test
    fun equivalentZoomFromFocalLength() {
        // 焦点距離が 3 倍(センサー幅は同じ)なら 3 倍
        assertEquals(3f, LensModel.equivalentZoom(12f, 5f, 4f, 5f), 1e-6f)
    }
}
