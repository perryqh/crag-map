#!/usr/bin/env python3
"""Seed a fake "Yard Crag [DEV]" climbing hierarchy into a climb pack DB.

Debug builds only: the seeded DB is written under app/src/debug/assets/ so
release keeps the clean Devil's Lake pack from main assets. Used to dry-run
Near Me / Edit Mode pin+photo / export / merge at home before Devil's Lake.

Usage:
    python3 tools/seed_yard_crag.py \\
        [--source app/src/main/assets/devils_lake.db] \\
        [--out app/src/debug/assets/devils_lake.db] \\
        [--lat 43.058510] [--lng -88.157913]
"""

from __future__ import annotations

import argparse
import math
import shutil
import sqlite3
from pathlib import Path

# Fixed UUIDs so re-seeds are stable / idempotent across machines.
YARD_ROOT_UUID = "ee1af972-ac44-5666-a9d9-01bdc0bf92f6"
GARAGE_UUID = "a4dbe62c-0e64-5cf1-9337-05f287d5467f"
DECK_UUID = "4c2055e7-0935-5752-bacf-12d00242b694"
FENCE_UUID = "58b22294-98b8-557a-a10a-e1e8e2899093"

GARAGE_CLIMBS = [
    ("738c51a4-b808-5383-9021-0eb43fc3de1a", "Garbage Day [DEV]", "5.7", "trad", 0),
    ("9632b75f-42d5-55a9-b896-e05c13cd4e0d", "Oil Stain Arete [DEV]", "5.9", "sport", 1),
    ("8c48a0c0-6f24-5158-9a68-9a4f5efb6b82", "Doorjamb Crack [DEV]", "5.8", "trad", 2),
]
DECK_CLIMBS = [
    ("d5c9f39f-bfb8-5e15-8566-d13872047558", "BBQ Slab [DEV]", "5.6", "sport", 0),
    ("1fcc8c6b-3f08-5711-a90c-a071fdb94665", "Railing Traverse [DEV]", "5.10a", "trad", 1),
    ("925c79f3-8346-5687-ae95-2ddb7353f9bd", "Umbrella Overhang [DEV]", "5.9+", "sport", 2),
]
FENCE_CLIMBS = [
    ("03352645-fb31-5de2-8aaa-01365d0b7380", "Postman's Crack [DEV]", "5.5", "trad", 0),
    ("8b11584a-0a32-5f6f-ae31-68bf3ebe66fa", "Neighbor's Glance [DEV]", "5.8", "sport", 1),
]

ALL_AREA_UUIDS = (YARD_ROOT_UUID, GARAGE_UUID, DECK_UUID, FENCE_UUID)
ALL_CLIMB_UUIDS = tuple(
    c[0] for c in (GARAGE_CLIMBS + DECK_CLIMBS + FENCE_CLIMBS)
)

DEFAULT_SOURCE = Path("app/src/main/assets/devils_lake.db")
DEFAULT_OUT = Path("app/src/debug/assets/devils_lake.db")
DEFAULT_LAT = 43.058510
DEFAULT_LNG = -88.157913

DEV_DESC = (
    "DEV dry-run route for home Yard Crag testing "
    "(Near Me / Edit Mode pin+photo / export / merge)."
)


def offset_lat_lng(lat: float, lng: float, north_m: float, east_m: float) -> tuple[float, float]:
    """Convert meter offsets to degrees at the given latitude."""
    dlat = north_m / 111_320.0
    dlng = east_m / (111_320.0 * math.cos(math.radians(lat)))
    return lat + dlat, lng + dlng


