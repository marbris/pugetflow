# PugetFlow

A small Android companion app that draws **live USGS river gauges** onto your
**OsmAnd** map. It polls the USGS Instantaneous Values service every few minutes
and pushes each station into OsmAnd as a coloured point via OsmAnd's AIDL API —
tap a point to see current **streamflow, gage height, and water temperature**.

This is the "Tier 3" approach: a real app with a background service, live
auto-refresh, and an on-demand **Refresh now** button in the notification.

```
Android app ──HTTP──> USGS IV API ──parse──> AIDL custom map layer ──> OsmAnd
   (RiverService)      (waterservices)         (OsmAndBridge)          (map)
```

## What you get

- A custom **"USGS Rivers"** layer in OsmAnd with one point per station.
- Circle colour encodes **water temperature** (deep blue = cold → red = warm).
  Stations with no temperature sensor show blue-grey.
- Tap a point → context menu shows Flow / Gage height / Water temp / Updated time.
- A persistent notification with **Refresh now** and **Stop** actions.
- Auto-refresh every 5 minutes while the service runs.

## Requirements

- **OsmAnd** installed on the same device (OsmAnd, OsmAnd+, or the nightly build).
  Nothing else about OsmAnd needs configuring — the app talks to it over its
  built-in AIDL service.
- Android 7.0 (API 24) or newer.

## Build & install

The easiest path is **Android Studio** (it bundles a compatible JDK 17 + Android SDK):

1. `File ▸ Open` this `PugetFlow/` folder.
2. Let Gradle sync (it downloads Gradle 8.7 via the wrapper and the OsmAnd AIDL
   library from OsmAnd's Ivy repo — both are already configured).
3. Plug in your phone (USB debugging on) and press **Run**.

Command line (needs the Android SDK installed and `ANDROID_HOME` set, plus JDK 17):

```bash
cd PugetFlow
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug   # if a device/emulator is connected
```

> Note: this machine has no Android SDK, so the project has not been compiled
> here — it's built to compile in Android Studio. If the IDE flags anything,
> it'll be a trivial import/resource fix, not a design issue.

## Using it

1. Open **PugetFlow**, grant the notification permission when asked.
2. Tap **Start live updates**. The first refresh fires immediately.
3. Tap **Open OsmAnd** (or switch to OsmAnd). You'll see the river points.
   If the custom layer looks hidden, toggle it in OsmAnd's *Configure map*.
4. Tap **Refresh now** (in the app or the notification) any time you want a
   fresh pull.

## Customising which rivers show

Edit **`app/src/main/java/com/pugetflow/Sites.kt`** — it's just a list of
8-digit USGS site numbers. Find more at
<https://waterdata.usgs.gov/wa/nwis/current/?type=flow>.

The current list covers the Duwamish, Cedar (Renton + Landsburg), Green,
Snoqualmie, Raging, Issaquah Creek, North Fork Tolt, and Sultan rivers.

To instead show **every active gauge near you automatically**, swap the
`sites=` query in `UsgsClient.kt` for a bounding box, e.g.
`&bBox=-122.55,47.30,-121.75,47.95` (west,south,east,north) and drop the
`sites=` parameter.

## Known limitations / notes

- **Data cadence:** USGS gauges typically report every 15 minutes and transmit
  hourly, so "live" here means "latest published reading," not second-by-second.
- **Overlay toggling:** OsmAnd has a long-standing quirk where an external
  custom overlay can switch itself off when the screen sleeps or you app-switch
  ([OsmAnd #14993](https://github.com/osmandapp/OsmAnd/issues/14993)). Pressing
  Refresh re-pushes the layer; usually it reappears.
- **Battery:** the foreground service uses the `dataSync` type. On Android 15
  that type is time-limited per day; for always-on use you may want to raise the
  refresh interval.

## How the OsmAnd integration works (for future you)

- `OsmAndBridge` binds to the service action `net.osmand.aidl.OsmandAidlServiceV2`
  in the installed OsmAnd package, then calls:
  - `addMapLayer(AddMapLayerParams(AMapLayer(id, name, zOrder, points)))` once,
  - `updateMapLayer(UpdateMapLayerParams(layer))` on each refresh,
  - `removeMapLayer(RemoveMapLayerParams(id))` on stop.
- Each point is an `AMapPoint(pointId, shortName, fullName, typeName, layerId,
  color, ALatLon, details, params)`.
- The AIDL classes come from the `net.osmand:android-aidl-lib` AAR (see
  `app/build.gradle`), so no `.aidl` files are vendored here.

### Possible next steps

- A **button inside OsmAnd** (rather than the notification) is possible via the
  AIDL `ContextMenuButtonsParams` / `AContextMenuButton` API — it adds a button
  to a point's context menu that fires an intent back to this app.
- Add a second layer for **Seattle Fire 911** calls (Socrata dataset
  `kzjm-xkqj`) — same publish path, different fetcher.
