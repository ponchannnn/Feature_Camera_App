package com.studentanalysis.zoomcapture.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 静止画の出力先 */
enum class OutputKind { JPEG, RAW, MAX_RESOLUTION_JPEG }

/**
 * Camera2 のカメラとキャプチャセッションの管理。1回開いて閉じたら使い捨てにする。
 * プレビューの切り替え(setPreview)と静止画の撮影(capture)だけを受け持ち、
 * どのプリセットをいつ撮るかは CaptureScheduler、撮影方式ごとの処理は CaptureStrategy が受け持つ。
 */
class CameraController(context: Context, val cap: CapabilityReport) {

    private val manager = context.getSystemService(CameraManager::class.java)
    val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(cap.cameraId)
    val requestFactory = CaptureRequestFactory(cap)

    private val thread = HandlerThread("CameraController").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private val readers = mutableMapOf<OutputKind, ImageReader>()
    private val imageChannels = mutableMapOf<OutputKind, Channel<Image>>()
    private val generation = AtomicInteger(0)

    private val _latestResult = MutableStateFlow<TotalCaptureResult?>(null)

    /** プレビュー(繰り返しリクエスト)の最新の結果。request.tag にプレビューの世代番号が入る */
    val latestResult: StateFlow<TotalCaptureResult?> = _latestResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** このセッションで使える撮影方式 */
    var enabledModes: Set<CaptureMode> = emptySet()
        private set

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            _latestResult.value = result
        }
    }

    /**
     * カメラを開き、キャプチャセッションを作る。
     * 端末によっては出力の組み合わせが通らないので、最大解像度 → RAW の順に外して作り直す。
     */
    suspend fun open(surface: Surface, wantedModes: Set<CaptureMode>) {
        previewSurface = surface
        val dev = openDevice()
        device = dev
        val wanted = wantedModes.filter { cap.supports(it) }.toSet()
        val attempts = listOf(
            wanted,
            wanted - CaptureMode.MAX_RESOLUTION,
            wanted - CaptureMode.MAX_RESOLUTION - CaptureMode.RAW,
        ).distinct()
        for (modes in attempts) {
            createReaders(modes)
            val created = runCatching { createSession(dev, surface) }
                .onFailure { Log.w(TAG, "セッションの作成に失敗: $modes", it) }
                .getOrNull()
            if (created != null) {
                session = created
                enabledModes = modes + CaptureMode.STANDARD + CaptureMode.BURST
                return
            }
            closeReaders()
        }
        throw IllegalStateException("キャプチャセッションを作成できませんでした")
    }

    /** プレビューの設定を切り替える。戻り値の世代番号は、その設定が反映された結果を見分けるのに使う */
    fun setPreview(params: ShotParams): Int {
        val dev = checkNotNull(device) { "カメラが開いていません" }
        val s = checkNotNull(session) { "セッションがありません" }
        val gen = generation.incrementAndGet()
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(checkNotNull(previewSurface))
        requestFactory.applyCommon(b, params)
        b.setTag(gen)
        s.setRepeatingRequest(b.build(), previewCallback, handler)
        return gen
    }

    fun buildStill(
        params: ShotParams,
        outputs: List<OutputKind>,
        jpegQuality: Int,
        jpegOrientation: Int,
        maxResolution: Boolean = false,
    ): CaptureRequest {
        val dev = checkNotNull(device) { "カメラが開いていません" }
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        outputs.forEach { kind -> b.addTarget(checkNotNull(readers[kind]) { "$kind の出力がありません" }.surface) }
        requestFactory.applyCommon(b, params, maxResolution)
        requestFactory.applyStill(b, jpegQuality, jpegOrientation)
        return b.build()
    }

    /** 静止画を撮影し、各リクエストの結果を順番に返す */
    suspend fun capture(requests: List<CaptureRequest>): List<TotalCaptureResult> {
        val s = checkNotNull(session) { "セッションがありません" }
        return suspendCancellableCoroutine { cont ->
            val results = mutableListOf<TotalCaptureResult>()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    results += result
                    if (results.size == requests.size && cont.isActive) cont.resume(results.toList())
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("撮影に失敗しました (reason=${failure.reason})"))
                    }
                }
            }
            if (requests.size == 1) {
                s.capture(requests.single(), callback, handler)
            } else {
                s.captureBurst(requests, callback, handler)
            }
        }
    }

    /** 前回までに残っている画像を捨てる(撮影の直前に呼ぶ) */
    fun drainImages(kind: OutputKind) {
        val channel = imageChannels[kind] ?: return
        while (true) {
            val image = channel.tryReceive().getOrNull() ?: break
            image.close()
        }
    }

    /**
     * 画像を count 枚受け取る。受け取った画像は呼び出し側で必ず close する。
     * タイムアウトした場合は、受け取り済みの画像を close してから例外を投げる。
     */
    suspend fun awaitImages(kind: OutputKind, count: Int, timeoutMs: Long = 5_000): List<Image> {
        val channel = checkNotNull(imageChannels[kind]) { "$kind の出力がありません" }
        val received = mutableListOf<Image>()
        try {
            withTimeout(timeoutMs) {
                repeat(count) { received += channel.receive() }
            }
            return received
        } catch (e: Throwable) {
            received.forEach { it.close() }
            throw e
        }
    }

    fun outputSize(kind: OutputKind): SizeI? = readers[kind]?.let { SizeI(it.width, it.height) }

    /** カメラを閉じ、スレッドも止める。以降このインスタンスは使えない */
    fun release() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        closeReaders()
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cap.cameraId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                fail("カメラが切断されました(ほかのアプリが使用している可能性があります)")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                fail("カメラでエラーが発生しました (code=$error)")
            }

            private fun fail(message: String) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(message)) else _error.value = message
            }
        })
    }

    private suspend fun createSession(dev: CameraDevice, surface: Surface): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val outputs = mutableListOf(defaultOutput(surface))
            readers.forEach { (kind, reader) ->
                outputs += if (kind == OutputKind.MAX_RESOLUTION_JPEG) {
                    OutputConfiguration(reader.surface).apply {
                        addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    }
                } else {
                    defaultOutput(reader.surface)
                }
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("セッションの構成に失敗しました"))
                    }
                },
            )
            dev.createCaptureSession(config)
        }

    private fun defaultOutput(surface: Surface) = OutputConfiguration(surface).apply {
        if (cap.ultraHighResolution) addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
    }

    private fun createReaders(modes: Set<CaptureMode>) {
        closeReaders()
        addReader(OutputKind.JPEG, cap.stillSize(), ImageFormat.JPEG, maxImages = MAX_BURST + 2)
        if (CaptureMode.RAW in modes) {
            cap.rawSize()?.let { addReader(OutputKind.RAW, it, ImageFormat.RAW_SENSOR, maxImages = 2) }
        }
        if (CaptureMode.MAX_RESOLUTION in modes) {
            cap.maxResolutionStillSize()?.let { addReader(OutputKind.MAX_RESOLUTION_JPEG, it, ImageFormat.JPEG, maxImages = 2) }
        }
    }

    private fun addReader(kind: OutputKind, size: SizeI, format: Int, maxImages: Int) {
        val reader = ImageReader.newInstance(size.width, size.height, format, maxImages)
        val channel = Channel<Image>(Channel.UNLIMITED)
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireNextImage() }.getOrNull()
            if (image != null && channel.trySend(image).isFailure) image.close()
        }, handler)
        readers[kind] = reader
        imageChannels[kind] = channel
    }

    private fun closeReaders() {
        imageChannels.values.forEach { ch ->
            while (true) {
                ch.tryReceive().getOrNull()?.close() ?: break
            }
            ch.close()
        }
        imageChannels.clear()
        readers.values.forEach { runCatching { it.close() } }
        readers.clear()
    }

    companion object {
        const val MAX_BURST = 8
        private const val TAG = "CameraController"
    }
}
