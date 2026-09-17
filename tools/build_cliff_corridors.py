#!/usr/bin/env python3
"""Enrich a climb-pack SQLite DB with cliff_corridor LineStrings derived from
sibling area coordinates (no OpenBeta network).

For each parent area that has ≥2 children with non-null lat/lng, order those
children along the dominant axis (lng if lng span ≥ lat span, else lat) and
write a GeoJSON LineString plus the aligned child uuid list.

Usage:
    python3 tools/build_cliff_corridors.py <pack.db>
"""
from __future__ import annotations

import json
import sqlite3
import sys
from datetime import datetime, timezone
from typing import Iterable


CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS cliff_corridor (
  parent_uuid TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  geojson TEXT NOT NULL,
  child_uuids_json TEXT NOT NULL
);
"""


def _order_children(children: list[tuple[str, float, float]]) -> list[tuple[str, float, float]]:
    """Order (uuid, lat, lng) children along the dominant axis of their span."""
    lats = [c[1] for c in children]
    lngs = [c[2] for c in children]
    lat_span = max(lats) - min(lats)
    lng_span = max(lngs) - min(lngs)
    if lng_span >= lat_span:
        return sorted(children, key=lambda c: c[2])  # by lng (E-W)
    return sorted(children, key=lambda c: c[1])  # by lat (N-S)


def build_corridors_for_rows(
    parents: dict[str, str],
    children_by_parent: dict[str, list[tuple[str, float, float]]],
) -> list[tuple[str, str, str, str]]:
    """Pure helper used by tests: return (parent_uuid, name, geojson, child_uuids_json)."""
    rows: list[tuple[str, str, str, str]] = []
    for parent_uuid, kids in children_by_parent.items():
        geo_kids = [(u, lat, lng) for (u, lat, lng) in kids if lat is not None and lng is not None]
        if len(geo_kids) < 2:
            continue
        ordered = _order_children(geo_kids)
        coords = [[lng, lat] for (_u, lat, lng) in ordered]
        child_uuids = [u for (u, _lat, _lng) in ordered]
        geojson = json.dumps(
            {"type": "LineString", "coordinates": coords},
            separators=(",", ":"),
        )
        name = parents.get(parent_uuid, parent_uuid)
        rows.append((parent_uuid, name, geojson, json.dumps(child_uuids)))
    return rows


def build_cliff_corridors(db_path: str) -> int:
    """Open pack DB, (re)build cliff_corridor, update pack_meta. Returns corridor count."""
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.executescript(
        """
        DROP TABLE IF EXISTS cliff_corridor;
        """
        + CREATE_TABLE_SQL
    )

    cur.execute("SELECT uuid, name FROM area")
    parents = {uuid: name for uuid, name in cur.fetchall()}

    cur.execute(
        "SELECT parent_uuid, uuid, lat, lng FROM area "
        "WHERE parent_uuid IS NOT NULL AND lat IS NOT NULL AND lng IS NOT NULL"
    )
    children_by_parent: dict[str, list[tuple[str, float, float]]] = {}
    for parent_uuid, uuid, lat, lng in cur.fetchall():
        children_by_parent.setdefault(parent_uuid, []).append((uuid, lat, lng))

    rows = build_corridors_for_rows(parents, children_by_parent)
    cur.executemany(
        "INSERT INTO cliff_corridor (parent_uuid, name, geojson, child_uuids_json) "
        "VALUES (?,?,?,?)",
        rows,
    )

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    cur.execute(
        "INSERT OR REPLACE INTO pack_meta (key, value) VALUES ('cliff_corridor_count', ?)",
        (str(len(rows)),),
    )
    cur.execute(
        "INSERT OR REPLACE INTO pack_meta (key, value) VALUES ('cliff_corridors_built_at', ?)",
        (now,),
    )
    conn.commit()
    conn.close()
    return len(rows)


def main(argv: Iterable[str] | None = None) -> None:
    args = list(argv if argv is not None else sys.argv[1:])
    if len(args) != 1:
        print(__doc__)
        sys.exit(1)
    count = build_cliff_corridors(args[0])
    print(f"Wrote {count} cliff corridors into {args[0]}")


if __name__ == "__main__":
    main()
