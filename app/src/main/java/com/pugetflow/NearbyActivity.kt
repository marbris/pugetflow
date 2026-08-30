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
import kotlin.math.PI
import kotlin.math.cos

/**
 * Discovery hub: reached by "long-press in OsmAnd → Share → PugetFlow", or from
 * "Rivers near me" (current GPS). Finds USGS gauges in a box around the point,
 * groups them into rivers, and lets you profile a river (NLDI up+downstream) or
 * add the raw gauges to the map.
 */
class NearbyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var list: ListView
    private lateinit var subtitle: TextView

    private val rowRiverNames = ArrayList<String>()
    private val rowSeedIds = ArrayList<String>()   // a gauge on that river to seed NLDI
    private var allSiteIds: List<String> = emptyList()

    private val radiusKm = 25.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_nearby)
        findViewById<android.view.View>(R.id.root).applySystemBarInsets()

        list = findViewById(R.id.list)
        subtitle = findViewById(R.id.txtSubtitle)
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addAllGauges() }

        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, RiverDetailActivity::class.java).apply {
                putExtra(RiverDetailActivity.EXTRA_NAME, rowRiverNames[pos])
                putExtra(RiverDetailActivity.EXTRA_SEED, rowSeedIds[pos])
            })
        }

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
                val sorted = readings.sortedBy { haversineKm(lat, lon, it.lat, it.lon) }
                runOnUiThread { showRivers(sorted) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Lookup failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun showRivers(readings: List<RiverReading>) {
        allSiteIds = readings.map { it.siteId }
        // Group by river/creek name; keep the nearest gauge as the group's seed.
        val order = LinkedHashMap<String, String>()   // display name -> seed site id (first = nearest)
        val counts = LinkedHashMap<String, Int>()
        for (r in readings) {
            val name = riverDisplayName(r.name)
            val key = name.lowercase()
            if (!order.containsKey(key)) order[key] = r.siteId
            counts[key] = (counts[key] ?: 0) + 1
        }

        rowRiverNames.clear(); rowSeedIds.clear()
        val labels = ArrayList<String>()
        for ((key, seed) in order) {
            val display = riverDisplayName(readings.first { it.siteId == seed }.name)
            rowRiverNames.add(display)
            rowSeedIds.add(seed)
            labels.add("$display   (${counts[key]} gauge${if (counts[key] == 1) "" else "s"} nearby)")
        }

        if (labels.isEmpty()) {
            subtitle.text = "No active USGS gauges within ${radiusKm.toInt()} km."
            return
        }
        subtitle.text = "${labels.size} river(s) nearby — tap one to see its flow/temp profile."
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun addAllGauges() {
        if (allSiteIds.isEmpty()) return
        Settings.addSites(allSiteIds)
        sendToService(RiverService.ACTION_START)
        sendToService(RiverService.ACTION_REFRESH)
        Toast.makeText(this, "Added ${allSiteIds.size} gauge(s) to the map.", Toast.LENGTH_SHORT).show()
        openOsmAnd()
        finish()
    }

    /** "Cedar River at Renton" -> "Cedar River"; "Big Soos Creek above ..." -> "Big Soos Creek". */
    private fun riverDisplayName(siteName: String): String {
        val lower = siteName.lowercase()
        val markers = listOf(" at ", " near ", " nr ", " below ", " blw ", " above ", " abv ", " a ")
        var cut = siteName.length
        for (m in markers) {
            val idx = lower.indexOf(m)
            if (idx in 0 until cut) cut = idx
        }
        return siteName.substring(0, cut).trim().ifEmpty { siteName }
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

    private fun parseCoordinate(intent: Intent?): Pair<Double, Double>? {
        if (intent == null) return null
        // 0) explicit extras (from "Rivers near me")
        if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON)) {
            return intent.getDoubleExtra(EXTRA_LAT, 0.0) to intent.getDoubleExtra(EXTRA_LON, 0.0)
        }
        // 1) geo: VIEW intent
        intent.data?.let { uri -> fromGeoUri(uri)?.let { return it } }
        // 2) shared text (ACTION_SEND)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        val lat = Regex("""[?&]lat=(-?\d+\.\d+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val lon = Regex("""[?&]lon=(-?\d+\.\d+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon
        Regex("""(-?\d{1,3}\.\d+)[,\s]+(-?\d{1,3}\.\d+)""").find(text)?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        return null
    }

    private fun fromGeoUri(uri: Uri): Pair<Double, Double>? {
        if (uri.scheme != "geo") return null
        val ssp = uri.schemeSpecificPart ?: return null
        val coords = ssp.substringBefore("?").split(",")
        if (coords.size < 2) return null
        val lat = coords[0].trim().toDoubleOrNull() ?: return null
        val lon = coords[1].trim().toDoubleOrNull() ?: return null
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
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
