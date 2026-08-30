package com.pugetflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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

    private val reqLocation = 200
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).applySystemBarInsets()

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
        findViewById<Button>(R.id.btnRivers).setOnClickListener {
            startActivity(Intent(this, RiversActivity::class.java))
        }
        findViewById<Button>(R.id.btnNearMe).setOnClickListener { riversNearMe() }

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

    private fun riversNearMe() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), reqLocation)
            return
        }
        openNearMe()
    }

    private fun openNearMe() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) { null }

        if (loc == null) {
            Toast.makeText(this, "No recent location fix. Open a maps app briefly, then retry.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, NearbyActivity::class.java).apply {
            putExtra(NearbyActivity.EXTRA_LAT, loc.latitude)
            putExtra(NearbyActivity.EXTRA_LON, loc.longitude)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqLocation &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            openNearMe()
        }
    }
}
