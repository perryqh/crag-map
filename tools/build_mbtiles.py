#!/usr/bin/env python3
"""Fetch USGS Topo raster tiles for a bounding box / zoom range and pack them
into a standard MBTiles (SQLite) file for offline use in the Crag Map app.

Usage:
    python3 build_mbtiles.py <south> <west> <north> <east> <minzoom> <maxzoom> <output.mbtiles>

Example (Devil's Lake — covers every bluff, derived from
MIN/MAX(lat/lng) across the exported area table plus a small buffer;
the original spike's box was eyeballed and missed West Bluff entirely):
    python3 build_mbtiles.py 43.385 -89.765 43.440 -89.655 13 16 devils_lake.mbtiles
"""
import math
import sqlite3
import sys
import time
import urllib.error
import urllib.request

TILE_URL = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
THROTTLE_SECONDS = 0.1


def deg2num(lat_deg, lon_deg, zoom):
    lat_rad = math.radians(lat_deg)
    n = 2.0**zoom
    xtile = int((lon_deg + 180.0) / 360.0 * n)
    ytile = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    return xtile, ytile


def tiles_in_bbox(south, west, north, east, zoom):
    x_min, y_max = deg2num(south, west, zoom)  # sw corner -> smaller x, larger y (rows count down from top)
    x_max, y_min = deg2num(north, east, zoom)  # ne corner -> larger x, smaller y
    for x in range(min(x_min, x_max), max(x_min, x_max) + 1):
        for y in range(min(y_min, y_max), max(y_min, y_max) + 1):
            yield x, y


def fetch_tile(z, x, y, retries=3):
    url = TILE_URL.format(z=z, x=x, y=y)
    req = urllib.request.Request(url, headers={"User-Agent": "crag-map-tiles/0.1 (personal offline-map spike)"})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read()
        except (urllib.error.HTTPError, urllib.error.URLError) as e:
            if attempt == retries - 1:
                print(f"  FAILED z={z} x={x} y={y}: {e}")
                return None
            time.sleep(2**attempt)


def init_mbtiles(path, bounds, minzoom, maxzoom):
    conn = sqlite3.connect(path)
    cur = conn.cursor()
    cur.executescript(
        """
        DROP TABLE IF EXISTS tiles;
        DROP TABLE IF EXISTS metadata;
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (
            zoom_level INTEGER,
            tile_column INTEGER,
            tile_row INTEGER,
            tile_data BLOB
        );
        CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
        """
    )
    south, west, north, east = bounds
    meta = [
        ("name", "Devils Lake USGS Topo (spike)"),
        ("type", "baselayer"),
        ("version", "1"),
        ("description", "USGS Topo tiles, public domain, pre-fetched for offline use"),
        ("format", "jpg"),  # USGSTopo MapServer returns JPEG tiles, not PNG
        ("bounds", f"{west},{south},{east},{north}"),
        ("minzoom", str(minzoom)),
        ("maxzoom", str(maxzoom)),
    ]
    cur.executemany("INSERT INTO metadata (name, value) VALUES (?, ?)", meta)
    conn.commit()
    return conn


def main():
    if len(sys.argv) != 8:
        print(__doc__)
        sys.exit(1)
    south, west, north, east = (float(a) for a in sys.argv[1:5])
    minzoom, maxzoom = int(sys.argv[5]), int(sys.argv[6])
    out_path = sys.argv[7]

    conn = init_mbtiles(out_path, (south, west, north, east), minzoom, maxzoom)
    cur = conn.cursor()

    total = 0
    failed = 0
    for z in range(minzoom, maxzoom + 1):
        tiles = list(tiles_in_bbox(south, west, north, east, z))
        print(f"zoom {z}: {len(tiles)} tiles")
        for x, y in tiles:
            data = fetch_tile(z, x, y)
            if data is None:
                failed += 1
                continue
            # MBTiles uses TMS row numbering (flipped from the XYZ y used to fetch).
            tms_y = (2**z - 1) - y
            cur.execute(
                "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
                (z, x, tms_y, data),
            )
            total += 1
            time.sleep(THROTTLE_SECONDS)
        conn.commit()

    conn.close()
    print(f"\nWrote {total} tiles ({failed} failed) to {out_path}")


if __name__ == "__main__":
    main()
