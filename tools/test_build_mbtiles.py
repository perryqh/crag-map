#!/usr/bin/env python3
"""Unit tests for build_mbtiles.py's tile math and MBTiles metadata — no
network calls (fetch_tile is never invoked).

Usage:
    python3 tools/test_build_mbtiles.py
"""
import sqlite3
import tempfile
import unittest

import build_mbtiles as tiles


class Deg2NumTests(unittest.TestCase):
    def test_known_reference_point(self):
        # Zoom 0 is the whole world in one tile.
        self.assertEqual(tiles.deg2num(0.0, 0.0, 0), (0, 0))

    def test_higher_zoom_gives_larger_tile_indices(self):
        x0, y0 = tiles.deg2num(43.41655, -89.72343, 10)
        x1, y1 = tiles.deg2num(43.41655, -89.72343, 14)
        # Each zoom level doubles resolution, so index ranges grow accordingly.
        self.assertLess(x0, x1)
        self.assertLess(y0, y1)

    def test_devils_lake_falls_in_the_expected_tile_at_zoom_13(self):
        # Regression check against the real bounding box used for the spike
        # (43.405,-89.735 to 43.428,-89.700) — the park center should land
        # inside the tile range actually fetched, not off by a row/column.
        x, y = tiles.deg2num(43.41655, -89.72343, 13)
        bbox_tiles = set(tiles.tiles_in_bbox(43.405, -89.735, 43.428, -89.700, 13))
        self.assertIn((x, y), bbox_tiles)


class TilesInBboxTests(unittest.TestCase):
    def test_returns_a_rectangular_grid(self):
        result = list(tiles.tiles_in_bbox(43.405, -89.735, 43.428, -89.700, 14))
        xs = {x for x, _ in result}
        ys = {y for _, y in result}
        self.assertEqual(len(result), len(xs) * len(ys), "should be a full x*y grid, no gaps")

    def test_tiny_bbox_at_low_zoom_still_returns_at_least_one_tile(self):
        result = list(tiles.tiles_in_bbox(43.405, -89.735, 43.428, -89.700, 1))
        self.assertGreaterEqual(len(result), 1)

    def test_tile_count_grows_with_zoom(self):
        low = list(tiles.tiles_in_bbox(43.405, -89.735, 43.428, -89.700, 10))
        high = list(tiles.tiles_in_bbox(43.405, -89.735, 43.428, -89.700, 15))
        self.assertLess(len(low), len(high))


class MbtilesMetadataTests(unittest.TestCase):
    def test_init_mbtiles_writes_expected_metadata_and_schema(self):
        with tempfile.NamedTemporaryFile(suffix=".mbtiles") as f:
            conn = tiles.init_mbtiles(f.name, (43.405, -89.735, 43.428, -89.700), 13, 16)
            conn.close()

            check = sqlite3.connect(f.name)
            cur = check.cursor()
            meta = dict(cur.execute("SELECT name, value FROM metadata").fetchall())
            self.assertEqual(meta["format"], "jpg")  # USGSTopo serves JPEG, not PNG
            self.assertEqual(meta["minzoom"], "13")
            self.assertEqual(meta["maxzoom"], "16")
            self.assertEqual(meta["bounds"], "-89.735,43.405,-89.7,43.428")

            cur.execute("PRAGMA index_list(tiles)")
            index_names = [row[1] for row in cur.fetchall()]
            self.assertIn("tile_index", index_names)
            check.close()


if __name__ == "__main__":
    unittest.main()
