package com.studentanalysis.zoomcapture.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * アプリが閉じられた後に、送り残しを送る。WorkManager の1回の実行時間には上限があるが、
 * SessionUploader は途中から再開できるので、送り終わるまで retry で繰り返せばよい。
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = UploadManager.get(applicationContext)
        repeat(MAX_ROUNDS) {
            when (manager.drainOnce()) {
                UploadManager.Drain.IDLE -> return Result.success()
                UploadManager.Drain.MORE -> Unit
                UploadManager.Drain.WAITING, UploadManager.Drain.ERROR -> return Result.retry()
            }
        }
        return Result.retry()
    }

    private companion object {
        const val MAX_ROUNDS = 1_000
    }
}
