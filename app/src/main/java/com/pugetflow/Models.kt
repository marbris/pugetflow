package com.pugetflow

/** One monitoring station's latest readings, assembled from USGS parameter codes. */
data class RiverReading(
    val siteId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val flowCfs: Double? = null,   // parameter 00060, ft³/s
    val gageFt: Double? = null,    // parameter 00065, ft
    val tempC: Double? = null,     // parameter 00010, °C
    val updated: String? = null    // ISO timestamp of the latest reading
)
