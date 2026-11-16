package com.ffvpn.client.core

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class SplitTunnelPrefsTest {
    private val values = mutableMapOf<String, Set<String>>()
    private val storeField = Prefs::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previousStore: Any? = null

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() {
        previousStore = storeField.get(Prefs)
        val pending = mutableMapOf<String, Set<String>>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putStringSet" -> {
                    pending[args[0] as String] = args[1] as Set<String>
                    proxy
                }
                "apply" -> {
                    values.putAll(pending)
                    pending.clear()
                    null
                }
                else -> error("Unexpected editor call: ${method.name}")
            }
        }
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getStringSet" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        }
        storeField.set(Prefs, preferences)
    }

    @After
    fun tearDown() {
        storeField.set(Prefs, previousStore)
    }

    @Test
    fun noAppsAreExcludedByDefault() {
        assertTrue(Prefs.vpnExcludedApps.isEmpty())
    }

    @Test
    fun savingReplacesThePreviousSelection() {
        Prefs.vpnExcludedApps = setOf("example.first", "example.second")
        assertEquals(setOf("example.first", "example.second"), Prefs.vpnExcludedApps)
        Prefs.vpnExcludedApps = setOf("example.third")
        assertEquals(setOf("example.third"), Prefs.vpnExcludedApps)
    }

    @Test
    fun clearingSelectionPersistsAnEmptySet() {
        Prefs.vpnExcludedApps = setOf("example.first")
        Prefs.vpnExcludedApps = emptySet()
        assertTrue(Prefs.vpnExcludedApps.isEmpty())
        assertEquals(emptySet<String>(), values["vpn_excluded_apps"])
    }

    @Test
    fun savedSelectionDoesNotRetainTheCallersMutableSet() {
        val selection = mutableSetOf("example.first", "example.second")
        Prefs.vpnExcludedApps = selection
        selection.clear()
        assertEquals(setOf("example.first", "example.second"), Prefs.vpnExcludedApps)
    }

    @Test
    fun connectionSnapshotDoesNotShareTheStoredMutableSet() {
        val stored = mutableSetOf("example.first", "example.second")
        values["vpn_excluded_apps"] = stored
        val snapshot = Prefs.vpnExcludedApps
        stored.clear()
        assertEquals(setOf("example.first", "example.second"), snapshot)
    }
}
