#!/usr/bin/env python3
"""Fetch USDA/USGS NAIP imagery tiles (z17–18) via ImageServer exportImage and
merge them into an existing Devil's Lake MBTiles pack.

NAIP is public domain. National Map XYZ basemaps stop at z16 for this area;
this fills cliff-base zoom.

Usage:
  python3 tools/build_naip_overlay.py <south> <west> <north> <east> \\
      <minzoom> <maxzoom> <input.mbtiles> <output.mbtiles>

Example:
  python3 tools/build_naip_overlay.py 43.385 -89.765 43.440 -89.655 17 18 \\
      app/src/main/assets/devils_lake.mbtiles /tmp/devils_lake_naip.mbtiles
"""
from __future__ import annotations

import argparse
import math
import shutil
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

EXPORT_URL = (
    "https://imagery.nationalmap.gov/arcgis/rest/services/"
    "USGSNAIPPlus/ImageServer/exportImage"
)
ORIGIN_SHIFT = 20037508.342789244
USER_AGENT = "crag-atlas-naip/0.1 (offline personal map; USDA/USGS public domain)"
WORKERS = 8


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


def tile_mercator_bbox(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    n = 2.0**z
    tile_size = (2 * ORIGIN_SHIFT) / n
    minx = -ORIGIN_SHIFT + x * tile_size
    maxx = -ORIGIN_SHIFT + (x + 1) * tile_size
    maxy = ORIGIN_SHIFT - y * tile_size
    miny = ORIGIN_SHIFT - (y + 1) * tile_size
    return minx, miny, maxx, maxy


def fetch_naip_tile(z: int, x: int, y: int, retries: int = 3) -> bytes | None:
    minx, miny, maxx, maxy = tile_mercator_bbox(z, x, y)
    params = {
        "bbox": f"{minx},{miny},{maxx},{maxy}",
        "bboxSR": "3857",
        "imageSR": "3857",
        "size": "256,256",
        "format": "jpg",
        "f": "image",
        "interpolation": "RSP_BilinearInterpolation",
    }
    url = EXPORT_URL + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
            if len(data) < 500:  # empty / error payload
                return None
            return data
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            if attempt == retries - 1:
                print(f"  FAILED z={z} x={x} y={y}: {e}")
                return None
            time.sleep(1.5 * (attempt + 1))
    return None


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("south", type=float)
    p.add_argument("west", type=float)
    p.add_argument("north", type=float)
    p.add_argument("east", type=float)
    p.add_argument("minzoom", type=int)
    p.add_argument("maxzoom", type=int)
    p.add_argument("input_mbtiles")
    p.add_argument("output_mbtiles")
    p.add_argument("--workers", type=int, default=WORKERS)
    args = p.parse_args()

    shutil.copy2(args.input_mbtiles, args.output_mbtiles)
    conn = sqlite3.connect(args.output_mbtiles)
    cur = conn.cursor()

    jobs: list[tuple[int, int, int]] = []
    for z in range(args.minzoom, args.maxzoom + 1):
        tiles = list(tiles_in_bbox(args.south, args.west, args.north, args.east, z))
        print(f"zoom {z}: {len(tiles)} NAIP tiles")
        jobs.extend((z, x, y) for x, y in tiles)

    total = 0
    failed = 0
    done = 0

    def work(job: tuple[int, int, int]):
        z, x, y = job
        return job, fetch_naip_tile(z, x, y)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(work, job) for job in jobs]
        for fut in as_completed(futures):
            (z, x, y), data = fut.result()
            done += 1
            if data is None:
                failed += 1
            else:
                tms_y = (2**z - 1) - y
                cur.execute(
                    "INSERT OR REPLACE INTO tiles "
                    "(zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
                    (z, x, tms_y, data),
                )
                total += 1
            if done % 100 == 0 or done == len(jobs):
                conn.commit()
                print(f"  progress {done}/{len(jobs)} (ok={total} fail={failed})")

    cur.execute(
        "INSERT OR REPLACE INTO metadata (name, value) VALUES ('maxzoom', ?)",
        (str(args.maxzoom),),
    )
    cur.execute(
        "INSERT OR REPLACE INTO metadata (name, value) VALUES ('name', ?)",
        ("Devils Lake ImageryTopo + NAIP",),
    )
    cur.execute(
        "INSERT OR REPLACE INTO metadata (name, value) VALUES ('description', ?)",
        (
            "USGS ImageryTopo z13-16 + USDA/USGS NAIP z17-18, public domain, offline pack",
        ),
    )
    conn.commit()
    conn.close()
    print(f"\nWrote {total} NAIP tiles ({failed} failed) into {args.output_mbtiles}")


if __name__ == "__main__":
    main()
