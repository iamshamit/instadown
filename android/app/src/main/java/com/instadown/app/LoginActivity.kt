package com.instadown.app

import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app WebView that loads instagram.com. The user logs in normally;
 * we poll for Instagram's `ds_user_id` cookie (a non-HttpOnly marker
 * that proves a real session) and only close the activity once it
 * appears. This handles all intermediate flows (captcha, 2FA, PKC
 * errors) because the user is free to interact with the page until
 * Instagram decides to issue a session.
 */
class LoginActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Note: we deliberately do NOT call setWebContentsDebuggingEnabled.
        // It only matters for chrome://inspect during development, and
        // some pages (Instagram) appear to fingerprint enabled WebView
        // debugging and serve blank content.

        setContent {
            InstaDownTheme {
                val phase = remember { mutableStateOf(LoginPhase.LOADING_HOME) }

                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { Text("Sign in to Instagram") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                        )
                    },
                ) { padding ->
                    Box(
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    ) {
                        LoginWebView(
                            phase = phase,
                            provideView = { webView = it },
                            onCaptured = { count ->
                                Toast.makeText(
                                    this@LoginActivity,
                                    "Signed in. $count cookies saved.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                finish()
                            },
                            onError = { msg ->
                                Toast.makeText(
                                    this@LoginActivity,
                                    msg,
                                    Toast.LENGTH_LONG,
                                ).show()
                                finish()
                            },
                        )
                        StatusBanner(
                            phase = phase.value,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

/**
 * High-level phase of the login flow. We map the current URL to one
 * of these, but we never close the activity based on URL alone —
 * only once `ds_user_id` appears in the cookie jar.
 */
private enum class LoginPhase(val message: String?, val loading: Boolean) {
    LOADING_HOME("Loading Instagram…", true),
    ON_LOGIN_FORM(null, false),
    NEEDS_CAPTCHA(
        "Instagram is asking you to prove you're human. Complete the " +
            "challenge in the page above, then wait.",
        false,
    ),
    NEEDS_CHALLENGE(
        "Instagram needs to verify your identity. Follow the steps " +
            "in the page above.",
        false,
    ),
    ACCOUNT_BLOCKED(
        "This Instagram account is suspended or disabled. Use a " +
            "different account to sign in.",
        false,
    ),
    /** Polling for `ds_user_id` after the login form / captcha. */
    WAITING_FOR_SESSION(
        "Signing you in… wait for the confirmation.",
        true,
    ),
    SIGNED_IN("Signed in!", false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginWebView(
    phase: MutableState<LoginPhase>,
    provideView: (WebView) -> Unit,
    onCaptured: (Int) -> Unit,
    onError: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bridge = remember { PythonBridge(context.applicationContext) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = DESKTOP_UA
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    allowFileAccess = true
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        Log.d(TAG, "progress=$newProgress url=${view?.url}")
                    }
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        Log.d(TAG, "title=$title url=${view?.url}")
                    }
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        Log.d(TAG, "console: ${msg?.message()} @${msg?.lineNumber()}")
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        Log.d(TAG, "loading: $url")
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "finished: $url title=${view?.title}")
                        if (url == null) return

                        val cur = phase.value

                        // Login form / one-tap.
                        if (url.contains("/accounts/login") ||
                            url.contains("/accounts/onetap")) {
                            if (cur != LoginPhase.ON_LOGIN_FORM) {
                                phase.value = LoginPhase.ON_LOGIN_FORM
                            }
                            startPolling(this@apply, bridge, phase, onCaptured, onError)
                            return
                        }

                        // CAPTCHA challenge.
                        if (url.contains("/auth_platform/")) {
                            if (cur != LoginPhase.NEEDS_CAPTCHA) {
                                phase.value = LoginPhase.NEEDS_CAPTCHA
                            }
                            startPolling(this@apply, bridge, phase, onCaptured, onError)
                            return
                        }

                        // 2FA / security challenge.
                        if (url.contains("/challenge/") ||
                            url.contains("/checkpoint/") ||
                            url.contains("/accounts/activity/")) {
                            if (cur != LoginPhase.NEEDS_CHALLENGE) {
                                phase.value = LoginPhase.NEEDS_CHALLENGE
                            }
                            startPolling(this@apply, bridge, phase, onCaptured, onError)
                            return
                        }

                        // Account issues.
                        if (url.contains("/accounts/suspended") ||
                            url.contains("/accounts/disabled")) {
                            phase.value = LoginPhase.ACCOUNT_BLOCKED
                            view?.postDelayed({
                                onError("Instagram account is blocked.")
                            }, 2000)
                            return
                        }

                        // Home page during priming.
                        if (cur == LoginPhase.LOADING_HOME) {
                            Log.d(TAG, "primed; navigating to login")
                            phase.value = LoginPhase.ON_LOGIN_FORM
                            view?.post {
                                view.loadUrl("https://www.instagram.com/accounts/login/")
                            }
                            return
                        }

                        // Any other URL — might be the feed, a profile
                        // page, an error page, whatever. Don't capture
                        // based on URL; just start polling and wait for
                        // ds_user_id.
                        startPolling(this@apply, bridge, phase, onCaptured, onError)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        Log.w(TAG, "error code=$errorCode desc=$description url=$failingUrl")
                    }
                }

                provideView(this)
                loadUrl("https://www.instagram.com/")
            }
        },
    )
}

