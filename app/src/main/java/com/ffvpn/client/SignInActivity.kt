package com.ffvpn.client

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ffvpn.client.ui.FirefoxVpnTheme
import com.ffvpn.client.vpn.FxaLogin
import com.ffvpn.client.vpn.LoginPrompt
import com.ffvpn.client.vpn.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Firefox Account sign-in and account creation. The engine does the login — it
 * also clears Mozilla's edge challenge and handles the confirmation email — so
 * this screen collects the credentials, relays the codes the engine asks for,
 * points new users at Mozilla's sign-up page, and hands the cached token back
 * to the caller.
 */
class SignInActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FirefoxVpnTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SignInScreen()
                }
            }
        }
    }

    /** Returns the cached session to whoever launched this activity. */
    internal fun deliver(token: String, email: String) {
        setResult(RESULT_OK, Intent().putExtra("token", token).putExtra("email", email))
        finish()
    }
}

/** Which half of the screen is showing before a sign-in starts. */
private enum class Mode { SignIn, Create }

@Composable
private fun SignInScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? SignInActivity

    // The create tab opens Mozilla's own page inside the app, so the address is
    // typed once, there, and the session comes back by itself.
    val webSignIn = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val token = data.getStringExtra("token").orEmpty()
            val mail = data.getStringExtra("email").orEmpty().ifBlank { Store.email(context).orEmpty() }
            if (token.length >= TOKEN_LENGTH) activity?.deliver(token, mail)
        }
    }

    var mode by remember { mutableStateOf(Mode.SignIn) }
    var email by remember { mutableStateOf(Store.email(context).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var unblock by remember { mutableStateOf("") }
    var showUnblock by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<LoginPrompt?>(null) }
    var captcha by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<FxaLogin?>(null) }

    // Leaving the screen kills any engine still waiting for a code.
    DisposableEffect(Unit) {
        onDispose { login?.cancel() }
    }

    fun start() {
        val binary = Store.binary(context)
        if (!binary.exists()) {
            error = context.getString(R.string.error_engine_missing)
            return
        }
        if (email.isBlank() || password.isBlank()) {
            error = context.getString(R.string.sign_in_missing)
            return
        }
        val address = email.trim()
        val driver = FxaLogin(binary, Store.workDir(context))
        login = driver
        error = null
        detail = ""
        code = ""
        captcha = ""
        prompt = null
        busy = true
        status = context.getString(R.string.sign_in_working)

        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                driver.signIn(
                    email = address,
                    password = password,
                    unblockCode = unblock.trim(),
                    onPrompt = { prompt = it },
                    onCaptcha = { captcha = it },
                    onStatus = { line ->
                        status = if (line.contains("confirmation email", ignoreCase = true)) {
                            context.getString(R.string.sign_in_confirm)
                        } else {
                            line
                        }
                    }
                )
            }
            busy = false
            login = null
            if (problem == null) {
                val token = runCatching { Store.sessionFile(context).readText().trim() }
                    .getOrDefault("")
                if (token.length >= TOKEN_LENGTH) {
                    activity?.deliver(token, address)
                    return@launch
                }
            }
            prompt = null
            val failure = describeFailure(context, problem)
            error = failure.first
            detail = failure.second
            // The engine reports a blocked sign-in before it can continue; the
            // code field is the only way forward, so show it right away.
            if (problem.orEmpty().contains("errno 125")) showUnblock = true
        }
    }

    fun cancel() {
        login?.cancel()
        showUnblock = false
        activity?.finish()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.sign_in_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.sign_in_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (prompt == null && !showUnblock) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ModeTab(
                    label = R.string.action_sign_in,
                    selected = mode == Mode.SignIn,
                    modifier = Modifier.weight(1f)
                ) {
                    mode = Mode.SignIn
                    error = null
                    detail = ""
                }
                ModeTab(
                    label = R.string.sign_in_create_tab,
                    selected = mode == Mode.Create,
                    modifier = Modifier.weight(1f)
                ) {
                    mode = Mode.Create
                    error = null
                    detail = ""
                }
            }
        }

        val asking = prompt
        if (asking != null) {
            Text(
                text = stringResource(
                    when (asking) {
                        LoginPrompt.Totp -> R.string.sign_in_totp_title
                        LoginPrompt.Captcha -> R.string.sign_in_captcha_title
                        else -> R.string.sign_in_password
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(
                    when (asking) {
                        LoginPrompt.Totp -> R.string.sign_in_totp_hint
                        LoginPrompt.Captcha -> R.string.sign_in_captcha_hint
                        else -> R.string.sign_in_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (asking == LoginPrompt.Captcha) {
                CaptchaImage(dataUrl = captcha)
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (asking) {
                        LoginPrompt.Totp -> KeyboardType.Number
                        LoginPrompt.Captcha -> KeyboardType.Text
                        else -> KeyboardType.Password
                    }
                ),
                visualTransformation = if (asking == LoginPrompt.Totp || asking == LoginPrompt.Captcha) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    login?.answer(code)
                    code = ""
                    captcha = ""
                    prompt = null
                    error = null
                    detail = ""
                    status = context.getString(R.string.sign_in_working)
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_continue))
            }
        } else if (mode == Mode.Create) {
            Text(
                text = stringResource(R.string.create_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.create_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { webSignIn.launch(Intent(context, WebSignInActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.create_open))
            }
            TextButton(
                onClick = {
                    mode = Mode.SignIn
                    error = null
                    detail = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.create_have_account))
            }
        } else if (showUnblock) {
            Text(
                text = stringResource(R.string.sign_in_unblock),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.sign_in_unblock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = unblock,
                onValueChange = { unblock = it },
                label = { Text(stringResource(R.string.sign_in_unblock)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(busy = busy, enabled = unblock.isNotBlank()) { start() }
            TextButton(
                onClick = { showUnblock = false; unblock = "" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.sign_in_email)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.sign_in_password)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        enabled = !busy
                    ) {
                        Icon(
                            imageVector = if (showPassword) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = stringResource(
                                if (showPassword) R.string.sign_in_hide_password
                                else R.string.sign_in_show_password
                            )
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(busy = busy, enabled = true) { start() }
            TextButton(
                onClick = { showUnblock = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sign_in_have_code))
            }
        }

        if (status.isNotBlank()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        TextButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/**
 * The picture Mozilla's anti-bot check asks the person to read. The engine
 * hands it over as a data URL, so nothing is fetched here.
 */
@Composable
private fun CaptchaImage(dataUrl: String) {
    val bitmap = remember(dataUrl) { decodeDataUrl(dataUrl) }
    if (bitmap == null) {
        Text(
            text = stringResource(R.string.sign_in_captcha_broken),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.sign_in_captcha_title),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
    )
}

/** Decodes a `data:image/...;base64,` URL into a bitmap, or null if unusable. */
private fun decodeDataUrl(dataUrl: String): android.graphics.Bitmap? {
    val payload = dataUrl.substringAfter("base64,", "")
    if (payload.isEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

/** The sign-in / create-account switch: the selected half carries the accent. */
@Composable
private fun ModeTab(
    label: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        )
    }
}

/** The submit button, with the engine's progress spinner inside it. */
@Composable
private fun PrimaryButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy && enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(
            stringResource(if (busy) R.string.sign_in_working else R.string.sign_in_action)
        )
    }
}

/**
 * Turns the engine's failure line into something the user can act on. The raw
 * line is kept as a second, quieter line: it names Mozilla's error when the
 * wording here does not fit.
 */
private fun describeFailure(context: Context, problem: String?): Pair<String, String> {
    val raw = problem.orEmpty().trim()
    val friendly = when {
        // Mozilla's edge asks for an image CAPTCHA it refuses to skip; the
        // engine can only solve the proof-of-work kind.
        raw.contains("CAPTCHA", ignoreCase = true) ||
            raw.contains("edge protection", ignoreCase = true) ||
            raw.contains("HTTP 406", ignoreCase = true) -> R.string.sign_in_err_blocked
        raw.contains("errno 103") || raw.contains("Incorrect password", ignoreCase = true) ->
            R.string.sign_in_err_password
        raw.contains("errno 125") -> R.string.sign_in_err_unblock
        raw.contains("errno 201") -> R.string.sign_in_err_unverified
        raw.contains("errno 138") -> R.string.sign_in_err_unconfirmed
        raw.contains("errno 102") -> R.string.sign_in_err_unknown
        raw.contains("timed out", ignoreCase = true) -> R.string.sign_in_err_timeout
        raw.contains("request to", ignoreCase = true) ||
            raw.contains("handshake", ignoreCase = true) ||
            raw.contains("HTTP 5", ignoreCase = true) -> R.string.sign_in_err_network
        else -> R.string.sign_in_failed
    }
    val message = context.getString(friendly)
    return message to if (raw.isNotBlank() && raw != message) raw.take(180) else ""
}

private const val TOKEN_LENGTH = 64
