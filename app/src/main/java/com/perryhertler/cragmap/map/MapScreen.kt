package com.perryhertler.cragmap.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.FileProvider
import com.perryhertler.cragmap.CragMapApplication
import com.perryhertler.cragmap.data.AppDatabase
import com.perryhertler.cragmap.data.AreaEntity
import com.perryhertler.cragmap.data.ClimbEntity
import com.perryhertler.cragmap.data.PinOverrideDatabase
import com.perryhertler.cragmap.data.PinOverrideEntity
import com.perryhertler.cragmap.data.overridesToJson
import com.perryhertler.cragmap.search.SearchBar
import com.perryhertler.cragmap.search.SearchResultItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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
import org.maplibre.geojson.Point

// Devil's Lake climbing-area centroid (verified live against OpenBeta's area metadata).
private val DEVILS_LAKE_CENTER = LatLng(43.41655, -89.72343)
private const val INITIAL_ZOOM = 13.5

// Fixed fraction of screen height to reserve at the bottom when centering a
// search result, so the highlighted pin lands above where the bottom sheet
// covers the map instead of right underneath its top edge. A fixed fraction
// (rather than the sheet's real measured height) is simpler and good enough —
// see the "should the area be top-centered" discussion this came out of.
private const val BOTTOM_SHEET_PADDING_FRACTION = 0.55f

// Zoom bands. "Intermediate" covers every non-leaf area from depth 2 down —
// not just a hardcoded depth-2 "subarea" — since Devils Lake's real tree
// runs to depth 6 in places and anything past depth 2 used to have no pin at
// any zoom level at all. Leaf areas (is_leaf=1) always get the closest band,
// regardless of which depth they actually sit at — some formations are
// direct depth-1 children (e.g. the bouldering area), others are 3-4 levels
// deep, and both need to be tappable to see their climbs.
private const val PARK_MIN = 0.0
private const val PARK_MAX = 13.0
private const val BLUFF_MIN = 13.0
private const val BLUFF_MAX = 15.0
private const val INTERMEDIATE_MIN = 15.0
private const val INTERMEDIATE_MAX = 17.0
private const val LEAF_MIN = 17.0
private const val LEAF_MAX = 22.0

private const val NEAR_ME_MAX_DISTANCE_M = 150.0

