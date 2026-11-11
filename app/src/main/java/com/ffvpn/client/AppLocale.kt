package com.ffvpn.client

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.ffvpn.client.vpn.Store
import java.util.Locale

/**
 * In-app language choice. Android 13's per-app locales would do this natively,
 * but the minimum is 26, so the configuration is overridden on every entry
 * point (activities and the service) from one stored tag.
 */
object AppLocale {
    const val SYSTEM = ""

    /** Tags offered in Settings; the labels are the languages' own names. */
    val choices: List<Pair<String, String>> = listOf(
        SYSTEM to "System",
        "en" to "English",
        "fa" to "فارسی",
        "ru" to "Русский",
        "es" to "Español"
    )

    fun current(context: Context): String = Store.language(context)

    /** Wraps a base context so every resource lookup uses the chosen language. */
    fun wrap(base: Context): Context {
        val tag = Store.language(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Stores the choice and rebuilds the activity so the new strings apply. */
    fun apply(activity: Activity, tag: String) {
        if (Store.language(activity) == tag) return
        Store.setLanguage(activity, tag)
        activity.recreate()
    }
}
