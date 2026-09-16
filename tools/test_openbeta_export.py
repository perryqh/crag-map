#!/usr/bin/env python3
"""Unit tests for openbeta_export.py. Pure fixture data, no network calls —
query_openbeta is mocked out entirely, so these run offline and fast.

Usage:
    python3 tools/test_openbeta_export.py
"""
import contextlib
import io
import json
import sqlite3
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import openbeta_export as export


# A tiny 3-level fixture tree: park -> bluff -> formation (with climbs).
# Mirrors the real Devils Lake shape closely enough to exercise depth/is_leaf/
# coordinate-inheritance without hitting the live API.
FIXTURE_TREE = {
    "park-uuid": {
        "uuid": "park-uuid",
        "areaName": "Fixture Park",
        "totalClimbs": 2,
        "metadata": {"lat": 43.0, "lng": -89.0},
        "climbs": [],
        "children": [{"uuid": "bluff-uuid"}],
    },
    "bluff-uuid": {
        "uuid": "bluff-uuid",
        "areaName": "Fixture Bluff",
        "totalClimbs": 2,
        "metadata": {"lat": 43.1, "lng": -89.1},
        "climbs": [],
        "children": [{"uuid": "formation-uuid"}],
    },
    "formation-uuid": {
        "uuid": "formation-uuid",
        "areaName": "Fixture Formation",
        "totalClimbs": 2,
        "metadata": {"lat": 43.2, "lng": -89.2},
        "climbs": [
            {
                "uuid": "climb-clean",
                "name": "Clean Route",
                "grades": {"yds": "5.9"},
                "type": {"trad": True, "sport": False, "bouldering": False, "tr": False, "aid": False, "mixed": False},
                "content": {"description": "A normal, non-infringing description."},
            },
            {
                "uuid": "climb-plagiarized",
                "name": "Disputed Route",
                "grades": {"yds": "5.10a"},
                "type": {"trad": False, "sport": True, "bouldering": False, "tr": False, "aid": False, "mixed": False},
                "content": {"description": "OpenBeta plagiarized this description from Mountain Project."},
            },
        ],
        "children": [],
    },
}


def fake_query_openbeta(uuid, retries=4):
    return FIXTURE_TREE[uuid]


class WalkTests(unittest.TestCase):
    def setUp(self):
        self.rows_area = []
        self.rows_climb = []
        self.max_depth_seen = [0]
        with patch.object(export, "query_openbeta", side_effect=fake_query_openbeta), \
             patch.object(export.time, "sleep", return_value=None), \
             contextlib.redirect_stdout(io.StringIO()):
            export.walk("park-uuid", None, 0, self.rows_area, self.rows_climb, self.max_depth_seen)

    def test_collects_every_area_in_the_tree(self):
        uuids = [row[0] for row in self.rows_area]
        self.assertEqual(uuids, ["park-uuid", "bluff-uuid", "formation-uuid"])

    def test_depth_increments_per_level(self):
        depths = {row[0]: row[3] for row in self.rows_area}
        self.assertEqual(depths, {"park-uuid": 0, "bluff-uuid": 1, "formation-uuid": 2})

    def test_max_depth_tracks_the_deepest_node(self):
        self.assertEqual(self.max_depth_seen[0], 2)

    def test_parent_uuid_chain_is_correct(self):
        parents = {row[0]: row[2] for row in self.rows_area}
        self.assertEqual(parents["park-uuid"], None)
        self.assertEqual(parents["bluff-uuid"], "park-uuid")
        self.assertEqual(parents["formation-uuid"], "bluff-uuid")

    def test_is_leaf_only_true_for_the_area_holding_climbs(self):
        is_leaf = {row[0]: row[4] for row in self.rows_area}
        self.assertEqual(is_leaf, {"park-uuid": 0, "bluff-uuid": 0, "formation-uuid": 1})

    def test_climb_coordinates_are_inherited_from_the_parent_formation(self):
        for row in self.rows_climb:
            # row layout: uuid, area_uuid, name, yds_grade, climb_type, description, lat, lng
            self.assertEqual(row[6], 43.2)
            self.assertEqual(row[7], -89.2)

    def test_plagiarized_description_is_dropped(self):
        by_uuid = {row[0]: row for row in self.rows_climb}
        self.assertIsNone(by_uuid["climb-plagiarized"][5])

    def test_clean_description_is_kept(self):
        by_uuid = {row[0]: row for row in self.rows_climb}
        self.assertEqual(by_uuid["climb-clean"][5], "A normal, non-infringing description.")


