package com.instadown.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Copy a downloaded file from the app's cache into the public
 * Downloads/InstaDown/ folder so the user can find it in their
 * file manager and Gallery.
 *
 * Uses MediaStore on Android 10+ (scoped storage) and the legacy
 * direct-file path on Android 9 and below (where we have
 * WRITE_EXTERNAL_STORAGE).
 */
object SaveHelper {

    private const val SUBDIR = "InstaDown"

    /** Copy [src] to Downloads/InstaDown/ and return the resulting Uri. */
    fun saveToDownloads(context: Context, src: File): Uri {
        require(src.isFile) { "source not found: $src" }
        val mime = guessMime(src.name)
        val name = makeUniqueName(src.name)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, src, name, mime)
        } else {
            saveLegacy(context, src, name)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore.insert returned null")

        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error("could not open output stream for $uri")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, src: File, name: String): Uri {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloads, SUBDIR).apply { mkdirs() }
        val target = uniqueFile(File(targetDir, name))
        src.copyTo(target, overwrite = true)

        // Make the file visible to the file manager / Gallery.
        context.sendBroadcast(
            android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                .setData(Uri.fromFile(target))
        )
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target,
        )
    }

    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = target.extension
        val parent = target.parentFile ?: return target
        var i = 1
        while (true) {
            val candidate = File(parent, "${base}_$i.$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun makeUniqueName(name: String): String {
        // MediaStore's UNIQUE constraint will rename automatically, so
        // we just pass the original name. If it conflicts, the system
        // appends " (1)", " (2)", etc.
        return name
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }
}
