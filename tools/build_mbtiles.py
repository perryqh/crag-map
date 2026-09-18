#!/usr/bin/env python3
"""Fetch USGS National Map raster tiles for a bounding box / zoom range and
pack them into a standard MBTiles (SQLite) file for offline use in Crag Atlas.

Usage:
    python3 build_mbtiles.py <south> <west> <north> <east> <minzoom> <maxzoom> <output.mbtiles> [service]

service: USGSImageryTopo (default) | USGSImageryOnly | USGSTopo

Example (Devil's Lake — covers every bluff):
    python3 build_mbtiles.py 43.385 -89.765 43.440 -89.655 13 16 \\
        app/src/main/assets/devils_lake.mbtiles USGSImageryTopo

Note: For Devil's Lake, National Map XYZ tiles are populated through z16.
Higher LODs are advertised but return 404 for this area.
"""
from __future__ import annotations

import math
import sqlite3
import sys
import time
import urllib.error
import urllib.request

DEFAULT_SERVICE = "USGSImageryTopo"
TILE_URL_TEMPLATE = (
    "https://basemap.nationalmap.gov/arcgis/rest/services/{service}/MapServer/tile/{z}/{y}/{x}"
)
THROTTLE_SECONDS = 0.05


def deg2num(lat_deg: float, lon_deg: float, zoom: int) -> tuple[int, int]:
    lat_rad = math.radians(lat_deg)
    n = 2.0**zoom
    xtile = int((lon_deg + 180.0) / 360.0 * n)
    ytile = int(
        (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n
    )
    return xtile, ytile


def tiles_in_bbox(south: float, west: float, north: float, east: float, zoom: int):
    x_min, y_max = deg2num(south, west, zoom)
    x_max, y_min = deg2num(north, east, zoom)
    for x in range(min(x_min, x_max), max(x_min, x_max) + 1):
        for y in range(min(y_min, y_max), max(y_min, y_max) + 1):
            yield x, y


def fetch_tile(z: int, x: int, y: int, service: str = DEFAULT_SERVICE, retries: int = 3):
    url = TILE_URL_TEMPLATE.format(service=service, z=z, x=x, y=y)
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "crag-atlas-tiles/0.2 (offline personal map; USGS public domain)"
        },
    )
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read()
        except (urllib.error.HTTPError, urllib.error.URLError) as e:
            if attempt == retries - 1:
                print(f"  FAILED z={z} x={x} y={y}: {e}")
                return None
            time.sleep(2**attempt)


def init_mbtiles(
    path: str,
    bounds: tuple[float, float, float, float],
    minzoom: int,
    maxzoom: int,
    service: str = DEFAULT_SERVICE,
):
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
        ("name", f"Devils Lake {service}"),
        ("type", "baselayer"),
        ("version", "2"),
        (
            "description",
            f"{service} tiles from USGS National Map, public domain, offline pack",
        ),
        ("format", "jpg"),
        ("bounds", f"{west},{south},{east},{north}"),
        ("minzoom", str(minzoom)),
        ("maxzoom", str(maxzoom)),
        ("attribution", "USGS National Map"),
    ]
    cur.executemany("INSERT INTO metadata (name, value) VALUES (?, ?)", meta)
    conn.commit()
    return conn


def main() -> None:
    if len(sys.argv) not in (8, 9):
        print(__doc__)
        sys.exit(1)
    south, west, north, east = (float(a) for a in sys.argv[1:5])
    minzoom, maxzoom = int(sys.argv[5]), int(sys.argv[6])
    out_path = sys.argv[7]
    service = sys.argv[8] if len(sys.argv) == 9 else DEFAULT_SERVICE

    conn = init_mbtiles(
        out_path, (south, west, north, east), minzoom, maxzoom, service=service
    )
    cur = conn.cursor()

    total = 0
    failed = 0
    for z in range(minzoom, maxzoom + 1):
        tiles = list(tiles_in_bbox(south, west, north, east, z))
        print(f"zoom {z}: {len(tiles)} tiles ({service})")
        for x, y in tiles:
            data = fetch_tile(z, x, y, service=service)
            if data is None:
                failed += 1
                continue
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
