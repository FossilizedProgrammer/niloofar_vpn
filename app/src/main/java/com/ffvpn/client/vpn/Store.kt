package com.ffvpn.client.vpn

import android.content.Context
import java.io.File

/** Where the engine keeps its state: session token, server cache and tunnel config. */
object Store {
    private const val PREFS = "ffvpn_store"
    private const val KEY_EMAIL = "email"
    private const val KEY_QUOTA = "quota"
    private const val KEY_LAST = "last_server"
    private const val KEY_MODE = "mode"
    private const val KEY_SHARE = "share"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_COUNTRY = "country"

    fun workDir(context: Context): File = File(context.filesDir, "ffvpn").apply { mkdirs() }

    fun sessionFile(context: Context): File = File(workDir(context), "session.txt")

    fun cacheFile(context: Context): File = File(workDir(context), "servers.json")

    fun configFile(context: Context): File = File(workDir(context), "tunnel.yml")

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libffvpn.so")

    /** The token the engine cached after a sign-in; 64 hex characters or nothing. */
    fun hasSession(context: Context): Boolean =
        runCatching { sessionFile(context).readText().trim().length >= 64 }.getOrDefault(false)

    fun clearSession(context: Context) {
        runCatching { sessionFile(context).delete() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun email(context: Context): String? = prefs(context).getString(KEY_EMAIL, null)

    fun setEmail(context: Context, value: String) {
        prefs(context).edit().putString(KEY_EMAIL, value).apply()
    }

    fun quota(context: Context): String? = prefs(context).getString(KEY_QUOTA, null)

    fun setQuota(context: Context, value: String) {
        prefs(context).edit().putString(KEY_QUOTA, value).apply()
    }

    fun mode(context: Context): Mode = when (prefs(context).getString(KEY_MODE, null)) {
        "proxy" -> Mode.Proxy
        else -> Mode.Vpn
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name.lowercase()).apply()
    }

    /** BCP-47 tag of the chosen UI language; empty means follow the system. */
    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, "").orEmpty()

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }

    fun share(context: Context): Boolean = prefs(context).getBoolean(KEY_SHARE, false)

    fun setShare(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHARE, value).apply()
    }

    /** Country code of the chosen exit; empty means the app picks the fastest. */
    fun country(context: Context): String = prefs(context).getString(KEY_COUNTRY, "").orEmpty()

    fun setCountry(context: Context, value: String) {
        prefs(context).edit().putString(KEY_COUNTRY, value).apply()
    }

    fun lastServer(context: Context): String? = prefs(context).getString(KEY_LAST, null)

    fun setLastServer(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST, value).apply()
    }
}
