#!/usr/bin/env python3
"""Applies field-captured GPS pin overrides onto a built devils_lake.db,
replacing OpenBeta's rough centroids with coordinates surveyed on the ground
(Phase 3: East Rampart baseline pack).

The overrides file is the JSON exported from the app's Edit Mode (see
PinOverride.kt's overridesToJson): a list of
{targetUuid, targetType, targetName, lat, lng, capturedAtMillis, fixAgeMillis,
headingDegrees} objects, where targetType is "area" or "climb" and
fixAgeMillis is how old the GPS fix was at the moment it was captured (the
phone already warns on capture past STALE_FIX_THRESHOLD_MILLIS, but that's
easy to miss mid-survey — this re-surfaces it at the desk too, before it's
baked into the shipped db). headingDegrees (0-360, 0=north, or null if no
sensor reading arrived in time) is the compass heading the phone was facing
when the pin was set — informational only for now, not written into
area/climb, since there's no wall-facing column in that schema yet.

Run this after every openbeta_export.py rebuild — a fresh export has no
knowledge of prior field surveys, so overrides must be reapplied each time.

The app's "Export" action (MapScreen.kt's exportOverrides) now bundles pins
and photos into one field_export.zip, so <overrides> can be that zip
directly — pin_overrides.json is read out of it in memory. A zip with no
pin_overrides.json in it (a trip that only captured photos) is treated as
zero pin overrides, not an error; run tools/merge_photo_overrides.py on the
same zip for the photo side.

Usage:
    python3 merge_pin_overrides.py <db_path> <overrides.json-or-zip>
"""
import json
import sqlite3
import sys
import zipfile

STALE_FIX_THRESHOLD_MILLIS = 20_000


def load_overrides(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as zf:
            if "pin_overrides.json" not in zf.namelist():
                return []
            with zf.open("pin_overrides.json") as f:
                return json.load(f)
    with open(path) as f:
        return json.load(f)


def find_stale_overrides(overrides, threshold_millis=STALE_FIX_THRESHOLD_MILLIS):
    """Overrides whose captured GPS fix was already old when taken."""
    return [o for o in overrides if o.get("fixAgeMillis", 0) > threshold_millis]


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

    stale = find_stale_overrides(overrides)
    if stale:
        print(f"NOTE: {len(stale)} override(s) were captured from a GPS fix that was already stale:")
        for o in stale:
            age_s = o.get("fixAgeMillis", 0) / 1000
            print(f"  {o.get('targetName', o['targetUuid'])} ({o['targetType']}) — fix was {age_s:.0f}s old")
        print("  Consider re-checking these against the field notes before trusting them.\n")

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
