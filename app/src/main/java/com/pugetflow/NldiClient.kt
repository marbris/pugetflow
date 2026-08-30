package com.pugetflow

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * USGS Hydro Network-Linked Data Index (NLDI) client.
 * Docs: https://api.water.usgs.gov/docs/nldi/
 *
 * We navigate the Upstream-Mainstem (UM) from a near-mouth "seed" gauge to get
 * (a) the list of gauges along the river and (b) the flowline geometry, which we
 * chain into one polyline so each gauge can be placed at an along-river distance.
 */
object NldiClient {

    private const val BASE = "https://api.water.usgs.gov/nldi/linked-data/nwissite/USGS-"

    /** An ordered river polyline (lon,lat vertices) with cumulative km at each vertex. */
    class RiverPath(val verts: List<DoubleArray>, val cum: DoubleArray) {
        val lengthKm: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

        /** Along-river distance (km) of the vertex nearest the given point. */
        fun riverKmFor(lat: Double, lon: Double): Double {
            if (verts.isEmpty()) return 0.0
            var bestI = 0
            var bestD = Double.MAX_VALUE
            for (i in verts.indices) {
                val d = havKm(lon, lat, verts[i][0], verts[i][1])
                if (d < bestD) { bestD = d; bestI = i }
            }
            return cum[bestI]
        }
    }

    /** USGS site IDs along the upstream mainstem (includes the seed itself). */
    fun mainstemSites(seedSiteId: String, distanceKm: Int = 400): List<String> {
        val url = "$BASE$seedSiteId/navigation/UM/nwissite?distance=$distanceKm"
        val root = JSONObject(get(url))
        val feats = root.optJSONArray("features") ?: return listOf(seedSiteId)
        val ids = LinkedHashSet<String>()
        ids.add(seedSiteId)
        for (i in 0 until feats.length()) {
            val id = feats.getJSONObject(i).optJSONObject("properties")
                ?.optString("identifier")?.removePrefix("USGS-") ?: continue
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids.toList()
    }

    /** Chain the upstream-mainstem flowlines into one polyline anchored at the seed. */
    fun mainstemPath(seedSiteId: String, seedLat: Double, seedLon: Double, distanceKm: Int = 400): RiverPath {
        val url = "$BASE$seedSiteId/navigation/UM/flowlines?distance=$distanceKm"
        val root = JSONObject(get(url))
        val feats = root.optJSONArray("features") ?: return RiverPath(emptyList(), DoubleArray(0))

        // Collect LineString segments.
        val segs = ArrayList<List<DoubleArray>>()
        for (i in 0 until feats.length()) {
            val g = feats.getJSONObject(i).optJSONObject("geometry") ?: continue
            if (g.optString("type") != "LineString") continue
            val coords = g.optJSONArray("coordinates") ?: continue
            val seg = ArrayList<DoubleArray>(coords.length())
            for (k in 0 until coords.length()) {
                val c = coords.getJSONArray(k)
                seg.add(doubleArrayOf(c.getDouble(0), c.getDouble(1))) // lon,lat
            }
            if (seg.size >= 2) segs.add(seg)
        }
        if (segs.isEmpty()) return RiverPath(emptyList(), DoubleArray(0))

        // Endpoint index (rounded key) → segment indices.
        val ends = HashMap<String, MutableList<Int>>()
        fun key(p: DoubleArray) = "%.5f,%.5f".format(p[0], p[1])
        for (i in segs.indices) {
            ends.getOrPut(key(segs[i].first())) { ArrayList() }.add(i)
            ends.getOrPut(key(segs[i].last())) { ArrayList() }.add(i)
        }

        // Start = the segment endpoint overall closest to the seed.
        var startSeg = 0
        var startPt = segs[0].first()
        var startD = Double.MAX_VALUE
        for (i in segs.indices) {
            for (endpt in listOf(segs[i].first(), segs[i].last())) {
                val d = havKm(seedLon, seedLat, endpt[0], endpt[1])
                if (d < startD) { startD = d; startSeg = i; startPt = endpt }
            }
        }

        // Walk the chain.
        val used = HashSet<Int>()
        val path = ArrayList<DoubleArray>()
        var curKey = key(startPt)
        var curSeg: Int? = startSeg
        var steps = 0
        while (curSeg != null && curSeg !in used && steps < segs.size + 5) {
            steps++
            used.add(curSeg)
            var seg = segs[curSeg]
            if (key(seg.first()) != curKey) seg = seg.reversed()
            val toAdd = if (path.isNotEmpty() && key(path.last()) == key(seg.first())) seg.drop(1) else seg
            path.addAll(toAdd)
            val endKey = key(path.last())
            curKey = endKey
            curSeg = ends[endKey]?.firstOrNull { it !in used }
        }

        // Cumulative distance.
        val cum = DoubleArray(path.size)
        for (k in 1 until path.size) {
            cum[k] = cum[k - 1] + havKm(path[k - 1][0], path[k - 1][1], path[k][0], path[k][1])
        }
        return RiverPath(path, cum)
    }

    // --- helpers ---

    private fun get(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "PugetFlow/1.0 (personal OsmAnd overlay)")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw RuntimeException("NLDI HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun havKm(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * asin(min(1.0, sqrt(a)))
    }
}
