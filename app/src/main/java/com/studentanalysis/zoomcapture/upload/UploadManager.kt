package com.studentanalysis.zoomcapture.upload

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.studentanalysis.zoomcapture.storage.SessionWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class UploadStatus(
    val mode: StorageMode = StorageMode.LOCAL_ONLY,
    val server: ServerConfig? = null,
    val busy: Boolean = false,
    val pausedReason: String? = null,
    val lastError: String? = null,
    val lastSuccessMs: Long? = null,
    /** 撮影中のセッションの送信状態 */
    val live: SessionUploadState? = null,
    /** 送り終えていないセッションの数 */
    val pendingSessions: Int = 0,
)

/**
 * サーバーへの送信をまとめて管理する(プロセスに1つ)。
 * アプリが前面にある間はこのクラスのループで送り、アプリが閉じられた後は UploadWorker(WorkManager)が続きを送る。
 * どちらから呼ばれても、mutex で同時に2つは走らない。
 */
class UploadManager private constructor(context: Context) {

    private val app = context.applicationContext
    private val settings = UploadSettingsStore(app)
    private val states = UploadStateStore(File(app.filesDir, "upload_state"))
    private val sessionsRoot = SessionWriter.sessionsRoot(app)

    /** 撮影のタイミングに影響しないよう、優先度の低いスレッド1本で送る */
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "upload")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val throttle = UploadThrottle()

    @Volatile
    private var liveDir: File? = null

    @Volatile
    private var paused = false

    /** トークンが拒否されたら、設定し直すまで送らない */
    @Volatile
    private var authRejected = false

    private var uploader: SessionUploader? = null
    private var uploaderKey: String? = null

    private val _status = MutableStateFlow(UploadStatus(mode = settings.mode, server = settings.loadServer()))
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    init {
        scope.launch { loop() }
    }

    // ---- 設定 ----

    val mode: StorageMode get() = _status.value.mode

    fun setMode(mode: StorageMode) {
        settings.mode = mode
        _status.update { it.copy(mode = mode) }
    }

    fun saveServer(config: ServerConfig) {
        settings.saveServer(config)
        authRejected = false
        _status.update { it.copy(server = config, lastError = null) }
        kick()
    }

    fun clearServer() {
        settings.clearServer()
        if (settings.mode.usesServer) setMode(StorageMode.LOCAL_ONLY)
        _status.update { it.copy(server = null, lastError = null) }
    }

    suspend fun testConnection(config: ServerConfig): Result<HealthInfo> = withContext(dispatcher) {
        runCatching { ServerClient(config).health() }
    }

    // ---- 撮影との連携 ----

    /** 撮影を始めたときにそのセッションフォルダを、終わったときに null を渡す */
    fun setLiveSession(dir: File?) {
        liveDir = dir
        if (dir == null) {
            throttle.reset()
            paused = false
            _status.update { it.copy(pausedReason = null) }
            scheduleWorker()
        }
        kick()
    }

    fun onCaptureProgress(nowMs: Long, shots: Long, bad: Long, thermalSevere: Boolean) {
        paused = throttle.update(nowMs, shots, bad, thermalSevere)
        val reason = if (paused) throttle.reason else null
        if (_status.value.pausedReason != reason) _status.update { it.copy(pausedReason = reason) }
    }

    // ---- セッション一覧から ----

    fun stateOf(sessionDir: File): SessionUploadState = states.load(sessionDir.name)

    /** 「端末のみ」で撮ったセッションを送る */
    fun requestUpload(sessionDir: File) {
        states.update(sessionDir.name) { it.copy(requested = true) }
        kick()
        scheduleWorker()
    }

    fun onSessionDeleted(sessionDir: File) {
        states.delete(sessionDir.name)
        scope.launch { mutex.withLock { uploader?.forget(sessionDir.name) } }
    }

    fun kick() {
        kicks.trySend(Unit)
    }

    // ---- 送信 ----

    enum class Drain { IDLE, MORE, WAITING, ERROR }

    /** 送るべきセッションを一通り処理する。UploadWorker からも呼ばれる */
    suspend fun drainOnce(): Drain = withContext(dispatcher) {
        mutex.withLock { drainLocked() }
    }

    private suspend fun drainLocked(): Drain {
        val config = settings.loadServer() ?: return Drain.IDLE
        if (authRejected) return Drain.ERROR
        val up = uploader?.takeIf { uploaderKey == config.key }
            ?: SessionUploader(ServerClient(config), config.key, states, shouldPause = { paused }).also {
                uploader = it
                uploaderKey = config.key
            }

        val live = liveDir
        val dirs = sessionsRoot.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        val ordered = listOfNotNull(dirs.firstOrNull { it == live }) + dirs.filter { it != live }
        var result = Drain.IDLE
        var pending = 0
        _status.update { it.copy(busy = true) }
        try {
            for (dir in ordered) {
                val isLive = dir == live
                val pass = try {
                    up.pass(dir, isLive)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "送信に失敗: ${dir.name}: $message")
                    states.update(dir.name) { it.copy(lastError = message, updatedAtMs = System.currentTimeMillis()) }
                    if (e is AuthException) authRejected = true
                    _status.update { it.copy(lastError = message) }
                    pending++
                    result = Drain.ERROR
                    // 通信やサーバーの問題なら、ほかのセッションも失敗するので打ち切る
                    if (e is UploadException) break else continue
                }
                if (isLive) _status.update { it.copy(live = pass.state) }
                when (pass.outcome) {
                    SessionUploader.Outcome.NOT_ELIGIBLE, SessionUploader.Outcome.DONE -> Unit
                    SessionUploader.Outcome.MORE -> {
                        pending++
                        if (result != Drain.ERROR) result = Drain.MORE
                    }
                    SessionUploader.Outcome.PENDING, SessionUploader.Outcome.PAUSED -> {
                        pending++
                        if (result == Drain.IDLE) result = Drain.WAITING
                    }
                }
            }
            if (result != Drain.ERROR) {
                _status.update { it.copy(lastError = null, lastSuccessMs = System.currentTimeMillis()) }
            }
        } finally {
            _status.update { it.copy(busy = false, pendingSessions = pending) }
        }
        return result
    }

    private suspend fun loop() {
        while (true) {
            val result = try {
                drainOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "送信ループのエラー", e)
                Drain.ERROR
            }
            val waitMs = when {
                result == Drain.MORE && !paused -> 0L
                liveDir != null -> LIVE_INTERVAL_MS
                result == Drain.ERROR -> RETRY_INTERVAL_MS
                result == Drain.WAITING || result == Drain.MORE -> LIVE_INTERVAL_MS
                else -> Long.MAX_VALUE
            }
            if (waitMs > 0) withTimeoutOrNull(waitMs) { kicks.receive() }
        }
    }

    /** アプリが閉じられても送り残しを送れるよう、WorkManager に頼んでおく */
    fun scheduleWorker() {
        if (settings.loadServer() == null) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private const val TAG = "UploadManager"
        private const val WORK_NAME = "upload"
        private const val LIVE_INTERVAL_MS = 3_000L
        private const val RETRY_INTERVAL_MS = 30_000L

        // 保持しているのは applicationContext だけなので、Activity のリークにはならない
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: UploadManager? = null

        fun get(context: Context): UploadManager =
            instance ?: synchronized(this) { instance ?: UploadManager(context).also { instance = it } }
    }
}