def delete_yard_seed(conn: sqlite3.Connection) -> None:
    """Remove any prior Yard Crag rows (by fixed UUID or under the yard root)."""
    # Climbs attached to yard areas or carrying fixed climb UUIDs.
    placeholders = ",".join("?" * len(ALL_CLIMB_UUIDS))
    area_placeholders = ",".join("?" * len(ALL_AREA_UUIDS))
    conn.execute(
        f"DELETE FROM climb WHERE uuid IN ({placeholders}) OR area_uuid IN ({area_placeholders})",
        ALL_CLIMB_UUIDS + ALL_AREA_UUIDS,
    )
    # Also drop climbs under any area whose parent is the yard root (defensive).
    conn.execute(
        "DELETE FROM climb WHERE area_uuid IN "
        "(SELECT uuid FROM area WHERE parent_uuid = ? OR uuid = ?)",
        (YARD_ROOT_UUID, YARD_ROOT_UUID),
    )
    conn.execute(
        f"DELETE FROM area WHERE uuid IN ({area_placeholders}) OR parent_uuid = ?",
        ALL_AREA_UUIDS + (YARD_ROOT_UUID,),
    )


def insert_yard_seed(conn: sqlite3.Connection, lat: float, lng: float) -> dict:
    garage_lat, garage_lng = offset_lat_lng(lat, lng, north_m=20.0, east_m=0.0)
    deck_lat, deck_lng = offset_lat_lng(lat, lng, north_m=0.0, east_m=15.0)
    # ~20m south-west: south=-20, west=-14 ≈ 24m hypot; keep ~20m SW → -14.14 N/E
    fence_lat, fence_lng = offset_lat_lng(lat, lng, north_m=-14.14, east_m=-14.14)

    leaves = [
        (GARAGE_UUID, "Garage Wall [DEV]", garage_lat, garage_lng, GARAGE_CLIMBS),
        (DECK_UUID, "Deck Buttress [DEV]", deck_lat, deck_lng, DECK_CLIMBS),
        (FENCE_UUID, "Fence Line [DEV]", fence_lat, fence_lng, FENCE_CLIMBS),
    ]

    total = sum(len(climbs) for *_, climbs in leaves)
    conn.execute(
        "INSERT INTO area (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs) "
        "VALUES (?, ?, NULL, 0, 0, ?, ?, ?)",
        (YARD_ROOT_UUID, "Yard Crag [DEV]", lat, lng, total),
    )

    climb_count = 0
    for area_uuid, name, a_lat, a_lng, climbs in leaves:
        conn.execute(
            "INSERT INTO area (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs) "
            "VALUES (?, ?, ?, 1, 1, ?, ?, ?)",
            (area_uuid, name, YARD_ROOT_UUID, a_lat, a_lng, len(climbs)),
        )
        for climb_uuid, climb_name, grade, climb_type, lr_index in climbs:
            conn.execute(
                "INSERT INTO climb (uuid, area_uuid, name, yds_grade, climb_type, "
                "description, left_right_index, lat, lng, safety_rating) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                (
                    climb_uuid,
                    area_uuid,
                    climb_name,
                    grade,
                    climb_type,
                    DEV_DESC,
                    lr_index,
                    a_lat,
                    a_lng,
                ),
            )
            climb_count += 1

    # Rebuild FTS4 content table after inserts.
    conn.execute("INSERT INTO climb_fts(climb_fts) VALUES('rebuild')")

    for key, value in (
        ("yard_crag_seeded", "1"),
        ("yard_crag_lat", str(lat)),
        ("yard_crag_lng", str(lng)),
    ):
        conn.execute(
            "INSERT INTO pack_meta (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    return {
        "areas": 1 + len(leaves),
        "climbs": climb_count,
        "lat": lat,
        "lng": lng,
    }


def seed(source: Path, out: Path, lat: float, lng: float) -> dict:
    if not source.is_file():
        raise FileNotFoundError(f"source db not found: {source}")
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, out)

    conn = sqlite3.connect(out)
    try:
        delete_yard_seed(conn)
        stats = insert_yard_seed(conn, lat, lng)
        conn.commit()
    finally:
        conn.close()
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--lat", type=float, default=DEFAULT_LAT)
    parser.add_argument("--lng", type=float, default=DEFAULT_LNG)
    args = parser.parse_args(argv)

    stats = seed(args.source, args.out, args.lat, args.lng)
    print(
        f"Seeded Yard Crag into {args.out}: "
        f"{stats['areas']} areas, {stats['climbs']} climbs "
        f"@ {stats['lat']},{stats['lng']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