/**
 * Start (or restart) a background poll that checks for Instagram's
 * `ds_user_id` cookie once per second. Once the cookie appears we
 * know the user is signed in — capture all cookies (including the
 * HttpOnly `sessionid` via reflection) and close the activity.
 *
 * We poll every 1 s for up to 120 s. If the user is already signed
 * in (cookies from a previous login in the same WebView), the first
 * poll will fire immediately.
 */
private fun startPolling(
    view: WebView,
    bridge: PythonBridge,
    phase: MutableState<LoginPhase>,
    onCaptured: (Int) -> Unit,
    onError: (String) -> Unit,
) {
    // Use WebView's tag as a once-only gate so we don't start
    // competing poll loops (which would trigger on every
    // onPageFinished). IntCompanionObject works fine as a sentinel.
    if (view.tag == true) return
    view.tag = true

    if (phase.value !in listOf(
            LoginPhase.WAITING_FOR_SESSION,
            LoginPhase.SIGNED_IN,
        )
    ) {
        phase.value = LoginPhase.WAITING_FOR_SESSION
    }

    val cm = CookieManager.getInstance()
    val poll = object : Runnable {
        var attempts = 0
        override fun run() {
            attempts++
            cm.flush()
            val cookies = cm.getCookie("https://www.instagram.com/") ?: ""

            if (cookies.contains("ds_user_id=")) {
                Log.d(TAG, "ds_user_id found! capturing cookies.")
                phase.value = LoginPhase.SIGNED_IN
                val n = bridge.writeWebViewCookiesToFile()
                if (n > 0) {
                    view.postDelayed({ onCaptured(n) }, 800)
                } else {
                    onError("Cookies captured but file is empty. Try again.")
                }
                return
            }

            if (attempts >= 180) {
                Log.w(TAG, "waiting for ds_user_id timed out after 180 attempts")
                view.postDelayed({
                    onError("Sign in timed out. Please try again, or sign out first if you're already signed in.")
                }, 2000)
                return
            }

            view.postDelayed(this, 1000)
        }
    }
    view.postDelayed(poll, 2000)
}

@Composable
private fun StatusBanner(
    phase: LoginPhase,
    modifier: Modifier = Modifier,
) {
    if (phase.loading) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
        return
    }
    val msg = phase.message ?: return
    val color = when (phase) {
        LoginPhase.SIGNED_IN -> MaterialTheme.colorScheme.primaryContainer
        LoginPhase.ACCOUNT_BLOCKED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color,
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val TAG = "InstaDown.Login"

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/148.0.0.0 Safari/537.36"
