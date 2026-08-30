package com.pugetflow

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches near-real-time readings from the USGS Instantaneous Values (IV) web service.
 * Docs: https://waterservices.usgs.gov/docs/instantaneous-values/
 *
 * No API key is required. One request returns every requested parameter for every
 * requested site, so a whole refresh is a single HTTP call.
 */
object UsgsClient {

    private const val BASE = "https://waterservices.usgs.gov/nwis/iv/"
    private const val NO_DATA = -999999.0 // USGS sentinel for "no value"

    // Parameter codes we care about.
    private const val P_FLOW = "00060" // discharge, ft³/s
    private const val P_GAGE = "00065" // gage height, ft
    private const val P_TEMP = "00010" // water temperature, °C

    /** Returns one RiverReading per site that reported data, keyed/ordered arbitrarily. */
    fun fetch(siteIds: List<String>): List<RiverReading> {
        if (siteIds.isEmpty()) return emptyList()
        val url = BASE + "?format=json" +
                "&sites=" + siteIds.joinToString(",") +
                "&parameterCd=$P_FLOW,$P_GAGE,$P_TEMP" +
                "&siteStatus=active"
        val body = get(url)
        return parse(body)
    }

    /**
     * All active gauges within a lat/lon bounding box. Box order is USGS's:
     * west,south,east,north (minLon, minLat, maxLon, maxLat).
     */
    fun fetchByBBox(west: Double, south: Double, east: Double, north: Double): List<RiverReading> {
        val box = "%.5f,%.5f,%.5f,%.5f".format(west, south, east, north)
        val url = BASE + "?format=json" +
                "&bBox=$box" +
                "&parameterCd=$P_FLOW,$P_GAGE,$P_TEMP" +
                "&siteStatus=active"
        return parse(get(url))
    }

    private fun get(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        // Be a polite API citizen with an identifying User-Agent.
        conn.setRequestProperty("User-Agent", "PugetFlow/1.0 (personal OsmAnd overlay)")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw RuntimeException("USGS HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<RiverReading> {
        // Accumulate readings per site across the (site × parameter) time series.
        data class Acc(
            var name: String = "",
            var lat: Double = 0.0,
            var lon: Double = 0.0,
            var flow: Double? = null,
            var gage: Double? = null,
            var temp: Double? = null,
            var updated: String? = null
        )

        val root = JSONObject(body)
        val timeSeries = root.getJSONObject("value").getJSONArray("timeSeries")
        val map = LinkedHashMap<String, Acc>()

        for (i in 0 until timeSeries.length()) {
            // Skip any malformed/odd station rather than failing the whole response.
            try {
                val ts = timeSeries.getJSONObject(i)
                val src = ts.getJSONObject("sourceInfo")
                val siteId = src.getJSONArray("siteCode").getJSONObject(0).getString("value")
                val geo = src.getJSONObject("geoLocation").getJSONObject("geogLocation")

                val varCode = ts.getJSONObject("variable")
                    .getJSONArray("variableCode").getJSONObject(0).getString("value")

                val valuesOuter = ts.getJSONArray("values")
                if (valuesOuter.length() == 0) continue
                val values = valuesOuter.getJSONObject(0).getJSONArray("value")
                if (values.length() == 0) continue
                val latest = values.getJSONObject(values.length() - 1)
                val v = latest.optString("value").toDoubleOrNull() ?: continue
                if (v == NO_DATA) continue

                // Only record the site once we have a usable value for it.
                val acc = map.getOrPut(siteId) { Acc() }
                acc.name = src.optString("siteName", acc.name)
                acc.lat = geo.getDouble("latitude")
                acc.lon = geo.getDouble("longitude")
                latest.optString("dateTime", null)?.let { acc.updated = it }

                when (varCode) {
                    P_FLOW -> acc.flow = v
                    P_GAGE -> acc.gage = v
                    P_TEMP -> acc.temp = v
                }
            } catch (_: Exception) {
                // Ignore this time-series and keep going.
            }
        }

        return map.map { (id, a) ->
            RiverReading(
                siteId = id,
                name = cleanName(a.name),
                lat = a.lat,
                lon = a.lon,
                flowCfs = a.flow,
                gageFt = a.gage,
                tempC = a.temp,
                updated = a.updated
            )
        }
    }

    /** USGS names are ALL CAPS with a trailing ", WA" — tidy them for display. */
    private fun cleanName(raw: String): String {
        var s = raw.substringBeforeLast(", WA").trim()
        if (s.isEmpty()) s = raw
        return s.split(" ").joinToString(" ") { w ->
            if (w.length <= 2) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
