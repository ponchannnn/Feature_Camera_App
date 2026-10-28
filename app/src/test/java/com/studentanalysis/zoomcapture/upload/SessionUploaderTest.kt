package com.studentanalysis.zoomcapture.upload

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** サーバーの受信 API と同じ振る舞いをする偽物 */
private class FakeServer : UploadApi {
    class Session(var json: ByteArray, var csv: ByteArray = ByteArray(0), val files: MutableMap<String, ByteArray> = linkedMapOf(), var finalized: Boolean = false)

    val sessions = mutableMapOf<String, Session>()
    var failPutFileAfter: Int = Int.MAX_VALUE
    var putFileCount = 0
    var appendCount = 0
    val order = mutableListOf<String>()

    override suspend fun health() = HealthInfo("ok", "dev", 0, true)

    override suspend fun putSessionJson(sessionId: String, bytes: ByteArray) {
        sessions.getOrPut(sessionId) { Session(bytes) }.json = bytes
    }

    override suspend fun manifest(sessionId: String): Manifest? = sessions[sessionId]?.let { s ->
        Manifest(s.csv.size.toLong(), s.finalized, s.files.map { (p, b) -> RemoteFile(p, b.size.toLong(), SessionUploader.sha256(b)) })
    }

    override suspend fun putFile(sessionId: String, path: String, file: File, sha256: String) {
        if (putFileCount >= failPutFileAfter) throw UploadException("接続が切れました")
        putFileCount++
        val s = sessions[sessionId] ?: throw UploadException("404")
        val bytes = file.readBytes()
        check(SessionUploader.sha256(bytes) == sha256)
        s.files[path] = bytes
        order += "file:$path"
    }

    override suspend fun appendCaptures(sessionId: String, offset: Long, bytes: ByteArray): Long {
        appendCount++
        val s = sessions[sessionId] ?: throw UploadException("404")
        if (offset > s.csv.size) throw OffsetConflictException(s.csv.size.toLong())
        val end = offset + bytes.size
        if (end > s.csv.size) s.csv += bytes.copyOfRange((s.csv.size - offset).toInt(), bytes.size)
        order += "csv:$end"
        return s.csv.size.toLong()
    }

    override suspend fun finalize(sessionId: String) {
        sessions.getValue(sessionId).finalized = true
    }
}

class SessionUploaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sessionDir: File
    private lateinit var states: UploadStateStore
    private val server = FakeServer()
    private val header = "seq,cycle,preset_id,status,file\n"

    @Before
    fun setUp() {
        sessionDir = File(tmp.root, "sessions/20261014_103000_講義室1").apply { mkdirs() }
        states = UploadStateStore(File(tmp.root, "state"))
    }

    private fun writeInfo(mode: StorageMode, ended: Boolean = false) {
        val end = if (ended) ""","endWallTime":"2026-10-14T12:00:00.000+09:00"""" else ""
        File(sessionDir, "session.json").writeText("""{"storageMode":"${mode.name}"$end}""")
    }

    /** 画像を保存してから captures.csv に行を足す(アプリと同じ順番) */
    private fun shoot(seq: Int, preset: String = "P01_全体", status: String = "ok"): String {
        val csv = File(sessionDir, "captures.csv")
        if (!csv.exists()) csv.writeText(header)
        val rel = "$preset/%06d_P01.jpg".format(seq)
        File(sessionDir, rel).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(100 + seq) { seq.toByte() })
        csv.appendText("$seq,0,P01,$status,$rel\n")
        return rel
    }

    private fun uploader(pause: () -> Boolean = { false }) = SessionUploader(server, "srv|dev", states, pause)

    private suspend fun SessionUploader.drain(live: Boolean): SessionUploader.PassResult {
        while (true) {
            val r = pass(sessionDir, live)
            if (r.outcome != SessionUploader.Outcome.MORE) return r
        }
    }

    @Test
    fun localOnlyIsNotUploadedUnlessRequested() = runTest {
        writeInfo(StorageMode.LOCAL_ONLY, ended = true)
        shoot(1)
        assertEquals(SessionUploader.Outcome.NOT_ELIGIBLE, uploader().pass(sessionDir, live = false).outcome)
        assertTrue(server.sessions.isEmpty())

        states.update(sessionDir.name) { it.copy(requested = true) }
        assertEquals(SessionUploader.Outcome.DONE, uploader().drain(live = false).outcome)
        assertTrue(server.sessions.getValue(sessionDir.name).finalized)
        // 手動で送ったときは端末の画像を消さない
        assertTrue(File(sessionDir, "P01_全体/000001_P01.jpg").exists())
    }

    @Test
    fun liveSessionUploadsFilesBeforeRowsAndFinalizesAfterEnd() = runTest {
        writeInfo(StorageMode.LOCAL_AND_SERVER)
        val up = uploader()
        val a = shoot(1)
        val b = shoot(2)
        assertEquals(SessionUploader.Outcome.PENDING, up.drain(live = true).outcome)

        val remote = server.sessions.getValue(sessionDir.name)
        assertEquals(setOf(a, b), remote.files.keys)
        assertArrayEquals(File(sessionDir, "captures.csv").readBytes(), remote.csv)
        // 行を追記するより前に、その行の画像を送っている
        assertEquals(listOf("file:$a", "file:$b"), server.order.take(2))
        assertFalse(remote.finalized)

        val c = shoot(3)
        up.drain(live = true)
        assertTrue(c in remote.files)

        writeInfo(StorageMode.LOCAL_AND_SERVER, ended = true)
        val done = up.drain(live = false)
        assertEquals(SessionUploader.Outcome.DONE, done.outcome)
        assertTrue(remote.finalized)
        assertTrue(String(remote.json).contains("endWallTime"))
        assertEquals(3L, done.state.sentRows)
        assertEquals(3L, done.state.totalRows)
    }

    @Test
    fun resumesAfterConnectionLossWithoutDuplicates() = runTest {
        writeInfo(StorageMode.LOCAL_AND_SERVER, ended = true)
        repeat(10) { shoot(it + 1) }
        server.failPutFileAfter = 4
        val first = runCatching { uploader().drain(live = false) }
        assertTrue(first.isFailure)
        val remote = server.sessions.getValue(sessionDir.name)
        assertEquals(4, remote.files.size)
        // 失敗したバッチの行はまだ追記していない
        assertEquals(0, remote.csv.size)

        // アプリを再起動した想定(新しい SessionUploader はサーバーの manifest から再開する)
        server.failPutFileAfter = Int.MAX_VALUE
        val before = server.putFileCount
        assertEquals(SessionUploader.Outcome.DONE, uploader().drain(live = false).outcome)
        assertEquals(6, server.putFileCount - before)
        assertArrayEquals(File(sessionDir, "captures.csv").readBytes(), remote.csv)
    }

    @Test
    fun ignoresRowsWithoutFilesAndIncompleteLastLine() = runTest {
        writeInfo(StorageMode.LOCAL_AND_SERVER, ended = true)
        shoot(1)
        File(sessionDir, "captures.csv").appendText("2,0,P01,skipped,\n3,0,P01,ok,P01_全体/0000")
        assertEquals(SessionUploader.Outcome.DONE, uploader().drain(live = false).outcome)
        val remote = server.sessions.getValue(sessionDir.name)
        assertEquals(1, remote.files.size)
        // アプリが落ちて途中で切れた最後の行は送らない
        assertEquals(header + "1,0,P01,ok,P01_全体/000001_P01.jpg\n2,0,P01,skipped,\n", String(remote.csv))
    }

    @Test
    fun deleteModeRemovesImagesOnlyAfterFinalize() = runTest {
        writeInfo(StorageMode.SERVER_DELETE_LOCAL)
        val a = shoot(1)
        val up = uploader()
        up.drain(live = true)
        assertTrue("撮影中は消さない", File(sessionDir, a).exists())

        writeInfo(StorageMode.SERVER_DELETE_LOCAL, ended = true)
        val r = up.drain(live = false)
        assertEquals(SessionUploader.Outcome.DONE, r.outcome)
        assertTrue(r.state.localDeleted)
        assertFalse(File(sessionDir, a).exists())
        assertFalse("空になったプリセットのフォルダも消す", File(sessionDir, "P01_全体").exists())
        assertTrue(File(sessionDir, "session.json").exists())
        assertTrue(File(sessionDir, "captures.csv").exists())
    }

    @Test
    fun pauseStopsBeforeNextFile() = runTest {
        writeInfo(StorageMode.LOCAL_AND_SERVER)
        repeat(3) { shoot(it + 1) }
        var paused = true
        val up = uploader { paused }
        assertEquals(SessionUploader.Outcome.PAUSED, up.pass(sessionDir, live = true).outcome)
        assertEquals(0, server.putFileCount)
        paused = false
        up.drain(live = true)
        assertEquals(3, server.putFileCount)
    }

    @Test
    fun serverChangeStartsOver() = runTest {
        writeInfo(StorageMode.LOCAL_AND_SERVER, ended = true)
        shoot(1)
        uploader().drain(live = false)
        assertTrue(states.load(sessionDir.name).finalized)

        val other = FakeServer()
        val r = SessionUploader(other, "other|dev", states).let { u ->
            var res: SessionUploader.PassResult
            do res = u.pass(sessionDir, false) while (res.outcome == SessionUploader.Outcome.MORE)
            res
        }
        assertEquals(SessionUploader.Outcome.DONE, r.outcome)
        assertEquals(1, other.sessions.getValue(sessionDir.name).files.size)
    }

    @Test
    fun manyRowsAreSplitIntoPasses() = runTest {
        writeInfo(StorageMode.LOCAL_AND_SERVER, ended = true)
        val n = SessionUploader.MAX_LINES_PER_BATCH * SessionUploader.MAX_BATCHES_PER_PASS + 5
        repeat(n) { shoot(it + 1) }
        val up = uploader()
        assertEquals(SessionUploader.Outcome.MORE, up.pass(sessionDir, live = false).outcome)
        assertEquals(SessionUploader.Outcome.DONE, up.drain(live = false).outcome)
        assertEquals(n, server.sessions.getValue(sessionDir.name).files.size)
    }
}
