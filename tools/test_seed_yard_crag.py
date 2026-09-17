#!/usr/bin/env python3
"""Tests for tools/seed_yard_crag.py."""

from __future__ import annotations

import math
import shutil
import sqlite3
import tempfile
import unittest
from pathlib import Path

from seed_yard_crag import (
    ALL_AREA_UUIDS,
    ALL_CLIMB_UUIDS,
    DECK_UUID,
    FENCE_UUID,
    GARAGE_UUID,
    YARD_ROOT_UUID,
    offset_lat_lng,
    seed,
)

REPO_ROOT = Path(__file__).resolve().parents[1]
REAL_DB = REPO_ROOT / "app" / "src" / "main" / "assets" / "devils_lake.db"
CENTER_LAT = 43.058510
CENTER_LNG = -88.157913


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6_371_000.0
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = (
        math.sin(dlat / 2) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(dlng / 2) ** 2
    )
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def make_tiny_fixture(path: Path) -> None:
    """Minimal climb-pack schema matching the real asset tables we touch."""
    conn = sqlite3.connect(path)
    try:
        conn.executescript(
            """
            CREATE TABLE area (
              uuid TEXT NOT NULL,
              name TEXT NOT NULL,
              parent_uuid TEXT,
              depth INTEGER NOT NULL,
              is_leaf INTEGER NOT NULL,
              lat REAL,
              lng REAL,
              total_climbs INTEGER NOT NULL,
              PRIMARY KEY(uuid)
            );
            CREATE TABLE climb (
              uuid TEXT NOT NULL,
              area_uuid TEXT NOT NULL,
              name TEXT NOT NULL,
              yds_grade TEXT,
              climb_type TEXT,
              description TEXT,
              left_right_index INTEGER,
              lat REAL,
              lng REAL,
              safety_rating TEXT,
              PRIMARY KEY(uuid)
            );
            CREATE VIRTUAL TABLE climb_fts USING fts4(name, yds_grade, content='climb');
            CREATE TABLE pack_meta (
              key TEXT NOT NULL,
              value TEXT,
              PRIMARY KEY(key)
            );
            INSERT INTO area VALUES (
              'root-dl', 'Devil''s Lake', NULL, 0, 0, 43.4, -89.7, 0
            );
            INSERT INTO pack_meta VALUES ('max_depth', '1');
            """
        )
        conn.commit()
    finally:
        conn.close()


class OffsetTests(unittest.TestCase):
    def test_north_offset_increases_lat(self):
        lat, lng = offset_lat_lng(CENTER_LAT, CENTER_LNG, north_m=20.0, east_m=0.0)
        self.assertGreater(lat, CENTER_LAT)
        self.assertAlmostEqual(lng, CENTER_LNG, places=8)
        self.assertLess(haversine_m(CENTER_LAT, CENTER_LNG, lat, lng), 25.0)


class SeedYardCragTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.source = Path(self.tmp.name) / "source.db"
        self.out = Path(self.tmp.name) / "out" / "devils_lake.db"
        make_tiny_fixture(self.source)

    def tearDown(self):
        self.tmp.cleanup()

    def test_seeds_one_park_three_leaves_and_climbs(self):
        stats = seed(self.source, self.out, CENTER_LAT, CENTER_LNG)
        self.assertEqual(4, stats["areas"])
        self.assertEqual(8, stats["climbs"])  # 3+3+2

        conn = sqlite3.connect(self.out)
        try:
            roots = conn.execute(
                "SELECT uuid, name, depth, is_leaf, total_climbs FROM area WHERE depth = 0"
            ).fetchall()
            self.assertEqual(2, len(roots))  # Devil's Lake + Yard Crag
            yard = conn.execute(
                "SELECT name, is_leaf, total_climbs, lat, lng FROM area WHERE uuid = ?",
                (YARD_ROOT_UUID,),
            ).fetchone()
            self.assertEqual("Yard Crag [DEV]", yard[0])
            self.assertEqual(0, yard[1])
            self.assertEqual(8, yard[2])
            self.assertAlmostEqual(CENTER_LAT, yard[3])
            self.assertAlmostEqual(CENTER_LNG, yard[4])

            leaves = conn.execute(
                "SELECT uuid, name, is_leaf, parent_uuid FROM area WHERE parent_uuid = ?",
                (YARD_ROOT_UUID,),
            ).fetchall()
            self.assertEqual(3, len(leaves))
            self.assertTrue(all(row[2] == 1 for row in leaves))
            self.assertTrue(all("[DEV]" in row[1] for row in leaves))

            climbs = conn.execute(
                "SELECT name, description, area_uuid FROM climb WHERE area_uuid IN (?,?,?)",
                (GARAGE_UUID, DECK_UUID, FENCE_UUID),
            ).fetchall()
            self.assertEqual(8, len(climbs))
            self.assertTrue(all("[DEV]" in c[0] for c in climbs))
            self.assertTrue(all(c[1] and "DEV dry-run" in c[1] for c in climbs))

            meta = dict(conn.execute("SELECT key, value FROM pack_meta").fetchall())
            self.assertEqual("1", meta["yard_crag_seeded"])
            self.assertEqual(str(CENTER_LAT), meta["yard_crag_lat"])
            self.assertEqual(str(CENTER_LNG), meta["yard_crag_lng"])

            # FTS rebuild picked up the new climb names.
            hits = conn.execute(
                "SELECT name FROM climb_fts WHERE climb_fts MATCH 'Yard OR Garage OR Deck OR Fence OR Garbage'"
            ).fetchall()
            # MATCH may be empty depending on tokenizer for [DEV]; query a known name.
            hits = conn.execute(
                "SELECT c.name FROM climb_fts f JOIN climb c ON c.rowid = f.rowid "
                "WHERE climb_fts MATCH ?",
                ("Garbage*",),
            ).fetchall()
            self.assertTrue(any("Garbage Day" in h[0] for h in hits))
        finally:
            conn.close()

    def test_leaf_distances_under_150m_from_center(self):
        seed(self.source, self.out, CENTER_LAT, CENTER_LNG)
        conn = sqlite3.connect(self.out)
        try:
            rows = conn.execute(
                "SELECT name, lat, lng FROM area WHERE uuid IN (?,?,?)",
                (GARAGE_UUID, DECK_UUID, FENCE_UUID),
            ).fetchall()
            self.assertEqual(3, len(rows))
            for name, lat, lng in rows:
                dist = haversine_m(CENTER_LAT, CENTER_LNG, lat, lng)
                self.assertLess(
                    dist, 150.0, f"{name} is {dist:.1f}m from center (Near Me radius)"
                )
                self.assertGreater(dist, 5.0, f"{name} should be offset from center")
        finally:
            conn.close()

    def test_names_contain_dev_marker(self):
        seed(self.source, self.out, CENTER_LAT, CENTER_LNG)
        conn = sqlite3.connect(self.out)
        try:
            area_names = [
                r[0]
                for r in conn.execute(
                    f"SELECT name FROM area WHERE uuid IN ({','.join('?' * len(ALL_AREA_UUIDS))})",
                    ALL_AREA_UUIDS,
                )
            ]
            climb_names = [
                r[0]
                for r in conn.execute(
                    f"SELECT name FROM climb WHERE uuid IN ({','.join('?' * len(ALL_CLIMB_UUIDS))})",
                    ALL_CLIMB_UUIDS,
                )
            ]
            self.assertTrue(area_names and all("[DEV]" in n for n in area_names))
            self.assertTrue(climb_names and all("[DEV]" in n for n in climb_names))
        finally:
            conn.close()

    def test_idempotent_rerun_does_not_duplicate(self):
        seed(self.source, self.out, CENTER_LAT, CENTER_LNG)
        # Re-run against an already-seeded source; delete+reinsert must not duplicate.
        seeded_source = Path(self.tmp.name) / "seeded_source.db"
        shutil.copy2(self.out, seeded_source)
        seed(seeded_source, self.out, CENTER_LAT, CENTER_LNG)

        conn = sqlite3.connect(self.out)
        try:
            area_n = conn.execute(
                "SELECT COUNT(*) FROM area WHERE uuid = ?", (YARD_ROOT_UUID,)
            ).fetchone()[0]
            climb_n = conn.execute(
                f"SELECT COUNT(*) FROM climb WHERE uuid IN ({','.join('?' * len(ALL_CLIMB_UUIDS))})",
                ALL_CLIMB_UUIDS,
            ).fetchone()[0]
            leaf_n = conn.execute(
                "SELECT COUNT(*) FROM area WHERE parent_uuid = ?", (YARD_ROOT_UUID,)
            ).fetchone()[0]
            self.assertEqual(1, area_n)
            self.assertEqual(8, climb_n)
            self.assertEqual(3, leaf_n)
        finally:
            conn.close()

    @unittest.skipUnless(REAL_DB.is_file(), "real pack db missing")
    def test_against_real_devils_lake_db(self):
        stats = seed(REAL_DB, self.out, CENTER_LAT, CENTER_LNG)
        self.assertEqual(4, stats["areas"])
        self.assertEqual(8, stats["climbs"])
        conn = sqlite3.connect(self.out)
        try:
            # Original park untouched.
            dl = conn.execute(
                "SELECT COUNT(*) FROM area WHERE name = ?", ("Devil's Lake",)
            ).fetchone()[0]
            self.assertEqual(1, dl)
            yard_climbs = conn.execute(
                "SELECT COUNT(*) FROM climb WHERE name LIKE ?", ("%[DEV]%",)
            ).fetchone()[0]
            self.assertEqual(8, yard_climbs)
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
