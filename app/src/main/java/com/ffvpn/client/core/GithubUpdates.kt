package com.ffvpn.client.core

import android.os.Build
import com.ffvpn.client.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object GithubUpdates {
    const val RELEASES_PAGE = "https://github.com/FossilizedProgrammer/niloofar_vpn/releases"
    private const val API = "https://api.github.com/repos/FossilizedProgrammer/niloofar_vpn/releases"
    private val apkName = Regex("""NiloofarVPN-(\d+\.\d+\.\d+)-(armeabi-v7a|arm64-v8a|universal)\.apk""")

    private val autoChecked = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Release(val version: String, val tag: String) {
        val page: String get() = "$RELEASES_PAGE/tag/$tag"
    }

    /**
     * One silent check per process. On a filtered line it simply fails; the
     * button in Settings is still there to retry with the tunnel on.
     */
    fun autoCheck() {
        if (!autoChecked.compareAndSet(false, true)) return
        scope.launch {
            runCatching { check() }.getOrNull()?.let { publish(it) }
        }
    }

    /** Records what a check found, so the home screen badge agrees with Settings. */
    fun publish(release: Release) {
        val newer = compareVersions(release.version, BuildConfig.VERSION_NAME) > 0
        AppState.update { it.copy(update = if (newer) release else null) }
    }

    suspend fun check(): Release = withContext(Dispatchers.IO) {
        // Only proxy mode needs the local front: a VPN already carries this request,
        // and there the front is opened just for LAN sharing, so it is usually absent.
        val proxy = if (AppState.state.connected && Prefs.mode == AppState.Mode.PROXY) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(Prefs.LOOPBACK, Prefs.httpPort))
        } else {
            Proxy.NO_PROXY
        }
        var newest: Release? = null
        for (page in 1..10) {
            ensureActive()
            val connection = URL("$API?per_page=100&page=$page").openConnection(proxy) as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "NiloofarVPN")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub HTTP ${connection.responseCode}")
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                ensureActive()
                candidates(json, Build.SUPPORTED_ABIS.toList()).forEach { release ->
                    val previous = newest
                    if (previous == null || compareVersions(release.version, previous.version) > 0 ||
                        (compareVersions(release.version, previous.version) == 0 && previous.tag == "latest")
                    ) {
                        newest = release
                    }
                }
                if (connection.getHeaderField("Link")?.contains("rel=\"next\"") != true) {
                    return@withContext newest ?: throw IOException("No compatible versioned APK release")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Incomplete GitHub release list")
    }

    internal fun candidates(json: String, supportedAbis: List<String>): List<Release> {
        val releases = JSONArray(json)
        val result = mutableListOf<Release>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            val tag = release.getString("tag_name")
            if (release.getBoolean("draft")) continue
            if (tag != "latest" && (release.getBoolean("prerelease") ||
                    !Regex("""v?\d+\.\d+\.\d+""").matches(tag))) continue
            val assets = release.getJSONArray("assets")
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.getString("state") != "uploaded" || asset.getLong("size") <= 0) continue
                val match = apkName.matchEntire(asset.getString("name")) ?: continue
                val (version, abi) = match.destructured
                val compatible = abi in supportedAbis || (abi == "universal" &&
                    supportedAbis.any { it == "arm64-v8a" || it == "armeabi-v7a" })
                if (!compatible || (tag != "latest" && tag.removePrefix("v") != version)) continue
                compareVersions(version, version)
                result += Release(version, tag)
            }
        }
        return result
    }

    internal fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Long> {
            require(Regex("""\d+\.\d+\.\d+""").matches(value))
            return value.split('.').map { it.toLong() }
        }
        return parts(left).zip(parts(right)).firstOrNull { it.first != it.second }
            ?.let { it.first.compareTo(it.second) } ?: 0
    }
}
