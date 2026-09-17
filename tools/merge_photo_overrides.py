#!/usr/bin/env python3
"""Copies field-captured photos from an app export into the bundled
app/src/main/assets/photos/<uuid>/ folders the app reads at runtime via
AssetManager.list() (see AreaSheet.kt's BundledPhotoRow) — a read-only
bundled snapshot, same shape as devils_lake.db/mbtiles, not a live sync (see
the blueprint's "how would we make photos available to view" discussion).

The export is the same field_export.zip the app's Edit Mode "Export" action
produces (MapScreen.kt's exportOverrides): a photo_overrides.json manifest —
[{fileName, capturedAtMillis, targets: [{targetUuid, targetType, targetName},
...]}, ...] — plus the actual photos/<fileName> files. A photo tagged to
multiple climbs (the common case for routes a few meters apart on one wall —
see PhotoTargetEntity) is copied into every one of its targets' folders,
since each target's folder is listed independently at runtime.

Run this after every field trip, alongside merge_pin_overrides.py (same
zip). Numbering picks up after whatever's already in each target's folder,
so re-running for a later trip appends rather than overwrites.

Usage:
    python3 merge_photo_overrides.py <assets_dir> <export.zip>
"""
import json
import os
import sys
import zipfile


def load_manifest(zip_path):
    with zipfile.ZipFile(zip_path) as zf:
        if "photo_overrides.json" not in zf.namelist():
            return []
        with zf.open("photo_overrides.json") as f:
            return json.load(f)


def next_index(target_dir):
    """1 for an empty/missing folder, otherwise one past the highest
    existing <n>.<ext> filename — so re-running for a later trip appends."""
    if not os.path.isdir(target_dir):
        return 1
    existing = [
        int(os.path.splitext(name)[0])
        for name in os.listdir(target_dir)
        if os.path.splitext(name)[0].isdigit()
    ]
    return (max(existing) + 1) if existing else 1


def merge_photos(assets_dir, zip_path):
    """Copies every manifest photo into each of its tagged targets' asset
    folders. Returns (photo_count, copy_count) — copy_count counts one copy
    per target, which exceeds photo_count whenever a photo has >1 target."""
    manifest = load_manifest(zip_path)
    if not manifest:
        return 0, 0

    photo_count = 0
    copy_count = 0
    with zipfile.ZipFile(zip_path) as zf:
        for entry in manifest:
            file_name = entry["fileName"]
            zip_entry_path = f"photos/{file_name}"
            if zip_entry_path not in zf.namelist():
                print(
                    f"WARNING: {zip_entry_path} referenced in manifest but missing from the zip",
                    file=sys.stderr,
                )
                continue
            data = zf.read(zip_entry_path)
            ext = os.path.splitext(file_name)[1] or ".jpg"
            photo_count += 1
            for target in entry.get("targets", []):
                target_dir = os.path.join(assets_dir, "photos", target["targetUuid"])
                os.makedirs(target_dir, exist_ok=True)
                dest = os.path.join(target_dir, f"{next_index(target_dir)}{ext}")
                with open(dest, "wb") as out:
                    out.write(data)
                copy_count += 1
    return photo_count, copy_count


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    assets_dir, zip_path = sys.argv[1], sys.argv[2]

    photo_count, copy_count = merge_photos(assets_dir, zip_path)
    if photo_count == 0:
        print("No photos in this export — nothing to do.")
        return
    print(f"Merged {photo_count} photo(s) into {copy_count} target folder(s) under {assets_dir}/photos/.")


if __name__ == "__main__":
    main()