// A label positioned in screen pixels (from MapLibreMap.projection), rendered as a
// plain Compose overlay rather than a MapLibre SymbolLayer — see addAreaLayer's comment.
// Every label is clickable now: tapping any area (leaf or not) does the same thing
// tapping its dot does — open a climb list or a child-area list.
private data class MapLabel(val area: AreaEntity, val text: String, val x: Int, val y: Int)

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    val db = remember { AppDatabase.getInstance(context) }
    val overrideDb = remember { PinOverrideDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var parkAreas by remember { mutableStateOf<List<AreaEntity>>(emptyList()) }
    var bluffAreas by remember { mutableStateOf<List<AreaEntity>>(emptyList()) }
    var intermediateAreas by remember { mutableStateOf<List<AreaEntity>>(emptyList()) }
    var leafAreas by remember { mutableStateOf<List<AreaEntity>>(emptyList()) }
    var mapLabels by remember { mutableStateOf<List<MapLabel>>(emptyList()) }
    var sheetContent by remember { mutableStateOf<SheetContent?>(null) }
    var nearMeResults by remember { mutableStateOf<List<NearbyFormation>?>(null) }
    // Phase 3 field-survey mode (see PinOverride.kt). Off by default and not
    // persisted across launches — a personal on-the-ground tool, not a
    // feature aimed at typical users.
    var editModeEnabled by remember { mutableStateOf(false) }
    var overrideCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        overrideCount = withContext(Dispatchers.IO) { overrideDb.pinOverrideDao().all().size }
    }
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationPermissionGranted = granted
        if (granted) {
            val map = mapLibreMap
            val style = loadedStyle
            if (map != null && style != null) enableLocationComponent(context, map, style)
        }
    }
    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Opens the sheet for one area — climbs if it's a formation, its children
    // (in cliff order) if it's a disclosure node — and sets the map highlight.
    // Shared by the map's own tap handler, tapping a label, search selection,
    // prev/next, and tapping a child row in the sheet itself, so all of them
    // stay identical by construction.
    fun selectArea(area: AreaEntity, highlightedClimbUuid: String? = null) {
        if (area.lat != null && area.lng != null) {
            loadedStyle?.let { setHighlight(it, area.lat, area.lng) }
        }
        scope.launch {
            try {
                sheetContent = withContext(Dispatchers.IO) {
                    buildSheetContent(db, area, highlightedClimbUuid)
                }
            } catch (e: Exception) {
                Log.e("CragMap", "failed to build sheet content for ${area.uuid}", e)
            }
        }
    }

    fun navigateSibling(offset: Int) {
        val content = sheetContent ?: return
        val idx = content.siblings.indexOfFirst { it.uuid == content.area.uuid }
        if (idx < 0) return
        val newIdx = idx + offset
        if (newIdx !in content.siblings.indices) return
        val target = content.siblings[newIdx]
        if (target.lat != null && target.lng != null) {
            mapLibreMap?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(target.lat, target.lng)), 400)
        }
        selectArea(target)
    }

    // Takes one on-demand GPS fix (same single-shot pattern as Near Me and the
    // recenter FAB — never a loop) and stores it as a field-survey override for
    // Phase 3. Deliberately doesn't touch the live map/sheet: this is a capture
    // tool, not a live editor — the override only takes effect once exported
    // and merged into the next devils_lake.db rebuild via
    // tools/merge_pin_overrides.py.
    fun capturePin(targetUuid: String, targetType: String, targetName: String) {
        val locationComponent = mapLibreMap?.locationComponent
        val location = if (locationComponent?.isLocationComponentActivated == true) {
            locationComponent.lastKnownLocation
        } else {
            null
        }
        if (location == null) {
            Toast.makeText(context, "Still waiting for a GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                overrideDb.pinOverrideDao().upsert(
                    PinOverrideEntity(
                        targetUuid = targetUuid,
                        targetType = targetType,
                        targetName = targetName,
                        lat = location.latitude,
                        lng = location.longitude,
                        capturedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            overrideCount = withContext(Dispatchers.IO) { overrideDb.pinOverrideDao().all().size }
            Toast.makeText(context, "Captured pin for $targetName", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportOverrides() {
        scope.launch {
            try {
                val overrides = withContext(Dispatchers.IO) { overrideDb.pinOverrideDao().all() }
                if (overrides.isEmpty()) {
                    Toast.makeText(context, "No captured pins to export yet", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, "pin_overrides.json").apply { writeText(overridesToJson(overrides)) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Export pin overrides"))
            } catch (e: Exception) {
                Log.e("CragMap", "failed to export pin overrides", e)
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()

    // Text labels drawn as a plain Compose overlay, positioned via the map's own
    // screen-projection — not a MapLibre SymbolLayer, which silently breaks
    // rendering on this device's GPU (see addAreaLayer's comment). Recomputed
    // whenever the camera settles (pan/zoom/animation), not on every frame of a
    // gesture, to keep this cheap.
    //
    // Greedy overlap avoidance: candidates closest to the screen center are placed
    // first, and any label whose measured bounding box would overlap an
    // already-placed one is dropped rather than drawn on top of it illegibly —
    // zooming in spreads pins out and reveals the ones that got hidden.
    fun refreshLabels(map: MapLibreMap, mv: MapView) {
        val zoom = map.cameraPosition.zoom
        val (areas, showCount) = when {
            zoom < BLUFF_MIN -> parkAreas to true
            zoom < INTERMEDIATE_MIN -> bluffAreas to false
            zoom < LEAF_MIN -> intermediateAreas to false
            else -> leafAreas to false
        }
        val width = mv.width
        val height = mv.height
        val centerX = width / 2f
        val centerY = height / 2f

        data class Candidate(val area: AreaEntity, val text: String, val x: Int, val y: Int, val distSq: Float)

        val candidates = areas.mapNotNull { area ->
            val lat = area.lat ?: return@mapNotNull null
            val lng = area.lng ?: return@mapNotNull null
            val screenPt = map.projection.toScreenLocation(LatLng(lat, lng))
            if (screenPt.x < -100 || screenPt.x > width + 100 || screenPt.y < -100 || screenPt.y > height + 100) {
                return@mapNotNull null
            }
            val text = if (showCount) "${area.name} (${area.totalClimbs})" else area.name
            val dx = screenPt.x - centerX
            val dy = screenPt.y - centerY
            Candidate(area, text, screenPt.x.toInt(), screenPt.y.toInt(), dx * dx + dy * dy)
        }.sortedBy { it.distSq }

        val placedBoxes = mutableListOf<IntArray>() // left, top, right, bottom
        val result = mutableListOf<MapLabel>()
        for (c in candidates) {
            val measured = textMeasurer.measure(c.text, style = TextStyle(fontSize = 11.sp))
            val left = c.x + 6
            val top = c.y - 8
            val right = left + measured.size.width + 6
            val bottom = top + measured.size.height + 2
            val overlaps = placedBoxes.any { box -> left < box[2] && right > box[0] && top < box[3] && bottom > box[1] }
            if (!overlaps) {
                placedBoxes += intArrayOf(left, top, right, bottom)
                result += MapLabel(c.area, c.text, c.x, c.y)
            }
        }
        mapLabels = result
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        ) { mv ->
            // AndroidView's update lambda re-runs on every recomposition of MapScreen
            // (e.g. whenever sheetContent changes, which happens on every pin tap /
            // search selection). Without this guard, each recomposition called
            // getMapAsync again, which called setStyle again, which replaced the
            // whole style — wiping every pin layer just added and snapping the
            // camera back to the default position. Real one-time init belongs in
            // `factory`, but getMapAsync is easiest to keep here guarded by "have
            // we already done this."
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
                        val intermediate = withContext(Dispatchers.IO) { db.areaDao().intermediateAreas() }
                        val leaves = withContext(Dispatchers.IO) { db.areaDao().leafAreas() }
                        Log.d(
                            "CragMap",
                            "fetched park=${park.size} bluffs=${bluffs.size} intermediate=${intermediate.size} leaves=${leaves.size}"
                        )
                        parkAreas = park
                        bluffAreas = bluffs
                        intermediateAreas = intermediate
                        leafAreas = leaves

                        val builder = buildBaseStyle(context)
                        addAreaLayer(builder, "park", park, "#0969da", PARK_MIN, PARK_MAX)
                        addAreaLayer(builder, "bluff", bluffs, "#0969da", BLUFF_MIN, BLUFF_MAX)
                        addAreaLayer(builder, "intermediate", intermediate, "#7c3aed", INTERMEDIATE_MIN, INTERMEDIATE_MAX)
                        addAreaLayer(builder, "leaf", leaves, "#cf222e", LEAF_MIN, LEAF_MAX)
                        addHighlightLayer(builder)

                        map.setStyle(builder) { style ->
                            Log.d("CragMap", "style loaded, fully loaded=${style.isFullyLoaded}, layers=${style.layers.map { it.id }}")
                            loadedStyle = style
                            if (locationPermissionGranted) enableLocationComponent(context, map, style)
                            refreshLabels(map, mv)
                        }
                    } catch (e: Exception) {
                        Log.e("CragMap", "area-layer setup failed", e)
                    }
                }

                map.addOnCameraIdleListener { refreshLabels(map, mv) }

                map.addOnMapClickListener { latLng ->
                    val point = map.projection.toScreenLocation(latLng)
                    val features = map.queryRenderedFeatures(
                        point, "park-circle", "bluff-circle", "intermediate-circle", "leaf-circle"
                    )
                    val feature = features.firstOrNull()
                    if (feature != null) {
                        val uuid = feature.getStringProperty("uuid")
                        scope.launch {
                            val area = withContext(Dispatchers.IO) { db.areaDao().getArea(uuid) }
                            if (area != null) selectArea(area)
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        }

        mapLabels.forEach { label ->
            Text(
                text = label.text,
                fontSize = 11.sp,
                color = Color(0xFF1F2328),
                modifier = Modifier
                    .offset { IntOffset(label.x + 6, label.y - 8) }
                    .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                    .clickable { selectArea(label.area) }
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)) {
        SearchBar(
            modifier = Modifier.weight(1f),
            db = db,
            onResultSelected = { item ->
                val map = mapLibreMap
                if (map == null) {
                    Log.w("CragMap", "onResultSelected: mapLibreMap was null, skipping camera move")
                    return@SearchBar
                }
                val bottomPadding = (mapView.height * BOTTOM_SHEET_PADDING_FRACTION).toInt()
                map.setPadding(0, 0, 0, bottomPadding)

                when (item) {
                    is SearchResultItem.Climb -> {
                        val result = item.result
                        if (result.lat != null && result.lng != null) {
                            map.easeCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(result.lat, result.lng), LEAF_MIN + 0.5),
                                800
                            )
                            loadedStyle?.let { setHighlight(it, result.lat, result.lng) }
                        }
                        scope.launch {
                            try {
                                val area = withContext(Dispatchers.IO) { db.areaDao().getArea(result.areaUuid) }
                                if (area != null) selectArea(area, highlightedClimbUuid = result.uuid)
                            } catch (e: Exception) {
                                Log.e("CragMap", "failed to open sheet for search climb result", e)
                            }
                        }
                    }
                    is SearchResultItem.Area -> {
                        val result = item.result
                        if (result.lat != null && result.lng != null) {
                            val zoom = if (result.isLeaf == 1) LEAF_MIN + 0.5 else INTERMEDIATE_MIN + 0.5
                            map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(result.lat, result.lng), zoom), 800)
                        }
                        scope.launch {
                            try {
                                val area = withContext(Dispatchers.IO) { db.areaDao().getArea(result.uuid) }
                                if (area != null) selectArea(area)
                            } catch (e: Exception) {
                                Log.e("CragMap", "failed to open sheet for search area result", e)
                            }
                        }
                    }
                }
            }
        )
        IconButton(
            onClick = { editModeEnabled = !editModeEnabled },
            modifier = Modifier.padding(top = 12.dp, end = 4.dp)
        ) {
            Icon(
                Icons.Filled.EditLocationAlt,
                contentDescription = "Toggle field-survey edit mode",
                tint = if (editModeEnabled) Color(0xFFCF6600) else Color.Gray
            )
        }
        }

        // "Near me": one on-demand GPS fix (not continuous polling — see the
        // closed corridor-survey branch this replaces), ranking formations by
        // straight-line distance within a short radius. Deliberately just a
        // shortlist, never a claim about the exact route — see NearMe.kt.
        FloatingActionButton(
            onClick = {
                val map = mapLibreMap
                val locationComponent = map?.locationComponent
                val location = if (locationComponent?.isLocationComponentActivated == true) {
                    locationComponent.lastKnownLocation
                } else {
                    null
                }
                if (location == null) {
                    Toast.makeText(context, "Still waiting for a GPS fix…", Toast.LENGTH_SHORT).show()
                } else {
                    nearMeResults = rankNearbyFormations(
                        location.latitude,
                        location.longitude,
                        leafAreas,
                        maxDistanceM = NEAR_ME_MAX_DISTANCE_M
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.NearMe, contentDescription = "What's near me")
        }

        // "Recenter on my location" — the location dot shows where you are, but
        // there was previously no way to actually jump the camera there. GPS
        // works via satellites, so this functions with zero cell signal.
        FloatingActionButton(
            onClick = {
                val map = mapLibreMap
                // locationComponent.lastKnownLocation throws
                // LocationComponentNotInitializedException (not just null) if called
                // before activateLocationComponent() has run, which only happens once
                // the map's style finishes loading — a real race if this is tapped in
                // the first moment after launch.
                val locationComponent = map?.locationComponent
                val location = if (locationComponent?.isLocationComponentActivated == true) {
                    locationComponent.lastKnownLocation
                } else {
                    null
                }
                if (map != null && location != null) {
                    map.easeCamera(
                        CameraUpdateFactory.newLatLng(LatLng(location.latitude, location.longitude)),
                        800
                    )
                } else {
                    Toast.makeText(context, "Still waiting for a GPS fix…", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Recenter on my location")
        }

        nearMeResults?.let { results ->
            NearMePanel(
                results = results,
                onSelect = { nearby -> selectArea(nearby.area) },
                onDismiss = { nearMeResults = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 80.dp)
            )
        }

        sheetContent?.let { content ->
            AreaSheet(
                content = content,
                onSelectArea = { area -> selectArea(area) },
                onNavigateSibling = { offset -> navigateSibling(offset) },
                onDismiss = {
                    sheetContent = null
                    loadedStyle?.let { clearHighlight(it) }
                    mapLibreMap?.setPadding(0, 0, 0, 0)
                },
                editModeEnabled = editModeEnabled,
                overrideCount = overrideCount,
                onCaptureAreaPin = { capturePin(content.area.uuid, "area", content.area.name) },
                onCaptureClimbPin = { climb: ClimbEntity -> capturePin(climb.uuid, "climb", climb.name) },
                onExportOverrides = { exportOverrides() }
            )
        }
    }
}

private const val HIGHLIGHT_SOURCE_ID = "highlight-source"
private const val HIGHLIGHT_LAYER_ID = "highlight-circle"
private val EMPTY_FEATURE_COLLECTION_JSON = """{"type":"FeatureCollection","features":[]}"""

private fun addHighlightLayer(style: Style.Builder) {
    val source = GeoJsonSource(HIGHLIGHT_SOURCE_ID, FeatureCollection.fromJson(EMPTY_FEATURE_COLLECTION_JSON))
    style.withSource(source)
    // Distinct ring around whichever pin is currently selected (search result or tapped
    // area). No zoom restriction — it should stay visible whatever zoom the user's at.
    // A plain CircleLayer, same as the (working) area pins — no SymbolLayer involved.
    val highlightLayer = CircleLayer(HIGHLIGHT_LAYER_ID, HIGHLIGHT_SOURCE_ID).withProperties(
        PropertyFactory.circleRadius(16f),
        PropertyFactory.circleOpacity(0f),
        PropertyFactory.circleStrokeWidth(3f),
        PropertyFactory.circleStrokeColor("#ffd700"),
        PropertyFactory.circleStrokeOpacity(1f)
    )
    style.withLayer(highlightLayer)
}

private fun setHighlight(style: Style, lat: Double, lng: Double) {
    val geoJson = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{}}]}"""
    style.getSourceAs<GeoJsonSource>(HIGHLIGHT_SOURCE_ID)?.setGeoJson(FeatureCollection.fromJson(geoJson))
}

private fun clearHighlight(style: Style) {
    style.getSourceAs<GeoJsonSource>(HIGHLIGHT_SOURCE_ID)?.setGeoJson(FeatureCollection.fromJson(EMPTY_FEATURE_COLLECTION_JSON))
}

private fun enableLocationComponent(context: Context, map: MapLibreMap, style: Style) {
    val options = LocationComponentActivationOptions.builder(context, style)
        .useDefaultLocationEngine(true)
        .build()
    map.locationComponent.apply {
        activateLocationComponent(options)
        isLocationComponentEnabled = true
        cameraMode = CameraMode.NONE
        renderMode = RenderMode.COMPASS
    }
    Log.d("CragMap", "location component enabled")
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
    // Labels are a separate Compose overlay instead (see MapLabel/refreshLabels above).
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
