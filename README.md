# Crag Map

An offline, zoomable climb map for Android, starting with Devils Lake State Park (WI).

## Why

Mountain Project chunks a climbing area into disconnected per-wall pages (e.g. "Devils Lake E Bluff - E Rampart") with no way to see how areas relate to each other spatially, and no offline story. Devils Lake's East Bluff has no cell service.

Crag Map instead renders every climb as a pin on a single pannable, zoomable topo map. Zoom out to see the whole park; zoom in and individual formations resolve. Search jumps straight to a route by name or grade. Map tiles and route data are bundled/cached on-device, so it works with zero signal at the crag.

## How it's built

- **Data**: [OpenBeta](https://openbeta.io)'s public GraphQL API — CC0-licensed, community-maintained climbing data. `tools/openbeta_export.py` recursively walks an area's tree (region → bluff → sub-area → formation → route) and writes a SQLite database matching the Android app's Room schema.
- **Basemap**: USGS Topo raster tiles (public domain, real contour lines). `tools/build_mbtiles.py` fetches tiles for a bounding box/zoom range into a standard MBTiles package.
- **App**: Kotlin + Jetpack Compose, [MapLibre Native](https://maplibre.org/) for the map, Room for the on-device database (bundled as a prepopulated asset), and a small embedded HTTP server that serves tiles out of the bundled MBTiles file to MapLibre's raster source. Zoom-tiered pin layers are keyed off each area's tree depth, not hardcoded to a specific park's hierarchy shape — a differently-shaped crag needs no schema or code changes, just a re-run of the two export scripts with a different root UUID and bounding box.

## Status

Working spike, verified on-device: pins render at every zoom band, tapping a formation opens its route list, search jumps the camera and highlights the selected route, and the current-location dot works via MapLibre's built-in location engine.

Known limitations:
- No on-map text labels — a `SymbolLayer` sharing a `GeoJsonSource` with a `CircleLayer` silently broke rendering for both on the test device's GPU (Imagination PowerVR). Route names/grades are still available by tapping a pin. See the comment in `MapScreen.kt`'s `addAreaLayer`.
- Single park (Devils Lake) only — the data/tile pipeline is generic, but there's no in-app UI yet for managing multiple downloaded areas.

## Tests

```
python3 tools/test_openbeta_export.py   # walk/depth/is_leaf logic, plagiarism filter, retry-on-timeout, schema
python3 tools/test_build_mbtiles.py     # bbox → tile-index math, MBTiles metadata
./gradlew testDebugUnitTest             # Room/FTS search, via Robolectric — no device/emulator needed
```

All three run offline against fixture data — no live network calls, no Android device. There's no instrumented (`androidTest`) coverage of the map/UI itself yet; that needs a device or emulator, neither of which was available while building this.

## Building it yourself

```
export JAVA_HOME=/path/to/a/jdk21
./gradlew assembleDebug
```

To regenerate the bundled data/tiles for a different area:

```
python3 tools/openbeta_export.py <openbeta-area-uuid> app/src/main/assets/<pack>.db
python3 tools/build_mbtiles.py <sw-lat> <sw-lng> <ne-lat> <ne-lng> <min-zoom> <max-zoom> app/src/main/assets/<pack>.mbtiles
```

## Data & basemap credit

Climbing data © [OpenBeta](https://openbeta.io) contributors, CC0. Basemap tiles from the [USGS National Map](https://www.usgs.gov/programs/national-geospatial-program/national-map), public domain.
