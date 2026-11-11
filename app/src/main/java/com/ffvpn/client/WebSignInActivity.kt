package com.ffvpn.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ffvpn.client.ui.FirefoxVpnTheme
import com.ffvpn.client.vpn.Logs
import com.ffvpn.client.vpn.ServerList
import com.ffvpn.client.vpn.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Mozilla's own sign-in page, inside the app. The page runs with the Firefox
 * WebChannel that Firefox desktop uses, so it handles everything Mozilla asks
 * for — the password, a code, the confirmation, and an image CAPTCHA — and
 * reports the session it opened.
 *
 * The page reports the session right after the password, before Mozilla has
 * confirmed it, so the page stays open while the engine waits for the
 * confirmation (an emailed code, or the approval on the page). Only once the
 * account answers does this screen return, with the token.
 */
class WebSignInActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startEmail = Store.email(this).orEmpty()
        setContent {
            FirefoxVpnTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WebSignInScreen(
                        startEmail = startEmail,
                        onSignedIn = { token, email -> deliver(token, email) }
                    )
                }
            }
        }
    }

    /** Returns the confirmed session to whoever launched this activity. */
    private fun deliver(token: String, email: String) {
        setResult(RESULT_OK, Intent().putExtra("token", token).putExtra("email", email))
        finish()
    }
}

