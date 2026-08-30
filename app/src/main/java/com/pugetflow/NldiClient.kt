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
 * From a "seed" gauge anywhere on a river we navigate BOTH directions —
 * Upstream Mainstem (UM) and Downstream Mainstem (DM) — to capture the whole
 * river: every mainstem gauge, plus the flowline geometry, which we chain into a
 * single polyline (mouth → headwaters) so each gauge gets an along-river distance.
 */
object NldiClient {

    private const val SITE_BASE = "https://api.water.usgs.gov/nldi/linked-data/nwissite/USGS-"
    private const val COMID_BASE = "https://api.water.usgs.gov/nldi/linked-data/comid/"

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

    /** NHD flowline COMID at a coordinate (for "what river is under this point?"). */
    fun comidAt(lat: Double, lon: Double): String? {
        val url = COMID_BASE + "position?coords=POINT(${lon}%20${lat})"
        return try {
            val feats = JSONObject(get(url)).optJSONArray("features") ?: return null
            if (feats.length() == 0) return null
            feats.getJSONObject(0).optJSONObject("properties")
                ?.optString("comid")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Every mainstem gauge on the river (both directions from the seed, incl. seed). */
    fun riverSites(seedSiteId: String, distanceKm: Int = 400): List<String> {
        val ids = LinkedHashSet<String>()
        ids.add(seedSiteId)
        for (dir in listOf("UM", "DM")) {
            try {
                val feats = JSONObject(get("$SITE_BASE$seedSiteId/navigation/$dir/nwissite?distance=$distanceKm"))
                    .optJSONArray("features") ?: continue
                for (i in 0 until feats.length()) {
                    val id = feats.getJSONObject(i).optJSONObject("properties")
                        ?.optString("identifier")?.removePrefix("USGS-") ?: continue
                    if (id.isNotEmpty()) ids.add(id)
                }
            } catch (_: Exception) { /* one direction may be empty near the mouth/headwaters */ }
        }
        return ids.toList()
    }

    /** UM+DM flowlines chained into one polyline, oriented mouth (0 km) → headwaters. */
    fun riverPath(seedSiteId: String, distanceKm: Int = 400): RiverPath {
        val segs = ArrayList<List<DoubleArray>>()
        val isDm = ArrayList<Boolean>()
        for (dir in listOf("UM", "DM")) {
            val dm = dir == "DM"
            try {
                val feats = JSONObject(get("$SITE_BASE$seedSiteId/navigation/$dir/flowlines?distance=$distanceKm"))
                    .optJSONArray("features") ?: continue
                for (i in 0 until feats.length()) {
                    val g = feats.getJSONObject(i).optJSONObject("geometry") ?: continue
                    if (g.optString("type") != "LineString") continue
                    val coords = g.optJSONArray("coordinates") ?: continue
                    val seg = ArrayList<DoubleArray>(coords.length())
                    for (k in 0 until coords.length()) {
                        val c = coords.getJSONArray(k)
                        seg.add(doubleArrayOf(c.getDouble(0), c.getDouble(1))) // lon,lat
                    }
                    if (seg.size >= 2) { segs.add(seg); isDm.add(dm) }
                }
            } catch (_: Exception) { }
        }
        if (segs.isEmpty()) return RiverPath(emptyList(), DoubleArray(0))

        fun key(p: DoubleArray) = "%.5f,%.5f".format(p[0], p[1])
        val ends = HashMap<String, MutableList<Int>>()
        val endCount = HashMap<String, Int>()
        for (i in segs.indices) {
            for (endpt in listOf(segs[i].first(), segs[i].last())) {
                val k = key(endpt)
                ends.getOrPut(k) { ArrayList() }.add(i)
                endCount[k] = (endCount[k] ?: 0) + 1
            }
        }

        // Two dangling ends on a simple path; the mouth is the one on a DM segment.
        val dangling = endCount.filter { it.value == 1 }.keys
        val hasDm = isDm.any { it }
        var startKey = dangling.firstOrNull { k -> hasDm && ends[k]!!.any { isDm[it] } }
            ?: dangling.firstOrNull()
            ?: key(segs[0].first())

        // Walk the chain from the start endpoint.
        val used = HashSet<Int>()
        val path = ArrayList<DoubleArray>()
        var curKey = startKey
        var curSeg: Int? = ends[startKey]?.firstOrNull()
        var steps = 0
        while (curSeg != null && curSeg !in used && steps < segs.size + 5) {
            steps++
            used.add(curSeg)
            var seg = segs[curSeg]
            if (key(seg.first()) != curKey) seg = seg.reversed()
            val toAdd = if (path.isNotEmpty() && key(path.last()) == key(seg.first())) seg.drop(1) else seg
            path.addAll(toAdd)
            curKey = key(path.last())
            curSeg = ends[curKey]?.firstOrNull { it !in used }
        }

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
