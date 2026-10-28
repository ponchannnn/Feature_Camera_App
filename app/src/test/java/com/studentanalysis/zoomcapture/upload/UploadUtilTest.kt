package com.studentanalysis.zoomcapture.upload

import com.studentanalysis.zoomcapture.storage.CaptureLogFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CsvTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readsOnlyCompleteLines() {
        val f = File(tmp.root, "c.csv").apply { writeText("h\n1,a\n2,b\n3,c") }
        val chunk = CsvTail.read(f, 0, maxLines = 10, maxBytes = 1000)!!
        assertEquals(listOf("h", "1,a", "2,b"), chunk.lines)
        assertEquals(10L, chunk.end)
        assertNull(CsvTail.read(f, chunk.end, 10, 1000))

        val limited = CsvTail.read(f, 0, maxLines = 2, maxBytes = 1000)!!
        assertEquals(listOf("h", "1,a"), limited.lines)
        assertEquals(3L, CsvTail.countNewlines(f))
        assertEquals(1L, CsvTail.countNewlines(f, upTo = 2))
    }

    @Test
    fun parsesQuotedFieldsWrittenByCaptureLog() {
        val line = CaptureLogFormat.HEADER.indices.joinToString(",") { "" }
        assertEquals(CaptureLogFormat.HEADER.size, CsvTail.parseLine(line).size)
        assertEquals(listOf("1", "P01_a,b/x.jpg", "say \"hi\""), CsvTail.parseLine("1,\"P01_a,b/x.jpg\",\"say \"\"hi\"\"\""))
    }
}

class UploadThrottleTest {

    @Test
    fun pausesWhenCaptureFallsBehindAndResumes() {
        val t = UploadThrottle(windowShots = 10, badRatio = 0.2, minBad = 3, pauseMs = 60_000)
        var now = 0L
        for (shot in 1L..10L) assertFalse(t.update(now++, shot, 0, false))
        // 直近 10 回のうち 3 回遅れた
        assertFalse(t.update(now, 11, 1, false))
        assertFalse(t.update(now, 12, 2, false))
        assertTrue(t.update(now, 13, 3, false))
        assertEquals("撮影が遅れているため一時停止中", t.reason)
        assertTrue(t.update(now + 59_000, 14, 3, false))
        assertFalse(t.update(now + 61_000, 15, 3, false))
    }

    @Test
    fun pausesWhileHot() {
        val t = UploadThrottle()
        assertTrue(t.update(0, 1, 0, thermalSevere = true))
        assertFalse(t.update(1, 2, 0, thermalSevere = false))
    }
}

class ServerConfigTest {

    private val pin = "A".repeat(43) + "="

    @Test
    fun parsesPairingPayload() {
        val c = ServerConfig.parsePairing("""{"v":1,"url":"https://10.1.2.3:8443/","device":"pixel6a-1","token":"tok","pin":"$pin"}""").getOrThrow()
        assertEquals("https://10.1.2.3:8443", c.url)
        assertEquals("pixel6a-1", c.device)
        assertEquals("https://10.1.2.3:8443|pixel6a-1", c.key)
    }

    @Test
    fun rejectsHttpAndGarbage() {
        assertTrue(ServerConfig.parsePairing("""{"v":1,"url":"http://10.1.2.3","device":"d","token":"t","pin":"$pin"}""").isFailure)
        assertTrue(ServerConfig.parsePairing("hello").isFailure)
        assertTrue(ServerConfig.parsePairing("""{"v":2,"url":"https://a","device":"d","token":"t","pin":"$pin"}""").isFailure)
    }
}