@Composable
private fun WebSignInScreen(startEmail: String, onSignedIn: (String, String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? WebSignInActivity
    var loading by remember { mutableStateOf(true) }
    var waiting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<Pair<String, String>?>(null) }
    val page = remember {
        SignInPage(startEmail) { token, email -> session = token to email }
    }

    BackHandler {
        if (!page.goBack()) activity?.finish()
    }

    // The session is only usable once Mozilla confirms it, and that can take a
    // code typed on the page or a link opened from the email: ask the engine
    // until it accepts, while the page stays open in front of the person.
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        waiting = true
        problem = null
        Logs.add("sign-in", "session captured for ${current.second}")
        var last: String? = null
        var attempt = 0
        while (attempt < CONFIRM_ATTEMPTS) {
            attempt++
            val binary = Store.binary(context)
            last = if (!binary.exists()) {
                context.getString(R.string.error_engine_missing)
            } else {
                withContext(Dispatchers.IO) {
                    ServerList.verifyToken(binary, Store.workDir(context), current.first) { }
                }
            }
            if (last == null) {
                waiting = false
                onSignedIn(current.first, current.second)
                return@LaunchedEffect
            }
            // Anything but "not confirmed yet" will not be fixed by waiting.
            if (!looksUnconfirmed(last.orEmpty())) break
            if (attempt == 1) Logs.add("sign-in", "not confirmed yet: ${last.take(160)}")
            delay(CONFIRM_RETRY_MS)
        }
        waiting = false
        problem = last ?: context.getString(R.string.sign_in_failed)
        Logs.add("sign-in", "confirmation failed: ${last.orEmpty().take(160)}")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.web_sign_in_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { activity?.finish() }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        Text(
            text = stringResource(R.string.web_sign_in_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
        )
        if (loading || waiting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (waiting) {
            Text(
                text = stringResource(R.string.web_sign_in_confirming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }
        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }
        AndroidView(
            factory = { view -> page.create(view) { loading = false } },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * The browser view and its WebChannel bridge. Mozilla's page sends what it
 * needs over `WebChannelMessageToChrome` and reads the answers from
 * `WebChannelMessageToContent`; the bridge relays both and lifts the session
 * out of the `fxaccounts:login` message.
 */
private class SignInPage(
    private val startEmail: String,
    private val onSession: (String, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var lastToken = ""

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, onLoaded: () -> Unit): WebView {
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // accounts.firefox.com only opens its WebChannel to a Firefox browser.
        view.settings.userAgentString = FIREFOX_UA
        view.addJavascriptInterface(Bridge(), "FxaBridge")
        view.webViewClient = object : WebViewClient() {
            // Installed twice: early, and again for a page whose start was missed.
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(HOOK_JS, null)
                onLoaded()
            }
        }
        web = view
        view.loadUrl(loginUrl())
        return view
    }

    /** True while the page itself can go back, so Back stays inside it. */
    fun goBack(): Boolean {
        val view = web ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    /** The sign-in page Firefox desktop opens for Sync; it signs up too. */
    private fun loginUrl(): String {
        val builder = Uri.parse(SIGN_IN_PAGE).buildUpon()
            .appendQueryParameter("context", "fx_desktop_v3")
            .appendQueryParameter("service", "sync")
            .appendQueryParameter("action", "email")
        if (startEmail.isNotBlank()) builder.appendQueryParameter("email", startEmail)
        return builder.build().toString()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(raw: String) {
            val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
            if (envelope.optString("kind") != "webchannel") return
            val detail = envelope.optJSONObject("detail") ?: return
            val id = detail.optString("id")
            val body = detail.optJSONObject("message") ?: return
            when (val command = body.optString("command")) {
                // The page asks whether a user is signed in and whether linking
                // this account is fine; the browser answers for the person.
                "fxaccounts:fxa_status" -> reply(
                    id,
                    body.opt("messageId"),
                    command,
                    JSONObject()
                        .put("capabilities", JSONObject())
                        .put("signedInUser", JSONObject.NULL)
                )

                "fxaccounts:can_link_account" -> reply(
                    id,
                    body.opt("messageId"),
                    command,
                    JSONObject().put("ok", true)
                )

                "fxaccounts:login", "fxaccounts:oauth_login" -> login(body.optJSONObject("data"))
            }
        }
    }

    private fun reply(id: String, messageId: Any?, command: String, data: JSONObject) {
        val js = "window.__fxaReply(" + JSONObject.quote(id) + ", " + jsValue(messageId) + ", " +
            JSONObject.quote(command) + ", " + data.toString() + ")"
        handler.post { web?.evaluateJavascript(js, null) }
    }

    /** A session, once: the page repeats its message after confirmation. */
    private fun login(data: JSONObject?) {
        if (data == null) return
        val token = data.optString("sessionToken").ifBlank { data.optString("token") }
        if (!TOKEN_PATTERN.matcher(token).matches() || token == lastToken) return
        lastToken = token
        val email = data.optString("email").ifBlank { startEmail }
        handler.post { onSession(token, email) }
    }

    private companion object {
        const val SIGN_IN_PAGE = "https://accounts.firefox.com/"
        const val FIREFOX_UA =
            "Mozilla/5.0 (Android 14; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"

        val TOKEN_PATTERN: Pattern = Pattern.compile("^[0-9a-f]{64}$")

        /** A JSON value for the injected call, keeping numbers unquoted. */
        fun jsValue(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

        val HOOK_JS = """
            (function () {
              if (window.__fxaHooked) return;
              window.__fxaHooked = true;
              var send = function (o) { try { FxaBridge.post(JSON.stringify(o)); } catch (e) {} };
              var onChannel = function (e) {
                var d = e.detail;
                if (typeof d === 'string') { try { d = JSON.parse(d); } catch (err) { return; } }
                if (d && d.message) send({ kind: 'webchannel', detail: d });
              };
              window.addEventListener('WebChannelMessageToChrome', onChannel);
              document.addEventListener('WebChannelMessageToChrome', onChannel);
              window.__fxaReply = function (id, messageId, command, data) {
                var detail = { id: id, message: { messageId: messageId, command: command, data: data } };
                window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
                document.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
              };
            })();
        """.trimIndent()
    }
}

/**
 * Whether the engine refused the session only because Mozilla has not confirmed
 * it yet. The auth server says `errno 138 unconfirmed session`; a fresh account
 * whose address is still unverified says 201.
 */
private fun looksUnconfirmed(problem: String): Boolean =
    problem.contains("errno 138") ||
        problem.contains("errno 201") ||
        problem.contains("unconfirmed", ignoreCase = true) ||
        problem.contains("unverified", ignoreCase = true) ||
        problem.contains("not verified", ignoreCase = true)

/** How long a session that is waiting for confirmation is retried. */
private const val CONFIRM_ATTEMPTS = 60
private const val CONFIRM_RETRY_MS = 4_000L
