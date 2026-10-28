package com.studentanalysis.zoomcapture.upload

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 実際の受信サーバー(server/)との結合テスト。環境変数があるときだけ動く。
 *
 *   ZC_E2E_PAIRING  add-device が表示した設定の文字列
 *   ZC_E2E_DATA     サーバーのデータフォルダ(受け取った内容の確認に使う)
 */
class ServerEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pairing: String? = System.getenv("ZC_E2E_PAIRING")
    private val data: String? = System.getenv("ZC_E2E_DATA")

    @Test
    fun uploadsSessionToRealServer() = runBlocking {
        assumeTrue(pairing != null && data != null)
        val config = ServerConfig.parsePairing(pairing!!).getOrThrow()
        val api = ServerClient(config)
        assertEquals(config.device, api.health().device)

        val dir = File(tmp.root, "20261021_090000_講義室 A").apply { mkdirs() }
        File(dir, "session.json").writeText("""{"storageMode":"SERVER_DELETE_LOCAL","endWallTime":"x"}""")
        val csv = File(dir, "captures.csv").apply { writeText("seq,file\n") }
        val files = (1..5).map { i ->
            val rel = "P0${i % 2 + 1}_奥・左/%06d_P01_20261021_090000_000.jpg".format(i)
            File(dir, rel).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(200_000 * i) { (it * i).toByte() })
            csv.appendText("$i,\"$rel\"\n")
            rel to File(dir, rel).readBytes()
        }
        val up = SessionUploader(api, config.key, UploadStateStore(File(tmp.root, "state")))
        var r: SessionUploader.PassResult
        do r = up.pass(dir, live = false) while (r.outcome == SessionUploader.Outcome.MORE)
        assertEquals(SessionUploader.Outcome.DONE, r.outcome)
        assertTrue(r.state.localDeleted)

        val remote = File(data, "sessions/${config.device}/${dir.name}")
        for ((rel, bytes) in files) {
            assertTrue(rel, File(remote, rel).readBytes().contentEquals(bytes))
            assertTrue("端末から削除されている: $rel", !File(dir, rel).exists())
        }
        assertEquals(csv.readText(), File(remote, "captures.csv").readText())
        assertTrue(File(remote, "upload_complete.json").exists())
    }

    @Test
    fun rejectsServerWithDifferentCertificate() = runBlocking {
        assumeTrue(pairing != null)
        val config = ServerConfig.parsePairing(pairing!!).getOrThrow().copy(pin = "B".repeat(43) + "=")
        val e = runCatching { ServerClient(config).health() }.exceptionOrNull()
        assertTrue(e is UploadException)
        assertTrue(e!!.message!!, e.message!!.contains("証明書が登録時と一致しません"))
    }

    @Test
    fun rejectsWrongToken() = runBlocking {
        assumeTrue(pairing != null)
        val config = ServerConfig.parsePairing(pairing!!).getOrThrow().copy(token = "wrong")
        assertTrue(runCatching { ServerClient(config).health() }.exceptionOrNull() is AuthException)
    }
}
