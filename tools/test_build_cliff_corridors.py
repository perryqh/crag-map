#!/usr/bin/env python3
"""Unit tests for build_cliff_corridors.py — synthetic temp DBs, no network.

Usage:
    python3 tools/test_build_cliff_corridors.py
"""
import json
import sqlite3
import tempfile
import unittest

import build_cliff_corridors as bcc


def _make_pack_db(path: str, areas: list[tuple]) -> None:
    """areas: (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs)"""
    conn = sqlite3.connect(path)
    cur = conn.cursor()
    cur.executescript(
        """
        CREATE TABLE area (
          uuid TEXT NOT NULL PRIMARY KEY,
          name TEXT NOT NULL,
          parent_uuid TEXT,
          depth INTEGER NOT NULL,
          is_leaf INTEGER NOT NULL,
          lat REAL,
          lng REAL,
          total_climbs INTEGER NOT NULL
        );
        CREATE TABLE pack_meta (
          key TEXT NOT NULL PRIMARY KEY,
          value TEXT
        );
        """
    )
    cur.executemany(
        "INSERT INTO area (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs) "
        "VALUES (?,?,?,?,?,?,?,?)",
        areas,
    )
    conn.commit()
    conn.close()


class BuildCliffCorridorsTests(unittest.TestCase):
    def test_east_west_parent_orders_by_lng(self):
        # Children span more in lng than lat → order by lng ascending.
        areas = [
            ("p-ew", "EW Wall", None, 0, 0, 43.0, -89.5, 3),
            ("c-west", "West Pin", "p-ew", 1, 1, 43.00, -89.60, 1),
            ("c-mid", "Mid Pin", "p-ew", 1, 1, 43.01, -89.50, 1),
            ("c-east", "East Pin", "p-ew", 1, 1, 43.00, -89.40, 1),
        ]
        with tempfile.NamedTemporaryFile(suffix=".db") as f:
            _make_pack_db(f.name, areas)
            count = bcc.build_cliff_corridors(f.name)
            self.assertEqual(count, 1)
            conn = sqlite3.connect(f.name)
            cur = conn.cursor()
            cur.execute(
                "SELECT parent_uuid, name, geojson, child_uuids_json FROM cliff_corridor"
            )
            row = cur.fetchone()
            self.assertEqual(row[0], "p-ew")
            self.assertEqual(row[1], "EW Wall")
            geo = json.loads(row[2])
            self.assertEqual(geo["type"], "LineString")
            # lng order: west → mid → east
            self.assertEqual(
                geo["coordinates"],
                [[-89.60, 43.00], [-89.50, 43.01], [-89.40, 43.00]],
            )
            self.assertEqual(json.loads(row[3]), ["c-west", "c-mid", "c-east"])
            cur.execute(
                "SELECT value FROM pack_meta WHERE key='cliff_corridor_count'"
            )
            self.assertEqual(cur.fetchone()[0], "1")
            cur.execute(
                "SELECT value FROM pack_meta WHERE key='cliff_corridors_built_at'"
            )
            self.assertIsNotNone(cur.fetchone()[0])
            conn.close()

    def test_north_south_parent_orders_by_lat(self):
        # Children span more in lat than lng → order by lat ascending.
        areas = [
            ("p-ns", "NS Wall", None, 0, 0, 43.5, -89.0, 3),
            ("c-south", "South Pin", "p-ns", 1, 1, 43.40, -89.00, 1),
            ("c-north", "North Pin", "p-ns", 1, 1, 43.60, -89.01, 1),
            ("c-mid", "Mid Pin", "p-ns", 1, 1, 43.50, -89.00, 1),
        ]
        with tempfile.NamedTemporaryFile(suffix=".db") as f:
            _make_pack_db(f.name, areas)
            count = bcc.build_cliff_corridors(f.name)
            self.assertEqual(count, 1)
            conn = sqlite3.connect(f.name)
            cur = conn.cursor()
            cur.execute("SELECT geojson, child_uuids_json FROM cliff_corridor")
            geojson, child_uuids_json = cur.fetchone()
            geo = json.loads(geojson)
            self.assertEqual(
                geo["coordinates"],
                [[-89.00, 43.40], [-89.00, 43.50], [-89.01, 43.60]],
            )
            self.assertEqual(json.loads(child_uuids_json), ["c-south", "c-mid", "c-north"])
            conn.close()

    def test_schema_matches_room_expectations(self):
        # Regression check: parent_uuid was declared "TEXT PRIMARY KEY" without
        # an explicit NOT NULL — SQLite only implies NOT NULL for INTEGER
        # PRIMARY KEY (rowid aliases), not TEXT ones — so the bundled asset's
        # notnull flag was 0 while Room's CliffCorridorEntity (a non-null
        # Kotlin String @PrimaryKey) expects 1. Room's strict pre-packaged
        # schema check rejected the whole database over this exact mismatch,
        # which took the entire map down with it (the same bug class as the
        # area/climb tables hit earlier in this project).
        areas = [
            ("p-ew", "EW Wall", None, 0, 0, 43.0, -89.5, 3),
            ("c-west", "West Pin", "p-ew", 1, 1, 43.00, -89.60, 1),
            ("c-east", "East Pin", "p-ew", 1, 1, 43.00, -89.40, 1),
        ]
        with tempfile.NamedTemporaryFile(suffix=".db") as f:
            _make_pack_db(f.name, areas)
            bcc.build_cliff_corridors(f.name)
            conn = sqlite3.connect(f.name)
            cur = conn.cursor()
            cur.execute("PRAGMA table_info(cliff_corridor)")
            cols = {row[1]: row[3] for row in cur.fetchall()}  # name -> notnull
            self.assertEqual(cols["parent_uuid"], 1)
            self.assertEqual(cols["name"], 1)
            self.assertEqual(cols["geojson"], 1)
            self.assertEqual(cols["child_uuids_json"], 1)
            cur.execute("PRAGMA foreign_key_list(cliff_corridor)")
            self.assertEqual(cur.fetchall(), [], "no FKs — Room's bare entity doesn't declare any")
            conn.close()

    def test_parent_with_one_child_is_skipped(self):
        areas = [
            ("p1", "Lonely Parent", None, 0, 0, 43.0, -89.0, 1),
            ("c1", "Only Child", "p1", 1, 1, 43.0, -89.0, 1),
            ("p2", "Also Lonely", None, 0, 0, 44.0, -88.0, 0),
        ]
        with tempfile.NamedTemporaryFile(suffix=".db") as f:
            _make_pack_db(f.name, areas)
            count = bcc.build_cliff_corridors(f.name)
            self.assertEqual(count, 0)
            conn = sqlite3.connect(f.name)
            cur = conn.cursor()
            cur.execute("SELECT COUNT(*) FROM cliff_corridor")
            self.assertEqual(cur.fetchone()[0], 0)
            cur.execute(
                "SELECT value FROM pack_meta WHERE key='cliff_corridor_count'"
            )
            self.assertEqual(cur.fetchone()[0], "0")
            conn.close()


if __name__ == "__main__":
    unittest.main()
