#!/usr/bin/env python3
"""Applies field-captured GPS pin overrides onto a built devils_lake.db,
replacing OpenBeta's rough centroids with coordinates surveyed on the ground
(Phase 3: East Rampart baseline pack).

The overrides file is the JSON exported from the app's Edit Mode (see
PinOverride.kt's overridesToJson): a list of
{targetUuid, targetType, targetName, lat, lng, capturedAtMillis} objects,
where targetType is "area" or "climb".

Run this after every openbeta_export.py rebuild — a fresh export has no
knowledge of prior field surveys, so overrides must be reapplied each time.

Usage:
    python3 merge_pin_overrides.py <db_path> <overrides.json>
"""
import json
import sqlite3
import sys


def load_overrides(path):
    with open(path) as f:
        return json.load(f)


def apply_overrides(conn, overrides):
    """Returns (area_count, climb_count, missing_uuids)."""
    area_count = 0
    climb_count = 0
    missing = []
    for o in overrides:
        uuid = o["targetUuid"]
        target_type = o["targetType"]
        lat = o["lat"]
        lng = o["lng"]
        if target_type == "area":
            cur = conn.execute("UPDATE area SET lat = ?, lng = ? WHERE uuid = ?", (lat, lng, uuid))
            if cur.rowcount == 0:
                missing.append(uuid)
            else:
                area_count += 1
        elif target_type == "climb":
            cur = conn.execute("UPDATE climb SET lat = ?, lng = ? WHERE uuid = ?", (lat, lng, uuid))
            if cur.rowcount == 0:
                missing.append(uuid)
            else:
                climb_count += 1
        else:
            raise ValueError(f"unknown targetType: {target_type!r}")
    return area_count, climb_count, missing


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    db_path, overrides_path = sys.argv[1], sys.argv[2]

    overrides = load_overrides(overrides_path)
    conn = sqlite3.connect(db_path)
    try:
        area_count, climb_count, missing = apply_overrides(conn, overrides)
        conn.commit()
    finally:
        conn.close()

    print(f"Applied {area_count} area override(s) and {climb_count} climb override(s).")
    if missing:
        print(f"WARNING: {len(missing)} override(s) referenced uuids not found in {db_path}:", file=sys.stderr)
        for uuid in missing:
            print(f"  {uuid}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