class QueryOpenbetaRetryTests(unittest.TestCase):
    """Regression test: a stalled read (not a connection failure) raises a bare
    TimeoutError from resp.read(), not urllib.error.URLError — a real bug that
    let one slow request kill an entire multi-minute recursive walk with no
    retry. urlopen() itself is mocked here since real urllib.error.URLError
    already wraps connection-stage failures; this specifically exercises the
    read-stage TimeoutError path.
    """

    def test_retries_past_a_read_timeout_and_then_succeeds(self):
        good_response = MagicMock()
        good_response.read.return_value = json.dumps(
            {"data": {"area": FIXTURE_TREE["park-uuid"]}}
        ).encode("utf-8")
        good_response.__enter__.return_value = good_response

        with patch.object(export.urllib.request, "urlopen", side_effect=[TimeoutError("stalled read"), good_response]), \
             patch.object(export.time, "sleep", return_value=None):
            result = export.query_openbeta("park-uuid")
        self.assertEqual(result["uuid"], "park-uuid")

    def test_gives_up_after_exhausting_retries(self):
        with patch.object(export.urllib.request, "urlopen", side_effect=TimeoutError("stalled read")), \
             patch.object(export.time, "sleep", return_value=None):
            with self.assertRaises(RuntimeError):
                export.query_openbeta("park-uuid", retries=2)


class ClimbTypeTests(unittest.TestCase):
    def test_picks_the_first_true_flag(self):
        self.assertEqual(export.climb_type({"trad": True, "sport": False}), "trad")
        self.assertEqual(export.climb_type({"trad": False, "sport": True}), "sport")

    def test_tr_maps_to_toprope(self):
        self.assertEqual(export.climb_type({"tr": True}), "toprope")

    def test_no_flags_set_is_unknown(self):
        self.assertEqual(export.climb_type({}), "unknown")


class BuildDbTests(unittest.TestCase):
    def test_schema_matches_room_expectations_and_fts_search_works(self):
        rows_area = [("a1", "Area One", None, 0, 0, 1.0, 2.0, 5)]
        rows_climb = [("c1", "a1", "Vivesection", "5.11a", "trad", None, 1.0, 2.0)]
        with tempfile.NamedTemporaryFile(suffix=".db") as f:
            export.build_db(f.name, rows_area, rows_climb, max_depth=0)
            conn = sqlite3.connect(f.name)
            cur = conn.cursor()

            # Every column Room's entities/DAOs read from must exist with the
            # right NOT NULL-ness — this is what broke the real app on first
            # launch (Room's strict pre-packaged-schema validation).
            cur.execute("PRAGMA table_info(area)")
            area_cols = {row[1]: row[3] for row in cur.fetchall()}  # name -> notnull
            self.assertEqual(area_cols["uuid"], 1)
            self.assertEqual(area_cols["depth"], 1)
            self.assertEqual(area_cols["is_leaf"], 1)
            self.assertEqual(area_cols["lat"], 0)

            cur.execute("PRAGMA foreign_key_list(area)")
            self.assertEqual(cur.fetchall(), [], "no FKs — Room's bare entities don't declare any")

            # The FTS prefix-search query the Android app runs at runtime.
            cur.execute(
                "SELECT climb.name FROM climb_fts JOIN climb ON climb.rowid = climb_fts.rowid "
                "WHERE climb_fts MATCH ?",
                ("vive*",),
            )
            self.assertEqual([row[0] for row in cur.fetchall()], ["Vivesection"])
            conn.close()


if __name__ == "__main__":
    unittest.main()
