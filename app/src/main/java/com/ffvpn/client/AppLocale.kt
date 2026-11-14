package com.ffvpn.client

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
        "es" to "Español",
        "zh" to "中文"
    )

    fun current(context: Context): String = Store.language(context)

    /** Wraps a base context so every resource lookup uses the chosen language. */
    fun wrap(base: Context): Context {
        val fallback = base.resources.configuration.locales[0]
        val tag = Store.language(base)
        val locale = if (tag.isEmpty()) {
            fallback
        } else {
            Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() } ?: fallback
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Stores the choice and rebuilds the screen.
     *
     * The app's own text switches as soon as the store changes, because the
     * strings read it directly, but left-to-right versus right-to-left comes from
     * the Activity's configuration, which only a rebuild picks up. So this takes
     * a Context rather than an Activity: a bottom sheet hands out its own window
     * wrapper, and the old cast silently missed it — the text changed, the
     * direction did not, and only a cold start put the two back in step.
     */
    fun apply(context: Context, tag: String) {
        if (Store.language(context) == tag) return
        Store.setLanguage(context, tag)
        activityOf(context)?.recreate()
    }

    /** The Activity behind a context, however many wrappers sit in between. */
    private fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * Rebuilds an activity that is still laid out in another language — the one
     * left over when the choice changed while it sat in the back stack.
     */
    fun sync(activity: Activity) {
        val tag = Store.language(activity)
        if (tag.isEmpty()) return
        val wanted = Locale.forLanguageTag(tag)
        if (wanted.language.isEmpty()) return
        val current = activity.resources.configuration.locales[0]
        if (current.language.equals(wanted.language, ignoreCase = true)) return
        activity.recreate()
    }
}
