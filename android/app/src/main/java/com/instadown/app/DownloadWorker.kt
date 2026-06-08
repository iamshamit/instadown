package com.instadown.app

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_SELECTED = "selected_indices"
    }

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif")

    override suspend fun getForegroundInfo(): ForegroundInfo =
        makeForegroundInfo(0, 0)

    override suspend fun doWork(): Result {
        return try {
            doWorkInternal()
        } catch (e: Exception) {
            NotificationHelper.showFailure(applicationContext, "Worker error: ${e::class.simpleName}: ${e.message}")
            Result.failure()
        }
    }

    private suspend fun doWorkInternal(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val selectedRaw = inputData.getString(KEY_SELECTED) ?: ""
        val selectedSet: Set<Int> = if (selectedRaw.isBlank()) emptySet()
            else selectedRaw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        val totalCount = selectedSet.size

        val bridge = PythonBridge(applicationContext)
        if (!bridge.cookiesFile().exists()) {
            NotificationHelper.showFailure(applicationContext, "Not signed in — open Settings and sign in first")
            return Result.failure()
        }

        val outDir = File(applicationContext.cacheDir, "dl_${id}")

        // Promote to foreground service so Android doesn't kill us mid-download
        try {
            setForeground(makeForegroundInfo(0, totalCount))
        } catch (e: Exception) {
            // setForeground can fail on some devices; fall back to a plain notification
            NotificationHelper.showProgress(applicationContext, 0, totalCount)
        }

        // Run download and progress poller as structured siblings so the poller
        // is always cancelled when download finishes (or if the worker is cancelled).
        val result = coroutineScope {
            val pollJob = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(800)
                    val done = outDir.walkTopDown()
                        .count { it.isFile && it.extension.lowercase() in imageExts }
                    if (done > 0) {
                        try {
                            setForeground(makeForegroundInfo(done, totalCount))
                        } catch (_: Exception) {
                            NotificationHelper.showProgress(applicationContext, done, totalCount)
                        }
                    }
                }
            }
            val r = withContext(Dispatchers.IO) { bridge.download(url, outDir) }
            pollJob.cancel()
            r
        }

        if (!result.success) {
            NotificationHelper.showFailure(applicationContext, result.error ?: "unknown error")
            outDir.deleteRecursively()
            return Result.failure()
        }

        val toSave = if (selectedSet.isEmpty()) result.files
            else result.files.filterIndexed { index, _ -> index in selectedSet }

        var saved = 0
        var lastSaveError: String? = null
        toSave.forEach { file ->
            try {
                SaveHelper.saveToDownloads(applicationContext, File(file.path))
                saved++
            } catch (e: Exception) {
                lastSaveError = "${e::class.simpleName}: ${e.message}"
            }
        }

        outDir.deleteRecursively()

        if (saved == 0) {
            NotificationHelper.showFailure(applicationContext, "Save failed: ${lastSaveError ?: "no files returned"}")
            return Result.failure()
        }

        NotificationHelper.showComplete(applicationContext, saved)
        return Result.success()
    }

    private fun makeForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val notif = NotificationHelper.buildProgress(applicationContext, current, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationHelper.NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationHelper.NOTIF_ID, notif)
        }
    }
}
