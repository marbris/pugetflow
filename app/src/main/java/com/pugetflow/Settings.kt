package com.pugetflow

import android.content.Context

/**
 * Lightweight persisted user settings (SharedPreferences), plus the shared
 * value-formatting helpers so the app, the notification, and the OsmAnd points
 * all present units consistently.
 */
object Settings {

    enum class ColorMode { TEMPERATURE, FLOW }

    private const val PREFS = "pugetflow_prefs"
    private const val KEY_FAHRENHEIT = "use_fahrenheit"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_SITES = "active_sites"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var useFahrenheit: Boolean
        get() = prefs?.getBoolean(KEY_FAHRENHEIT, false) ?: false
        set(v) { prefs?.edit()?.putBoolean(KEY_FAHRENHEIT, v)?.apply() }

    var colorMode: ColorMode
        get() = if (prefs?.getString(KEY_COLOR_MODE, "TEMPERATURE") == "FLOW")
            ColorMode.FLOW else ColorMode.TEMPERATURE
        set(v) { prefs?.edit()?.putString(KEY_COLOR_MODE, v.name)?.apply() }

    /**
     * The USGS site IDs currently shown on the map. Defaults to the built-in
     * Seattle-area seed list until the user adds their own via "add nearby gauges".
     */
    fun activeSites(): MutableSet<String> {
        val stored = prefs?.getStringSet(KEY_SITES, null)
        // Copy — the Set returned by getStringSet must not be mutated in place.
        return stored?.toMutableSet() ?: Sites.SITE_IDS.toMutableSet()
    }

    fun addSites(ids: Collection<String>) {
        val set = activeSites()
        set.addAll(ids)
        prefs?.edit()?.putStringSet(KEY_SITES, set)?.apply()
    }

    /** Replace the whole active set (used by "show only this river's gauges"). */
    fun setActiveSites(ids: Collection<String>) {
        prefs?.edit()?.putStringSet(KEY_SITES, LinkedHashSet(ids))?.apply()
    }

    fun removeSite(id: String) {
        val set = activeSites()
        if (set.remove(id)) prefs?.edit()?.putStringSet(KEY_SITES, set)?.apply()
    }

    /** Forget user additions; activeSites() falls back to the built-in seed list. */
    fun resetSites() {
        prefs?.edit()?.remove(KEY_SITES)?.apply()
    }

    /** "14.2 °C" or "57.6 °F" depending on the setting. */
    fun formatTemp(celsius: Double): String {
        return if (useFahrenheit) {
            "${round1(celsius * 9.0 / 5.0 + 32.0)} °F"
        } else {
            "${round1(celsius)} °C"
        }
    }

    private fun round1(v: Double): String = String.format("%.1f", v)
}
