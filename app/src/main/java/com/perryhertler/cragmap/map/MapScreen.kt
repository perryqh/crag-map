package com.perryhertler.cragmap.map

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.perryhertler.cragmap.CragMapApplication
import com.perryhertler.cragmap.data.AppDatabase
import com.perryhertler.cragmap.data.AreaEntity
import com.perryhertler.cragmap.data.ClimbEntity
import com.perryhertler.cragmap.search.SearchBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.FeatureCollection

// Devil's Lake climbing-area centroid (verified live against OpenBeta's area metadata).
private val DEVILS_LAKE_CENTER = LatLng(43.41655, -89.72343)
private const val INITIAL_ZOOM = 13.5

// Zoom bands from the blueprint's zoom-tiered UI table.
private const val PARK_MIN = 0.0
private const val PARK_MAX = 13.0
private const val BLUFF_MIN = 13.0
private const val BLUFF_MAX = 15.0
private const val SUBAREA_MIN = 15.0
private const val SUBAREA_MAX = 17.0
private const val FORMATION_MIN = 17.0
private const val FORMATION_MAX = 22.0

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var sheetClimbs by remember { mutableStateOf<List<ClimbEntity>?>(null) }
    var sheetAreaName by remember { mutableStateOf("") }
    var highlightedClimbUuid by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        ) { mv ->
            // AndroidView's update lambda re-runs on every recomposition of MapScreen
            // (e.g. whenever sheetClimbs changes, which happens on every pin tap / search
            // selection). Without this guard, each recomposition called getMapAsync again,
            // which called setStyle again, which replaced the whole style — wiping every
            // pin layer just added and snapping the camera back to the default position.
            // Real one-time init belongs in `factory`, but getMapAsync is easiest to keep
            // here guarded by "have we already done this."
            if (mapLibreMap == null) mv.getMapAsync { map ->
                Log.d("CragMap", "getMapAsync fired, map=$map")
                mapLibreMap = map
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(DEVILS_LAKE_CENTER)
                    .zoom(INITIAL_ZOOM)
                    .build()

                // Fetch data BEFORE calling setStyle at all, so every source/layer is part
                // of the initial style-load transaction instead of being added dynamically
                // to an already-loaded style (dynamic post-load addSource/addLayer produced
                // registered-but-empty sources when tested on-device: layer present, correct
                // zoom range, visible, but zero renderable features and nothing drawn).
                scope.launch {
                    try {
                        val park = withContext(Dispatchers.IO) { db.areaDao().areasAtDepth(0) }
                        val bluffs = withContext(Dispatchers.IO) { db.areaDao().areasAtDepth(1) }
                        val subareas = withContext(Dispatchers.IO) { db.areaDao().areasAtDepth(2) }
                        val formations = withContext(Dispatchers.IO) { db.areaDao().leafAreas() }
                        Log.d("CragMap", "fetched park=${park.size} bluffs=${bluffs.size} subareas=${subareas.size} formations=${formations.size}")

                        val builder = buildBaseStyle(context)
                        addAreaLayer(builder, "park", park, "#0969da", PARK_MIN, PARK_MAX)
                        addAreaLayer(builder, "bluff", bluffs, "#0969da", BLUFF_MIN, BLUFF_MAX)
                        addAreaLayer(builder, "subarea", subareas, "#7c3aed", SUBAREA_MIN, SUBAREA_MAX)
                        addAreaLayer(builder, "formation", formations, "#cf222e", FORMATION_MIN, FORMATION_MAX)

                        map.setStyle(builder) { style ->
                            Log.d("CragMap", "style loaded, fully loaded=${style.isFullyLoaded}, layers=${style.layers.map { it.id }}")
                        }
                    } catch (e: Exception) {
                        Log.e("CragMap", "area-layer setup failed", e)
                    }
                }

                map.addOnMapClickListener { latLng ->
                    val point = map.projection.toScreenLocation(latLng)
                    val features = map.queryRenderedFeatures(point, "formation-circle")
                    val feature = features.firstOrNull()
                    if (feature != null) {
                        val uuid = feature.getStringProperty("uuid")
                        val name = feature.getStringProperty("name")
                        scope.launch {
                            val climbs = withContext(Dispatchers.IO) { db.climbDao().climbsInArea(uuid) }
                            sheetClimbs = climbs
                            sheetAreaName = name
                            highlightedClimbUuid = null
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        }

        SearchBar(
            modifier = Modifier.fillMaxWidth(),
            db = db,
            onResultSelected = { result ->
                Log.d("CragMap", "search result selected: $result, mapLibreMap=$mapLibreMap")
                val map = mapLibreMap
                if (map == null) {
                    Log.w("CragMap", "onResultSelected: mapLibreMap was null, skipping camera move")
                } else if (result.lat != null && result.lng != null) {
                    map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(result.lat, result.lng), FORMATION_MIN + 0.5),
                        800
                    )
                } else {
                    Log.w("CragMap", "onResultSelected: result had null lat/lng")
                }
                scope.launch {
                    try {
                        val climbs = withContext(Dispatchers.IO) { db.climbDao().climbsInArea(result.areaUuid) }
                        Log.d("CragMap", "climbsInArea(${result.areaUuid}) -> ${climbs.size} climbs")
                        sheetClimbs = climbs
                        sheetAreaName = "" // area name not needed for the highlighted case
                        highlightedClimbUuid = result.uuid
                    } catch (e: Exception) {
                        Log.e("CragMap", "failed to load climbs for bottom sheet", e)
                    }
                }
            }
        )

        val climbs = sheetClimbs
        if (climbs != null) {
            ClimbBottomSheet(
                areaName = sheetAreaName,
                climbs = climbs,
                highlightedClimbUuid = highlightedClimbUuid,
                onDismiss = { sheetClimbs = null }
            )
        }
    }
}

