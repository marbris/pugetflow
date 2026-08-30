package com.pugetflow

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * On Android 15+ (targetSdk 35) apps are edge-to-edge by default: content draws
 * behind the status bar and navigation bar. This pads a root view by the
 * system-bar insets (added on top of whatever padding it already has) so headers
 * and bottom buttons aren't obscured.
 */
fun View.applySystemBarInsets() {
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(baseL + bars.left, baseT + bars.top, baseR + bars.right, baseB + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
