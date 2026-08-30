package com.pugetflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.UpdateMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint

/**
 * Wraps the OsmAnd AIDL service: binds to it, and publishes a custom map layer
 * of river points that OsmAnd draws on top of the map. Values live in each
 * point's context-menu detail rows; the circle colour encodes water temperature.
 *
 * Method/constructor signatures here match the official osmand-api-demo
 * (OsmAndAidlHelper): AMapLayer(id, name, zOrder, points),
 * AMapPoint(pointId, shortName, fullName, typeName, layerId, color, ALatLon, details, params).
 */
class OsmAndBridge(private val context: Context) {

    companion object {
        const val LAYER_ID = "pugetflow_rivers"
        const val LAYER_NAME = "USGS Rivers"
        private const val AIDL_SERVICE = "net.osmand.aidl.OsmandAidlServiceV2"
        // Checked in priority order.
        private val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
    }

    private var api: IOsmAndAidlInterface? = null
    private var layerAdded = false
    private var pending: List<RiverReading>? = null

    /** Invoked on the binder thread once the service connects. */
    var onConnected: (() -> Unit)? = null

    val isConnected: Boolean get() = api != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            api = IOsmAndAidlInterface.Stub.asInterface(service)
            layerAdded = false
            pending?.let { publish(it) }
            pending = null
            onConnected?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            layerAdded = false
        }
    }

    /** Returns the installed OsmAnd package name, or null if OsmAnd isn't installed. */
    fun osmandPackage(): String? {
        val pm = context.packageManager
        for (pkg in OSMAND_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    /** @return true if a bind was initiated (or already bound). */
    fun bind(): Boolean {
        if (api != null) return true
        val pkg = osmandPackage() ?: return false
        val intent = Intent(AIDL_SERVICE).apply { setPackage(pkg) }
        var flags = Context.BIND_AUTO_CREATE
        if (Build.VERSION.SDK_INT >= 34) flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS
        return context.bindService(intent, connection, flags)
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
        }
        api = null
        layerAdded = false
    }

    /**
     * Push the given readings to OsmAnd. If the service isn't connected yet the
     * readings are queued and sent as soon as it connects.
     */
    fun publish(readings: List<RiverReading>) {
        val a = api
        if (a == null) {
            pending = readings
            return
        }
        val points = ArrayList<AMapPoint>(readings.size)
        for (r in readings) points.add(toPoint(r))
        val layer = AMapLayer(LAYER_ID, LAYER_NAME, 5.5f, points)
        try {
            if (!layerAdded) {
                a.addMapLayer(AddMapLayerParams(layer))
                layerAdded = true
            } else {
                a.updateMapLayer(UpdateMapLayerParams(layer))
            }
        } catch (e: RemoteException) {
            // OsmAnd process may have died; force a re-add next time.
            layerAdded = false
        }
    }

    fun removeLayer() {
        val a = api ?: return
        try {
            a.removeMapLayer(RemoveMapLayerParams(LAYER_ID))
        } catch (_: RemoteException) {
        }
        layerAdded = false
    }

    private fun toPoint(r: RiverReading): AMapPoint {
        val details = ArrayList<String>()
        r.flowCfs?.let { details.add("Flow: ${fmt(it)} ft³/s") }
        r.gageFt?.let { details.add("Gage height: ${fmt(it)} ft") }
        r.tempC?.let { details.add("Water temp: ${fmt(it)} °C") }
        r.updated?.let { details.add("Updated: ${prettyTime(it)}") }

        val shortName = r.name.firstOrNull { it.isLetter() }?.uppercase() ?: "R"
        val typeName = "USGS ${r.siteId}"
        val color = colorForTemp(r.tempC)
        val location = ALatLon(r.lat, r.lon)

        return AMapPoint(
            r.siteId,           // pointId
            shortName,          // shortName (shown on the map marker)
            r.name,             // fullName (context menu, first row)
            typeName,           // typeName (context menu, second row)
            LAYER_ID,           // layerId
            color,              // circle background colour (ARGB)
            location,           // ALatLon
            details,            // detail rows
            HashMap<String, String>() // params (none needed)
        )
    }

    /** Cold → warm colour ramp; grey-blue when no temperature is reported. */
    private fun colorForTemp(tempC: Double?): Int {
        if (tempC == null) return Color.rgb(96, 125, 139) // blue-grey
        return when {
            tempC < 8 -> Color.rgb(13, 71, 161)    // deep blue
            tempC < 12 -> Color.rgb(2, 136, 209)   // blue
            tempC < 16 -> Color.rgb(0, 150, 136)   // teal
            tempC < 19 -> Color.rgb(124, 179, 66)  // green
            tempC < 22 -> Color.rgb(251, 140, 0)   // orange
            else -> Color.rgb(211, 47, 47)         // red (fish-stress warm)
        }
    }

    private fun fmt(v: Double): String =
        if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else String.format("%.1f", v)

    private fun prettyTime(iso: String): String {
        // e.g. 2026-08-30T10:15:00.000-07:00 -> 08-30 10:15
        return try {
            val date = iso.substringBefore("T").substringAfter("-").replace("-", "-")
            val time = iso.substringAfter("T").take(5)
            "$date $time"
        } catch (_: Exception) {
            iso
        }
    }
}