private fun buildBaseStyle(context: Context): Style.Builder {
    val port = (context.applicationContext as CragMapApplication).tileServerPort
    val tileUrl = "http://127.0.0.1:$port/tiles/{z}/{x}/{y}.jpg"
    val tileSet = TileSet("2.1.0", tileUrl).apply {
        minZoom = 13f
        maxZoom = 16f
    }
    val rasterSource = RasterSource("usgs-topo", tileSet, 256)
    val rasterLayer = RasterLayer("usgs-topo-layer", "usgs-topo")
    return Style.Builder()
        .withSource(rasterSource)
        .withLayer(rasterLayer)
}

private fun addAreaLayer(
    style: Style.Builder,
    id: String,
    areas: List<AreaEntity>,
    color: String,
    minZoom: Double,
    maxZoom: Double
) {
    val geoJson = areasToFeatureCollectionJson(areas)
    val source = GeoJsonSource("$id-source", FeatureCollection.fromJson(geoJson))
    style.withSource(source)

    val circleLayer = CircleLayer("$id-circle", "$id-source").withProperties(
        PropertyFactory.circleRadius(9f),
        PropertyFactory.circleColor(color),
        PropertyFactory.circleStrokeWidth(1.5f),
        PropertyFactory.circleStrokeColor("#ffffff")
    )
    circleLayer.setMinZoom(minZoom.toFloat())
    circleLayer.setMaxZoom(maxZoom.toFloat())
    style.withLayer(circleLayer)

    // No SymbolLayer (text label) here on purpose: on this device (Pixel 11 Pro XL /
    // Imagination PowerVR GPU) a SymbolLayer sharing a GeoJsonSource with a CircleLayer
    // silently broke rendering for BOTH layers — confirmed by A/B test on-device (circles
    // rendered correctly the instant the sibling SymbolLayer was removed). This looks like
    // a MapLibre Native text/glyph rendering bug on this GPU, not an app bug — see the
    // similar PowerVR/Vulkan rendering reports at https://github.com/maplibre/maplibre-compose/issues/1370.
    // Labels are available via tapping a pin (bottom sheet) instead, for now.
}

private fun areasToFeatureCollectionJson(areas: List<AreaEntity>): String {
    val features = areas
        .filter { it.lat != null && it.lng != null }
        .joinToString(",") { a ->
            val props = JSONObject()
                .put("uuid", a.uuid)
                .put("name", a.name)
                .put("totalClimbs", a.totalClimbs)
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${a.lng},${a.lat}]},"properties":$props}"""
        }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    return mapView
}
