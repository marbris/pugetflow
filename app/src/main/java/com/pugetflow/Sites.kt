package com.pugetflow

/**
 * The USGS monitoring stations to display. These are real, currently-active
 * gauges in the Seattle / King County area (verified against the USGS
 * Instantaneous Values service).
 *
 * To add or remove rivers, just edit this list — find site numbers at
 * https://waterdata.usgs.gov/wa/nwis/current/?type=flow  (the 8-digit number).
 *
 * Coordinates here are only a fallback label position; the live feed supplies
 * the authoritative lat/lon for each site on every refresh.
 */
object Sites {

    // 8-digit USGS site numbers.
    val SITE_IDS: List<String> = listOf(
        "12113390", // Duwamish River at Tukwila (flow + temp)
        "12119000", // Cedar River at Renton (flow + temp)
        "12113000", // Green River near Auburn
        "12144500", // Snoqualmie River near Snoqualmie
        "12145500", // Raging River near Fall City
        "12121600", // Issaquah Creek near mouth
        "12117500", // Cedar River near Landsburg (flow + temp)
        "12147500", // North Fork Tolt River near Carnation (flow + temp)
        "12138160"  // Sultan River below powerplant (flow + temp)
    )
}
