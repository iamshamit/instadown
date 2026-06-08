package com.instadown.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL = "url"
        // Comma-separated selected indices, e.g. "0,2,3". Empty string = save all.
        const val KEY_SELECTED = "selected_indices"
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val selectedRaw = inputData.getString(KEY_SELECTED) ?: ""
        val selectedSet: Set<Int> = if (selectedRaw.isBlank()) emptySet()
            else selectedRaw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

        val bridge = PythonBridge(applicationContext)
        if (!bridge.cookiesFile().exists()) {
            NotificationHelper.showFailure(applicationContext, url)
            return Result.failure()
        }

        val result = bridge.download(url)
        if (!result.success) {
            NotificationHelper.showFailure(applicationContext, url)
            return Result.failure()
        }

        val toSave = if (selectedSet.isEmpty()) result.files
            else result.files.filterIndexed { index, _ -> index in selectedSet }

        NotificationHelper.showProgress(applicationContext, toSave.size)

        var saved = 0
        toSave.forEach { file ->
            try {
                SaveHelper.saveToDownloads(applicationContext, File(file.path))
                saved++
            } catch (_: Exception) {}
        }

        if (saved == 0) {
            NotificationHelper.showFailure(applicationContext, url)
            return Result.failure()
        }

        NotificationHelper.showComplete(applicationContext, saved)
        return Result.success()
    }
}
