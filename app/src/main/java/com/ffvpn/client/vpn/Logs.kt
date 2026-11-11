package com.ffvpn.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the in-app log. */
data class LogLine(val time: String, val tag: String, val message: String) {
    override fun toString(): String = "$time $tag: $message"
}

/**
 * What the app and the engine did in this run, for the log screen in Settings.
 * It stays in memory only: enough to see why a connect or a sign-in went the
 * way it did, and nothing left behind on the device.
 */
object Logs {
    private const val LIMIT = 500
    private const val MAX_CHARS = 400

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    @Synchronized
    fun add(tag: String, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val line = LogLine(clock.format(Date()), tag, text.take(MAX_CHARS))
        _lines.value = (_lines.value + line).takeLast(LIMIT)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    /** The whole log as plain text, for sharing it out of the app. */
    fun text(): String = _lines.value.joinToString("\n")
}
