package com.pugetflow

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Loads a river profile: NLDI finds the mainstem gauges + flowline, USGS supplies
 * current flow/temp, and each gauge is placed at an along-river distance. Draws
 * two graphs (temperature and flow vs distance) and adds the gauges to OsmAnd.
 */
class RiverDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_SEED = "seed"
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var addedIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_river_detail)

        val name = intent.getStringExtra(EXTRA_NAME) ?: "River"
        val seed = intent.getStringExtra(EXTRA_SEED) ?: run { finish(); return }

        findViewById<TextView>(R.id.txtTitle).text = name
        status = findViewById(R.id.txtStatus)
        findViewById<Button>(R.id.btnOpenOsmAnd).setOnClickListener { openOsmAnd() }

        load(name, seed)
    }

    private fun load(name: String, seed: String) {
        io.execute {
            try {
                val sites = NldiClient.mainstemSites(seed)
                val readings = UsgsClient.fetch(sites)
                if (readings.isEmpty()) {
                    runOnUiThread { status.text = "No active USGS gauges found for this river." }
                    return@execute
                }
                // Anchor the flowline at the seed (fall back to any reading).
                val anchor = readings.firstOrNull { it.siteId == seed } ?: readings.first()
                val path = NldiClient.mainstemPath(seed, anchor.lat, anchor.lon)

                // Place each gauge along the river (fallback: straight-line from anchor).
                data class P(val km: Double, val r: RiverReading)
                val placed = readings.map { r ->
                    val km = if (path.verts.isNotEmpty()) path.riverKmFor(r.lat, r.lon)
                    else straightKm(anchor.lat, anchor.lon, r.lat, r.lon)
                    P(km, r)
                }.sortedBy { it.km }

                val useF = Settings.useFahrenheit
                val tempPts = placed.map { it.km to it.r.tempC?.let { c -> if (useF) c * 9 / 5 + 32 else c } }
                val flowPts = placed.map { it.km to it.r.flowCfs }

                addedIds = placed.map { it.r.siteId }
                // Make the river's gauges visible/findable in OsmAnd.
                Settings.addSites(addedIds)
                sendToService(RiverService.ACTION_START)
                sendToService(RiverService.ACTION_REFRESH)

                val listing = buildString {
                    for (p in placed) {
                        val f = p.r.flowCfs?.let { "${fmtVal(it)} cfs" } ?: "—"
                        val t = p.r.tempC?.let { " · ${Settings.formatTemp(it)}" } ?: ""
                        append("%.1f km  %s  %s%s\n".format(p.km, p.r.name, f, t))
                    }
                }

                runOnUiThread {
                    status.text = "${placed.size} gauges · river length ${"%.0f".format(path.lengthKm)} km · added to OsmAnd"
                    findViewById<GraphView>(R.id.graphTemp).setData(
                        tempPts, "Water temperature (${if (useF) "°F" else "°C"})",
                        if (useF) "°F" else "°C", Color.rgb(230, 74, 25)
                    )
                    findViewById<GraphView>(R.id.graphFlow).setData(
                        flowPts, "Streamflow (ft³/s)", "cfs", Color.rgb(2, 136, 209)
                    )
                    findViewById<TextView>(R.id.txtGauges).text = listing.trimEnd()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Failed to load river: ${e.message}" }
            }
        }
    }

    private fun sendToService(action: String) {
        val svc = Intent(this, RiverService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    private fun openOsmAnd() {
        val pkg = OsmAndBridge(this).osmandPackage()
        if (pkg == null) {
            Toast.makeText(this, "OsmAnd not installed", Toast.LENGTH_SHORT).show()
            return
        }
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun straightKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    private fun fmtVal(v: Double): String =
        if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else String.format("%.1f", v)

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
