package com.ffvpn.client.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One Firefox Account kept on the device: its token and last known quota. */
data class SavedAccount(
    val email: String,
    val token: String,
    val quota: String
)

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
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE = "active_account"

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
        val active = prefs(context).getString(KEY_ACTIVE, null) ?: return
        val list = accounts(context).map {
            if (it.email.equals(active, ignoreCase = true)) it.copy(quota = value) else it
        }
        writeAccounts(context, list)
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

    /** Every account added so far, in the order they were added. */
    fun accounts(context: Context): List<SavedAccount> {
        val raw = prefs(context).getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val email = item.optString("email").trim()
                    val token = item.optString("token").trim()
                    // A half-written entry would only fail later, at connect time.
                    if (email.isEmpty() || token.length < 64) continue
                    add(SavedAccount(email, token, item.optString("quota")))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** The account whose token the engine reads from session.txt right now. */
    fun activeAccount(context: Context): SavedAccount? {
        val list = accounts(context)
        val active = prefs(context).getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.email.equals(active, ignoreCase = true) } ?: list.firstOrNull()
    }

    /** Adds [email] or refreshes its token, then makes it the active account. */
    fun saveAccount(context: Context, email: String, token: String, quota: String = "") {
        val address = email.trim()
        if (address.isEmpty() || token.trim().length < 64) return
        val list = accounts(context).filterNot { it.email.equals(address, ignoreCase = true) } +
            SavedAccount(address, token.trim(), quota)
        writeAccounts(context, list)
        activateAccount(context, address, list)
    }

    fun activateAccount(context: Context, email: String) {
        activateAccount(context, email, accounts(context))
    }

    private fun activateAccount(context: Context, email: String, list: List<SavedAccount>) {
        val account = list.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return
        runCatching { sessionFile(context).writeText(account.token) }
        prefs(context).edit()
            .putString(KEY_ACTIVE, account.email)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_QUOTA, account.quota)
            .apply()
    }

    /** Drops one account; another takes its place, or the session goes away. */
    fun removeAccount(context: Context, email: String) {
        val rest = accounts(context).filterNot { it.email.equals(email, ignoreCase = true) }
        writeAccounts(context, rest)
        val active = prefs(context).getString(KEY_ACTIVE, null)
        if (active == null || !active.equals(email, ignoreCase = true)) return
        if (rest.isEmpty()) {
            clearSession(context)
            prefs(context).edit().remove(KEY_ACTIVE).remove(KEY_EMAIL).remove(KEY_QUOTA).apply()
        } else {
            activateAccount(context, rest.first().email, rest)
        }
    }

    /** Adopts a session cached by an older build as the first saved account. */
    fun ensureAccounts(context: Context) {
        if (accounts(context).isNotEmpty()) return
        val address = email(context).orEmpty()
        val token = runCatching { sessionFile(context).readText().trim() }.getOrDefault("")
        if (address.isNotBlank() && token.length >= 64) {
            val first = SavedAccount(address, token, quota(context).orEmpty())
            writeAccounts(context, listOf(first))
            prefs(context).edit().putString(KEY_ACTIVE, first.email).apply()
        }
    }

    private fun writeAccounts(context: Context, list: List<SavedAccount>) {
        val array = JSONArray()
        list.forEach { account ->
            array.put(
                JSONObject()
                    .put("email", account.email)
                    .put("token", account.token)
                    .put("quota", account.quota)
            )
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }
}
