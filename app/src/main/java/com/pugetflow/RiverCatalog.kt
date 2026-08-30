package com.pugetflow

/**
 * Rivers you can profile in PugetFlow. Each entry names a near-mouth "seed" USGS
 * gauge; NLDI navigates the upstream mainstem from there to find the river's
 * gauges and flowline. Edit freely — the seed is just a starting point on the river.
 */
object RiverCatalog {

    data class River(val name: String, val seedSiteId: String)

    val RIVERS: List<River> = listOf(
        River("Cedar River", "12119000"),          // at Renton
        River("Green / Duwamish River", "12113390"),// Duwamish at Tukwila
        River("Snoqualmie River", "12144500"),      // near Snoqualmie
        River("Raging River", "12145500"),          // near Fall City
        River("Tolt River", "12148500"),            // near Carnation
        River("Issaquah Creek", "12121600"),        // near mouth
        River("Sammamish River", "12125200"),       // near Redmond
        River("Sultan River", "12138160"),          // below powerplant
        River("Skykomish River", "12134500"),       // near Gold Bar
        River("Snohomish River", "12150800"),       // near Monroe
        River("Puyallup River", "12101500"),        // at Puyallup
        River("Nisqually River", "12089500")        // at McKenna
    )
}
