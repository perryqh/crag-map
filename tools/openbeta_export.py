#!/usr/bin/env python3
"""Recursively walk OpenBeta's GraphQL area tree from a root area UUID and
write a SQLite database matching the Crag Map Android app's Room schema.

Usage:
    python3 openbeta_export.py <root-uuid> <output.db>

Example (Devil's Lake, WI):
    python3 openbeta_export.py bf4481e8-d698-5b5f-a46d-81f807c26d7d devils_lake.db
"""
import json
import sqlite3
import sys
import time
import urllib.request

API_URL = "https://api.openbeta.io/"
THROTTLE_SECONDS = 0.2

QUERY = """
query AreaTree($uuid: ID!) {
  area(uuid: $uuid) {
    uuid
    areaName
    totalClimbs
    metadata { lat lng }
    climbs {
      uuid
      name
      grades { yds }
      type { trad sport bouldering tr aid mixed }
      safety
      content { description }
      metadata { leftRightIndex }
    }
    children { uuid }
  }
}
"""


def query_openbeta(uuid: str, retries: int = 4) -> dict:
    payload = json.dumps({"query": QUERY, "variables": {"uuid": uuid}}).encode("utf-8")
    req = urllib.request.Request(
        API_URL,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "User-Agent": "crag-map-export/0.1 (personal offline-map spike)",
        },
    )
    last_err = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            if "errors" in body:
                raise RuntimeError(f"GraphQL error for {uuid}: {body['errors']}")
            return body["data"]["area"]
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            last_err = e
            wait = 2 ** attempt
            print(f"  (retrying {uuid} after {e}; sleeping {wait}s)")
            time.sleep(wait)
    raise RuntimeError(f"Giving up on {uuid} after {retries} attempts: {last_err}")


def climb_type(t: dict) -> str:
    # OpenBeta returns a struct of booleans; pick the first true flag.
    for key in ("trad", "sport", "bouldering", "tr", "aid", "mixed"):
        if t.get(key):
            return {"tr": "toprope"}.get(key, key)
    return "unknown"


def safety_rating(value):
    # OpenBeta's default/no-data value is the literal string "UNSPECIFIED" —
    # normalize that (and blank) to None so the app only ever shows a badge
    # when there's an actual X/R/PG-type warning to show.
    if not value or value == "UNSPECIFIED":
        return None
    return value


def walk(uuid, parent_uuid, depth, rows_area, rows_climb, max_depth_seen):
    data = query_openbeta(uuid)
    is_leaf = 1 if len(data["climbs"]) > 0 else 0
    lat = data["metadata"]["lat"]
    lng = data["metadata"]["lng"]

    rows_area.append(
        (data["uuid"], data["areaName"], parent_uuid, depth, is_leaf, lat, lng, data["totalClimbs"])
    )

    for c in data["climbs"]:
        description = (c.get("content") or {}).get("description") or None
        # Skip descriptions OpenBeta itself has flagged as plagiarized (copied from
        # Mountain Project without a license) — don't bundle disputed content offline.
        if description and "plagiar" in description.lower():
            description = None
        rows_climb.append(
            (
                c["uuid"],
                data["uuid"],
                c["name"],
                (c.get("grades") or {}).get("yds"),
                climb_type(c["type"]),
                description,
                (c.get("metadata") or {}).get("leftRightIndex"),
                lat,
                lng,
                safety_rating(c.get("safety")),
            )
        )

    max_depth_seen[0] = max(max_depth_seen[0], depth)

    children = data["children"]
    print(f"{'  ' * depth}{data['areaName']} ({data['totalClimbs']} climbs, {len(children)} children)")

    for child in children:
        time.sleep(THROTTLE_SECONDS)
        walk(child["uuid"], data["uuid"], depth + 1, rows_area, rows_climb, max_depth_seen)


def build_db(path, rows_area, rows_climb, max_depth):
    conn = sqlite3.connect(path)
    cur = conn.cursor()
    cur.executescript(
        """
        DROP TABLE IF EXISTS area;
        DROP TABLE IF EXISTS climb;
        DROP TABLE IF EXISTS climb_fts;
        DROP TABLE IF EXISTS pack_meta;

        CREATE TABLE area (
          uuid         TEXT NOT NULL,
          name         TEXT NOT NULL,
          parent_uuid  TEXT,
          depth        INTEGER NOT NULL,
          is_leaf      INTEGER NOT NULL,
          lat          REAL,
          lng          REAL,
          total_climbs INTEGER NOT NULL,
          PRIMARY KEY(uuid)
        );

        CREATE TABLE climb (
          uuid             TEXT NOT NULL,
          area_uuid        TEXT NOT NULL,
          name             TEXT NOT NULL,
          yds_grade        TEXT,
          climb_type       TEXT,
          description      TEXT,
          left_right_index INTEGER,
          lat              REAL,
          lng              REAL,
          safety_rating    TEXT,
          PRIMARY KEY(uuid)
        );

        CREATE VIRTUAL TABLE climb_fts USING fts4(name, yds_grade, content='climb');

        CREATE TABLE pack_meta (
          key   TEXT NOT NULL,
          value TEXT,
          PRIMARY KEY(key)
        );
        """
    )
    cur.executemany(
        "INSERT INTO area (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs) VALUES (?,?,?,?,?,?,?,?)",
        rows_area,
    )
    cur.executemany(
        "INSERT INTO climb (uuid, area_uuid, name, yds_grade, climb_type, description, left_right_index, lat, lng, safety_rating) "
        "VALUES (?,?,?,?,?,?,?,?,?,?)",
        rows_climb,
    )
    cur.executemany(
        "INSERT INTO climb_fts (rowid, name, yds_grade) SELECT rowid, name, yds_grade FROM climb WHERE 0=1",
        [],
    )
    # FTS4 with content='climb' needs an explicit rebuild against the backing table.
    cur.execute("INSERT INTO climb_fts(climb_fts) VALUES ('rebuild')")
    cur.execute("INSERT INTO pack_meta (key, value) VALUES ('max_depth', ?)", (str(max_depth),))
    cur.execute("INSERT INTO pack_meta (key, value) VALUES ('root_uuid', ?)", (rows_area[0][0],))
    conn.commit()
    conn.close()


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    root_uuid, out_path = sys.argv[1], sys.argv[2]

    rows_area, rows_climb = [], []
    max_depth_seen = [0]

    print(f"Walking OpenBeta area tree from {root_uuid} ...")
    walk(root_uuid, None, 0, rows_area, rows_climb, max_depth_seen)

    print(f"\nCollected {len(rows_area)} areas, {len(rows_climb)} climbs, max depth {max_depth_seen[0]}")
    build_db(out_path, rows_area, rows_climb, max_depth_seen[0])
    print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
