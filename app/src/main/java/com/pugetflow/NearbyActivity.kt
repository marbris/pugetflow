package com.pugetflow

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.PI

/**
 * Entry point for "long-press a spot in OsmAnd → Share → PugetFlow".
 * Parses the shared coordinate, queries USGS for every active gauge within a
 * box around it, and lets the user add a selection to the live map layer.
 */
class NearbyActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var list: ListView
    private lateinit var subtitle: TextView

    // Parallel to the ListView rows.
    private val rowSiteIds = ArrayList<String>()
    private val rowLabels = ArrayList<String>()

    private val radiusKm = 20.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_nearby)

        list = findViewById(R.id.list)
        subtitle = findViewById(R.id.txtSubtitle)

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { selectAll() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addSelected() }

        val coord = parseCoordinate(intent)
        if (coord == null) {
            Toast.makeText(this, "Couldn't find coordinates in the shared location.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        subtitle.text = "Searching within ${radiusKm.toInt()} km of ${"%.4f".format(coord.first)}, ${"%.4f".format(coord.second)} …"
        loadNearby(coord.first, coord.second)
    }

    private fun loadNearby(lat: Double, lon: Double) {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(lat * PI / 180.0))
        io.execute {
            try {
                val readings = UsgsClient.fetchByBBox(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
                val already = Settings.activeSites()
                // Nearest first.
                val sorted = readings.sortedBy { haversineKm(lat, lon, it.lat, it.lon) }
                runOnUiThread { showResults(sorted, already) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Lookup failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun showResults(readings: List<RiverReading>, already: Set<String>) {
        rowSiteIds.clear()
        rowLabels.clear()
        for (r in readings) {
            rowSiteIds.add(r.siteId)
            val flow = r.flowCfs?.let { "${fmt(it)} cfs" } ?: "—"
            val temp = r.tempC?.let { " · ${Settings.formatTemp(it)}" } ?: ""
            val shown = if (already.contains(r.siteId)) "  ✓ already added" else ""
            rowLabels.add("${r.name}\n$flow$temp$shown")
        }

        if (rowLabels.isEmpty()) {
            subtitle.text = "No active USGS gauges found within ${radiusKm.toInt()} km."
            return
        }
        subtitle.text = "${rowLabels.size} gauge(s) nearby — tap to select, then Add."

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, rowLabels)
        list.adapter = adapter
        // Pre-check the ones not yet added, so a single "Add" grabs the new ones.
        for (i in rowSiteIds.indices) {
            list.setItemChecked(i, !already.contains(rowSiteIds[i]))
        }
    }

    private fun selectAll() {
        for (i in rowSiteIds.indices) list.setItemChecked(i, true)
    }

    private fun addSelected() {
        val checked = list.checkedItemPositions
        val ids = ArrayList<String>()
        for (i in rowSiteIds.indices) {
            if (checked.get(i, false)) ids.add(rowSiteIds[i])
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, "Nothing selected.", Toast.LENGTH_SHORT).show()
            return
        }
        Settings.addSites(ids)
        // Ensure live updates are running, then force an immediate refresh so the
        // new points appear now (START alone won't re-fetch if already running).
        sendToService(RiverService.ACTION_START)
        sendToService(RiverService.ACTION_REFRESH)
        Toast.makeText(this, "Added ${ids.size} gauge(s). Opening OsmAnd…", Toast.LENGTH_SHORT).show()
        openOsmAnd()
        finish()
    }

    private fun sendToService(action: String) {
        val svc = Intent(this, RiverService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    private fun openOsmAnd() {
        val pkg = OsmAndBridge(this).osmandPackage() ?: return
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    // --- coordinate parsing ---

    /** Handles geo: VIEW intents and OsmAnd's shared text (geo: URI + osmand.net link). */
    private fun parseCoordinate(intent: Intent?): Pair<Double, Double>? {
        if (intent == null) return null

        // 1) geo: VIEW intent, e.g. geo:47.60,-122.33?z=15
        (intent.data)?.let { uri ->
            fromGeoUri(uri)?.let { return it }
        }

        // 2) Shared text (ACTION_SEND).
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null

        // 2a) geo: inside the text
        Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        // 2b) osmand.net/go?lat=..&lon=..
        val lat = Regex("""[?&]lat=(-?\d+\.\d+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val lon = Regex("""[?&]lon=(-?\d+\.\d+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon
        // 2c) bare "lat, lon" pair
        Regex("""(-?\d{1,3}\.\d+)[,\s]+(-?\d{1,3}\.\d+)""").find(text)?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        return null
    }

    private fun fromGeoUri(uri: Uri): Pair<Double, Double>? {
        if (uri.scheme != "geo") return null
        val ssp = uri.schemeSpecificPart ?: return null           // "47.60,-122.33?z=15"
        val coords = ssp.substringBefore("?").split(",")
        if (coords.size < 2) return null
        val lat = coords[0].trim().toDoubleOrNull() ?: return null
        val lon = coords[1].trim().toDoubleOrNull() ?: return null
        // geo:0,0?q=lat,lon form:
        if (lat == 0.0 && lon == 0.0) {
            uri.query?.let { q ->
                Regex("""(-?\d+\.\d+),(-?\d+\.\d+)""").find(q)?.let { m ->
                    return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
                }
            }
        }
        return lat to lon
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1 * PI / 180.0) * Math.cos(lat2 * PI / 180.0) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun fmt(v: Double): String =
        if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else String.format("%.1f", v)

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
