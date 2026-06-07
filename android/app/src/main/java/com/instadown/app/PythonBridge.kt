package com.instadown.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.PyObject
import org.json.JSONObject
import java.io.File

/**
 * Bridge to the embedded Python interpreter (Chaquopy).
 *
 * Python code lives in `app/src/main/python/instadown_android.py`.
 * We start Python on first use, then call `instadown_android.download`
 * and convert the returned dict into a typed [DownloadResult].
 */
class PythonBridge(private val context: Context) {

    companion object {
        private val lock = Any()
        private var pythonStarted = false

        fun ensurePythonStarted(context: Context) {
            if (pythonStarted) return
            synchronized(lock) {
                if (pythonStarted) return
                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(context))
                        Log.d("InstaDown.Bridge", "Python started")
                    }
                    pythonStarted = true
                } catch (e: Exception) {
                    Log.e("InstaDown.Bridge", "Python start failed", e)
                    // Fall through — let the caller hit the real error
                }
            }
        }
    }

    /** Lazily acquire the Python module so the first activity launch isn't slow. */
    private val module: PyObject by lazy {
        ensurePythonStarted(context)
        Python.getInstance().getModule("instadown_android")
    }

    /**
     * Download an Instagram post / carousel.
     *
     * Returns a [DownloadResult] with either a list of file paths or
     * an error message. Never throws — all exceptions are caught
     * inside Python and surfaced as `DownloadResult(success=false)`.
     */
    fun download(url: String): DownloadResult {
        val cookiesFile = cookiesFile()
        if (!cookiesFile.exists()) {
            return DownloadResult(
                success = false,
                error = "Not signed in. Open Settings and sign in to Instagram first.",
            )
        }

        val outDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        // Clean previous run's files so we don't accumulate junk.
        outDir.listFiles()?.forEach { it.delete() }

        val raw: PyObject = try {
            module.callAttr("download", url, cookiesFile.absolutePath, outDir.absolutePath)
        } catch (t: Throwable) {
            return DownloadResult(success = false, error = "Python call failed: ${t.message}")
        }

        val json = raw.toString()
        return try {
            val obj = JSONObject(json)
            val ok = obj.optBoolean("ok", false)
            if (!ok) {
                return DownloadResult(success = false, error = obj.optString("error", "unknown error"))
            }
            val arr = obj.getJSONArray("files")
            val files = (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                DownloadedFile(
                    name = f.getString("name"),
                    path = f.getString("path"),
                    sizeBytes = f.getLong("size"),
                    sizeHuman = f.getString("size_human"),
                )
            }
            DownloadResult(success = true, files = files)
        } catch (t: Throwable) {
            DownloadResult(success = false, error = "Bad response from Python: $json")
        }
    }

    /**
     * The Netscape cookies.txt file we write from the LoginActivity
     * WebView. Always under the app's private files dir so we don't
     * need external storage permissions.
     */
    fun cookiesFile(): File = File(context.filesDir, "instagram-cookies.txt")

    /**
     * True if the cookies file exists AND contains a `sessionid`
     * cookie. The 6 anonymous cookies Instagram sets on the home page
     * (csrftoken, datr, ig_did, mid, dpr, wd) are not enough to
     * authenticate gallery-dl — we need sessionid.
     */
    fun hasSessionCookie(): Boolean {
        val f = cookiesFile()
        if (!f.exists()) return false
        return f.readText().lineSequence().any { line ->
            line.startsWith("#") == false && line.split('\t').getOrNull(5) == "sessionid"
        }
    }

    /**
     * Capture the WebView's session cookies for instagram.com and
     * write them out in the Netscape cookies.txt format that
     * gallery-dl expects.
     *
     * Called by [LoginActivity] when the WebView has logged in.
     * No-op if there are no instagram.com cookies.
     *
     * The public [CookieManager.getCookie] strips HttpOnly cookies — but
     * Instagram's `sessionid` is HttpOnly, and without it gallery-dl is
     * not authenticated. The Chromium implementation has a private
     * overload `getCookie(url, includeHttpOnly)` that we call via
     * reflection to recover them.
     */
    fun writeWebViewCookiesToFile(): Int {
        val cm = CookieManager.getInstance()
        // Flush so any in-memory cookies are committed before we read.
        cm.flush()

        // Collect cookies across every domain the login flow touches.
        val domains = listOf(
            "https://www.instagram.com/",
            "https://instagram.com/",
            "https://i.instagram.com/",
            "https://api.instagram.com/",
        )

        val seen = mutableSetOf<String>()
        val allCookies = mutableListOf<Pair<String, String>>()
        for (d in domains) {
            val raw = getCookieIncludingHttpOnly(cm, d) ?: continue
            for (pair in raw.split(';')) {
                val trimmed = pair.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isEmpty() || name in seen) continue
                seen.add(name)
                allCookies += name to value
            }
        }

        if (allCookies.isEmpty()) return 0

        android.util.Log.d(
            "InstaDown.Bridge",
            "captured cookies: ${allCookies.map { it.first }.sorted().joinToString(",")}",
        )

        val out = cookiesFile()
        out.parentFile?.mkdirs()

        // Netscape format: domain  flag  path  secure  expiration  name  value
        // Tab-separated, one cookie per line. We treat all cookies as
        // session cookies (expiration=0) since gallery-dl doesn't
        // validate it.
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        sb.append("# https://curl.haxx.se/rfc/cookie_spec.html\n")
        sb.append("# This is a generated file! Do not edit.\n\n")

        for ((name, value) in allCookies) {
            sb.append(".instagram.com\tTRUE\t/\tTRUE\t0\t")
                .append(name).append('\t').append(value).append('\n')
        }

        out.writeText(sb.toString())
        return allCookies.size
    }

    /**
     * Call Chromium's private `getCookie(url, includeHttpOnly)` so we
     * can recover HttpOnly cookies (Instagram's `sessionid` is one of
     * these). Falls back to the public [CookieManager.getCookie] if
     * the reflection fails (e.g. on a future Chromium that renames
     * the API).
     */
    private fun getCookieIncludingHttpOnly(cm: CookieManager, url: String): String? {
        return try {
            val method = CookieManager::class.java.getDeclaredMethod(
                "getCookie",
                String::class.java,
                java.lang.Boolean.TYPE,
            )
            method.isAccessible = true
            method.invoke(cm, url, true) as? String
        } catch (_: Throwable) {
            // Reflection failed — fall back to the public API which
            // strips HttpOnly. Better than nothing.
            try {
                cm.getCookie(url)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Wipe the cookies file (used by Settings → Sign out). */
    fun clearCookies() {
        cookiesFile().delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

data class DownloadedFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val sizeHuman: String,
)

data class DownloadResult(
    val success: Boolean,
    val files: List<DownloadedFile> = emptyList(),
    val error: String? = null,
)
