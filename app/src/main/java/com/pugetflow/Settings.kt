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
