package com.ffvpn.client.core

import android.content.Context
import android.content.SharedPreferences
import com.ffvpn.client.vpn.Store
import java.util.Locale

enum class AppLanguage(val code: String) {
    EN("en"),
    FA("fa"),
    RU("ru"),
    ES("es"),
    ZH("zh")
}

/** Only Persian is laid out right to left; the rest share the LTR layout. */
val AppLanguage.rtl: Boolean get() = this == AppLanguage.FA

object Prefs {
    private const val NAME = "settings"
    private const val KEY_MODE = "mode"
    private const val KEY_CORE = "core"
    private const val KEY_SHARE_LAN = "share_lan"
    private const val KEY_CONNECT_SOUND = "connect_sound"
    private const val KEY_AUTO_RETRY = "auto_retry"
    private const val KEY_TOR_BRIDGES = "tor_bridges"
    private const val KEY_PSIPHON_CONDUIT = "psiphon_conduit"
    private const val KEY_TOOLS_REPLACE = "tools_replace"
    private const val KEY_VPNGATE_FILTER = "vpngate_filter"
    private const val KEY_SOCKS_PORT = "socks_port"
    private const val KEY_HTTP_PORT = "http_port"

    const val LOOPBACK = "127.0.0.1"
    const val DEFAULT_SOCKS_PORT = 1080
    const val DEFAULT_HTTP_PORT = 8080
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    @Volatile
    private var store: SharedPreferences? = null

    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        store = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences =
        store ?: error("Prefs.init() was not called")

    var mode: AppState.Mode
        get() = runCatching {
            AppState.Mode.valueOf(prefs().getString(KEY_MODE, null).orEmpty())
        }.getOrDefault(AppState.Mode.VPN)
        set(value) {
            prefs().edit().putString(KEY_MODE, value.name).apply()
        }

    /** Empty means "whatever the registry lists first"; the picker always writes an id. */
    var coreId: String
        get() = prefs().getString(KEY_CORE, "").orEmpty()
        set(value) {
            prefs().edit().putString(KEY_CORE, value).apply()
        }

    /**
     * The language picker lives in Store, and its empty tag means "follow the
     * system". This maps that choice onto the four languages the ported screens
     * speak.
     */
    var language: AppLanguage
        get() = AppLanguage.entries.firstOrNull { it.code == resolvedTag() } ?: AppLanguage.EN
        set(value) {
            app?.let { Store.setLanguage(it, value.code) }
        }

    private fun resolvedTag(): String {
        val chosen = app?.let { Store.language(it) }.orEmpty()
        val tag = chosen.ifBlank { Locale.getDefault().language }
        return tag.lowercase(Locale.ROOT).take(2)
    }

    var shareLan: Boolean
        get() = prefs().getBoolean(KEY_SHARE_LAN, false)
        set(value) {
            prefs().edit().putBoolean(KEY_SHARE_LAN, value).apply()
        }

    var connectSound: Boolean
        get() = prefs().getBoolean(KEY_CONNECT_SOUND, true)
        set(value) {
            prefs().edit().putBoolean(KEY_CONNECT_SOUND, value).apply()
        }

    var autoRetry: Boolean
        get() = prefs().getBoolean(KEY_AUTO_RETRY, true)
        set(value) {
            prefs().edit().putBoolean(KEY_AUTO_RETRY, value).apply()
        }

    /** Tor only gets past a blocking network through obfs4 bridges. */
    var torUseBridges: Boolean
        get() = prefs().getBoolean(KEY_TOR_BRIDGES, false)
        set(value) {
            prefs().edit().putBoolean(KEY_TOR_BRIDGES, value).apply()
        }

    /**
     * Off by default: Psiphon's own servers are faster where they are reachable.
     * Conduit is for the links where every one of them is blocked.
     */
    var psiphonConduit: Boolean
        get() = prefs().getBoolean(KEY_PSIPHON_CONDUIT, false)
        set(value) {
            prefs().edit().putBoolean(KEY_PSIPHON_CONDUIT, value).apply()
        }

    /** Applying a scanned list drops what the core used before, instead of adding to it. */
    var replaceToolLists: Boolean
        get() = prefs().getBoolean(KEY_TOOLS_REPLACE, true)
        set(value) {
            prefs().edit().putBoolean(KEY_TOOLS_REPLACE, value).apply()
        }

    /** Relays hosted in Iran, Russia, China and North Korea never reach the list. */
    var vpngateFilterCountries: Boolean
        get() = prefs().getBoolean(KEY_VPNGATE_FILTER, true)
        set(value) {
            prefs().edit().putBoolean(KEY_VPNGATE_FILTER, value).apply()
        }

    /** SOCKS5 port the app listens on, and the port the engines bind themselves to. */
    var socksPort: Int
        get() = prefs().getInt(KEY_SOCKS_PORT, DEFAULT_SOCKS_PORT)
        set(value) {
            prefs().edit().putInt(KEY_SOCKS_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply()
        }

    var httpPort: Int
        get() = prefs().getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT)
        set(value) {
            prefs().edit().putInt(KEY_HTTP_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply()
        }

    val socksAddress: String get() = "$LOOPBACK:$socksPort"
    val httpAddress: String get() = "$LOOPBACK:$httpPort"
}
