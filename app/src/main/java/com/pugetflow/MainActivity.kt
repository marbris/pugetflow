package com.pugetflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.txtStatus)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestNotifPermissionIfNeeded()
            send(RiverService.ACTION_START)
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            send(RiverService.ACTION_REFRESH)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            send(RiverService.ACTION_STOP)
        }
        findViewById<Button>(R.id.btnOpenOsmAnd).setOnClickListener { openOsmAnd() }

        setupSettingsControls()
        checkOsmAnd()
    }

    private fun setupSettingsControls() {
        val swF = findViewById<CompoundButton>(R.id.swFahrenheit)
        swF.isChecked = Settings.useFahrenheit
        swF.setOnCheckedChangeListener { _, checked ->
            Settings.useFahrenheit = checked
            reRenderPoints()
        }

        val rg = findViewById<RadioGroup>(R.id.rgColorMode)
        val initial = if (Settings.colorMode == Settings.ColorMode.FLOW)
            R.id.rbColorFlow else R.id.rbColorTemp
        rg.check(initial)
        rg.setOnCheckedChangeListener { _, checkedId ->
            Settings.colorMode = if (checkedId == R.id.rbColorFlow)
                Settings.ColorMode.FLOW else Settings.ColorMode.TEMPERATURE
            reRenderPoints()
        }

        findViewById<Button>(R.id.btnResetGauges).setOnClickListener {
            Settings.resetSites()
            send(RiverService.ACTION_RESET)
            Toast.makeText(this, "Gauge list reset to defaults.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-push points so OsmAnd reflects the new unit/colour immediately. */
    private fun reRenderPoints() {
        send(RiverService.ACTION_REFRESH)
    }

    override fun onResume() {
        super.onResume()
        RiverService.statusListener = { s -> runOnUiThread { status.text = s } }
        status.text = RiverService.lastStatus
    }

    override fun onPause() {
        super.onPause()
        RiverService.statusListener = null
    }

    private fun send(action: String) {
        val i = Intent(this, RiverService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun checkOsmAnd() {
        val pkg = OsmAndBridge(this).osmandPackage()
        status.text = if (pkg == null) {
            "⚠ OsmAnd not found. Install OsmAnd, then press Start."
        } else {
            "OsmAnd detected: $pkg\nPress Start to begin live updates."
        }
    }

    private fun openOsmAnd() {
        val pkg = OsmAndBridge(this).osmandPackage()
        if (pkg == null) {
            Toast.makeText(this, "OsmAnd not installed", Toast.LENGTH_SHORT).show()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) startActivity(launch)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
