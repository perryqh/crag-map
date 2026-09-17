import sqlite3
import unittest

from merge_pin_overrides import apply_overrides, find_stale_overrides


def make_conn():
    conn = sqlite3.connect(":memory:")
    conn.execute(
        "CREATE TABLE area (uuid TEXT NOT NULL, name TEXT NOT NULL, lat REAL, lng REAL, PRIMARY KEY(uuid))"
    )
    conn.execute(
        "CREATE TABLE climb (uuid TEXT NOT NULL, name TEXT NOT NULL, lat REAL, lng REAL, PRIMARY KEY(uuid))"
    )
    conn.execute("INSERT INTO area (uuid, name, lat, lng) VALUES ('a1', 'East Rampart', 43.0, -89.0)")
    conn.execute("INSERT INTO climb (uuid, name, lat, lng) VALUES ('c1', \"Hawk's Nest\", 43.0, -89.0)")
    conn.commit()
    return conn


class ApplyOverridesTests(unittest.TestCase):
    def test_area_override_updates_lat_lng(self):
        conn = make_conn()
        area_count, climb_count, missing = apply_overrides(
            conn, [{"targetUuid": "a1", "targetType": "area", "lat": 43.41353, "lng": -89.7158}]
        )
        self.assertEqual(1, area_count)
        self.assertEqual(0, climb_count)
        self.assertEqual([], missing)
        row = conn.execute("SELECT lat, lng FROM area WHERE uuid = 'a1'").fetchone()
        self.assertEqual((43.41353, -89.7158), row)

    def test_climb_override_updates_lat_lng(self):
        conn = make_conn()
        area_count, climb_count, missing = apply_overrides(
            conn, [{"targetUuid": "c1", "targetType": "climb", "lat": 43.5, "lng": -89.5}]
        )
        self.assertEqual(0, area_count)
        self.assertEqual(1, climb_count)
        self.assertEqual([], missing)
        row = conn.execute("SELECT lat, lng FROM climb WHERE uuid = 'c1'").fetchone()
        self.assertEqual((43.5, -89.5), row)

    def test_unknown_uuid_is_reported_missing_and_not_applied_elsewhere(self):
        conn = make_conn()
        area_count, climb_count, missing = apply_overrides(
            conn, [{"targetUuid": "nope", "targetType": "area", "lat": 1.0, "lng": 2.0}]
        )
        self.assertEqual(0, area_count)
        self.assertEqual(["nope"], missing)

    def test_unknown_target_type_raises(self):
        conn = make_conn()
        with self.assertRaises(ValueError):
            apply_overrides(conn, [{"targetUuid": "a1", "targetType": "boulder", "lat": 1.0, "lng": 2.0}])

    def test_multiple_overrides_of_mixed_type(self):
        conn = make_conn()
        area_count, climb_count, missing = apply_overrides(
            conn,
            [
                {"targetUuid": "a1", "targetType": "area", "lat": 1.0, "lng": 2.0},
                {"targetUuid": "c1", "targetType": "climb", "lat": 3.0, "lng": 4.0},
            ],
        )
        self.assertEqual(1, area_count)
        self.assertEqual(1, climb_count)
        self.assertEqual([], missing)


class FindStaleOverridesTests(unittest.TestCase):
    def test_fresh_fix_is_not_stale(self):
        self.assertEqual([], find_stale_overrides([{"targetUuid": "a1", "fixAgeMillis": 500}]))

    def test_old_fix_is_flagged(self):
        overrides = [{"targetUuid": "a1", "fixAgeMillis": 45000}]
        self.assertEqual(overrides, find_stale_overrides(overrides))

    def test_missing_fixAgeMillis_defaults_to_fresh(self):
        self.assertEqual([], find_stale_overrides([{"targetUuid": "a1"}]))

    def test_exactly_at_threshold_is_not_stale(self):
        overrides = [{"targetUuid": "a1", "fixAgeMillis": 20000}]
        self.assertEqual([], find_stale_overrides(overrides))

    def test_custom_threshold(self):
        overrides = [{"targetUuid": "a1", "fixAgeMillis": 6000}]
        self.assertEqual(overrides, find_stale_overrides(overrides, threshold_millis=5000))


if __name__ == "__main__":
    unittest.main()
