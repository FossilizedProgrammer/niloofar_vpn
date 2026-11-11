package com.ffvpn.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
 * Firefox Account sign-in. The engine does the work — it also clears Mozilla's
 * edge challenge and handles the confirmation email — so this screen only
 * collects the credentials, relays the codes the engine asks for, and hands the
 * cached token back to the caller.
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

@Composable
private fun SignInScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? SignInActivity

    var email by remember { mutableStateOf(Store.email(context).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var unblock by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<LoginPrompt?>(null) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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
        code = ""
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
            error = problem ?: context.getString(R.string.sign_in_failed)
        }
    }

    fun cancel() {
        login?.cancel()
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

        val asking = prompt
        if (asking != null) {
            Text(
                text = stringResource(
                    if (asking == LoginPrompt.Totp) R.string.sign_in_totp_title
                    else R.string.sign_in_password
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(
                    if (asking == LoginPrompt.Totp) R.string.sign_in_totp_hint
                    else R.string.sign_in_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (asking == LoginPrompt.Totp) KeyboardType.Number
                    else KeyboardType.Password
                ),
                visualTransformation = if (asking == LoginPrompt.Totp) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    login?.answer(code)
                    code = ""
                    prompt = null
                    status = context.getString(R.string.sign_in_working)
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_continue))
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
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = unblock,
                onValueChange = { unblock = it },
                label = { Text(stringResource(R.string.sign_in_unblock)) },
                supportingText = { Text(stringResource(R.string.sign_in_unblock_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { start() },
                enabled = !busy,
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
                    stringResource(
                        if (busy) R.string.sign_in_working else R.string.sign_in_action
                    )
                )
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

        TextButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

private const val TOKEN_LENGTH = 64
