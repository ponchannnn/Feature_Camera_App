package com.studentanalysis.zoomcapture.upload

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * 1つのセッションフォルダをサーバーへ送る。何度呼んでも、途中で切れても、送り直すだけで済むようにする。
 *
 * 送る順番:
 * 1. session.json(内容が変わっていれば送り直す)
 * 2. captures.csv のまだ送っていない行について、その行の画像を先に送り、そのあと行を追記する
 *    (サーバーの captures.csv には、サーバーにある画像の行だけが並ぶ)
 * 3. 撮影が終わっていて全部送れたら finalize し、モードによっては端末の画像を削除する
 *
 * Android に依存しないので、JVM の単体テストで確かめられる。
 */
class SessionUploader(
    private val api: UploadApi,
    private val serverKey: String,
    private val states: UploadStateStore,
    /** true のあいだは次のファイルに進まない(撮影が遅れているときなど) */
    private val shouldPause: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome {
        /** 送る対象ではない(端末のみのセッションなど) */
        NOT_ELIGIBLE,
        /** 送り終えた(finalize 済み) */
        DONE,
        /** 撮影中、または撮影が終わっていないので、新しい行を待っている */
        PENDING,
        /** 1回分の上限まで送った。すぐに続きを送る */
        MORE,
        PAUSED,
    }

    data class PassResult(val outcome: Outcome, val state: SessionUploadState)

    private class Remote(var capturesSize: Long, val files: MutableMap<String, Long>)

    /** サーバーにあるもの。プロセスの中で覚えておき、毎回 manifest を取り直さない */
    private val remotes = mutableMapOf<String, Remote>()
    private val fileColumns = mutableMapOf<String, Int>()

    fun forget(sessionId: String) {
        remotes.remove(sessionId)
        fileColumns.remove(sessionId)
    }

    /** live: このセッションを今まさに撮影している(finalize しない) */
    suspend fun pass(dir: File, live: Boolean): PassResult {
        val id = dir.name
        var state = states.load(id).forServer(serverKey)
        val infoFile = File(dir, SESSION_JSON)
        val info = readInfo(infoFile) ?: return PassResult(if (live) Outcome.PENDING else Outcome.NOT_ELIGIBLE, state)
        val mode = effectiveMode(StorageMode.fromName(info.storageMode), state.requested)
            ?: return PassResult(Outcome.NOT_ELIGIBLE, state)
        if (state.finalized) {
            if (mode == StorageMode.SERVER_DELETE_LOCAL && !state.localDeleted) state = deleteLocal(dir, state)
            return PassResult(Outcome.DONE, state)
        }

        // 1. session.json
        val infoBytes = infoFile.readBytes()
        val infoSha = sha256(infoBytes)
        var remote = remotes[id]
        if (remote == null && state.sessionJsonSha == infoSha) {
            remote = api.manifest(id)?.toRemote()
        }
        if (remote == null || state.sessionJsonSha != infoSha) {
            api.putSessionJson(id, infoBytes)
            state = save(id, state.copy(sessionJsonSha = infoSha))
            if (remote == null) remote = api.manifest(id)?.toRemote() ?: Remote(0, mutableMapOf())
        }
        remotes[id] = remote

        // 2. 画像と captures.csv
        val csv = File(dir, CAPTURES_CSV)
        var batches = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            // 画面の表示を更新し、ほかのセッションにも順番が回るよう、一定量ごとに区切る
            if (batches++ >= MAX_BATCHES_PER_PASS) return PassResult(Outcome.MORE, saveProgress(id, state, csv, remote))
            if (shouldPause()) return PassResult(Outcome.PAUSED, saveProgress(id, state, csv, remote))
            if (remote.capturesSize > csv.length()) {
                throw UploadException("サーバーの captures.csv が端末より長くなっています(${remote.capturesSize} > ${csv.length()})")
            }
            val chunk = CsvTail.read(csv, remote.capturesSize, MAX_LINES_PER_BATCH, MAX_BATCH_BYTES) ?: break
            val fileColumn = fileColumn(id, csv) ?: break
            val rows = if (chunk.offset == 0L) chunk.lines.drop(1) else chunk.lines
            for (line in rows) {
                val path = CsvTail.parseLine(line).getOrNull(fileColumn)?.takeIf { it.isNotEmpty() } ?: continue
                if (path in remote.files) continue
                val local = File(dir, path)
                if (!local.isFile) continue // 保存に失敗した撮影など
                if (shouldPause()) return PassResult(Outcome.PAUSED, saveProgress(id, state, csv, remote))
                api.putFile(id, path, local, sha256(local))
                remote.files[path] = local.length()
            }
            remote.capturesSize = try {
                api.appendCaptures(id, chunk.offset, chunk.bytes)
            } catch (e: OffsetConflictException) {
                e.serverSize
            }
        }
        state = saveProgress(id, state, csv, remote)

        // 3. 撮影が終わっていれば確定する。アプリが落ちて最後の行が途中で切れている場合は、その行は送らない
        if (live) return PassResult(Outcome.PENDING, state)
        api.finalize(id)
        state = save(id, state.copy(finalized = true, lastError = null))
        if (mode == StorageMode.SERVER_DELETE_LOCAL) state = deleteLocal(dir, state)
        return PassResult(Outcome.DONE, state)
    }

    /** サーバーが持っていて大きさも一致する画像だけを端末から消す。session.json と captures.csv は残す */
    private suspend fun deleteLocal(dir: File, state: SessionUploadState): SessionUploadState {
        val manifest = api.manifest(dir.name) ?: throw UploadException("サーバーにセッションが見つからないため、端末の画像を削除しません")
        if (!manifest.finalized) throw UploadException("サーバーでセッションが確定していないため、端末の画像を削除しません")
        for (f in manifest.files) {
            val local = File(dir, f.path)
            if (local.isFile && local.length() == f.size && local.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                local.delete()
            }
        }
        dir.listFiles { f -> f.isDirectory }.orEmpty().forEach { if (it.list().isNullOrEmpty()) it.delete() }
        return save(dir.name, state.copy(localDeleted = true))
    }

    private fun saveProgress(id: String, state: SessionUploadState, csv: File, remote: Remote): SessionUploadState {
        val total = (CsvTail.countNewlines(csv) - 1).coerceAtLeast(0)
        val sent = (CsvTail.countNewlines(csv, remote.capturesSize) - 1).coerceAtLeast(0)
        return save(id, state.copy(sentRows = sent, totalRows = total, lastError = null))
    }

    private fun save(id: String, state: SessionUploadState): SessionUploadState =
        state.copy(updatedAtMs = clock()).also { states.save(id, it) }

    private fun fileColumn(id: String, csv: File): Int? = fileColumns[id] ?: CsvTail.firstLine(csv)
        ?.let { CsvTail.parseLine(it).indexOf("file") }
        ?.takeIf { it >= 0 }
        ?.also { fileColumns[id] = it }

    private class Info(val storageMode: String?)

    private fun readInfo(file: File): Info? = runCatching {
        val obj: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
        Info(obj["storageMode"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

    private fun Manifest.toRemote() = Remote(captures_size, files.associate { it.path to it.size }.toMutableMap())

    companion object {
        const val SESSION_JSON = "session.json"
        const val CAPTURES_CSV = "captures.csv"
        const val MAX_LINES_PER_BATCH = 30
        const val MAX_BATCH_BYTES = 256 * 1024
        const val MAX_BATCHES_PER_PASS = 10

        /** 送る対象なら実際に使うモード、対象外なら null */
        fun effectiveMode(mode: StorageMode, requested: Boolean): StorageMode? = when {
            mode.usesServer -> mode
            requested -> StorageMode.LOCAL_AND_SERVER
            else -> null
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
