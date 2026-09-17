import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

from merge_photo_overrides import load_manifest, merge_photos, next_index


def make_export_zip(zip_path, manifest, photo_bytes_by_name):
    with zipfile.ZipFile(zip_path, "w") as zf:
        zf.writestr("photo_overrides.json", json.dumps(manifest))
        for name, data in photo_bytes_by_name.items():
            zf.writestr(f"photos/{name}", data)


class LoadManifestTests(unittest.TestCase):
    def test_missing_manifest_returns_empty_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "export.zip"
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("pin_overrides.json", "[]")
            self.assertEqual([], load_manifest(str(zip_path)))

    def test_reads_manifest_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "export.zip"
            manifest = [{"fileName": "a1_1.jpg", "capturedAtMillis": 1, "targets": []}]
            make_export_zip(zip_path, manifest, {})
            self.assertEqual(manifest, load_manifest(str(zip_path)))


class NextIndexTests(unittest.TestCase):
    def test_missing_dir_starts_at_one(self):
        self.assertEqual(1, next_index("/nonexistent/path"))

    def test_empty_dir_starts_at_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(1, next_index(tmp))

    def test_picks_up_after_highest_existing_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "1.jpg").write_bytes(b"x")
            Path(tmp, "2.jpg").write_bytes(b"x")
            self.assertEqual(3, next_index(tmp))

    def test_ignores_non_numeric_filenames(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "1.jpg").write_bytes(b"x")
            Path(tmp, "notes.txt").write_bytes(b"x")
            self.assertEqual(2, next_index(tmp))


class MergePhotosTests(unittest.TestCase):
    def test_single_photo_single_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "export.zip"
            assets_dir = Path(tmp) / "assets"
            manifest = [
                {
                    "fileName": "c1_100.jpg",
                    "capturedAtMillis": 100,
                    "targets": [{"targetUuid": "c1", "targetType": "climb", "targetName": "The Beast"}],
                }
            ]
            make_export_zip(zip_path, manifest, {"c1_100.jpg": b"fake-jpeg-bytes"})

            photo_count, copy_count = merge_photos(str(assets_dir), str(zip_path))

            self.assertEqual(1, photo_count)
            self.assertEqual(1, copy_count)
            dest = assets_dir / "photos" / "c1" / "1.jpg"
            self.assertTrue(dest.exists())
            self.assertEqual(b"fake-jpeg-bytes", dest.read_bytes())

    def test_photo_tagged_to_multiple_climbs_is_copied_into_each_folder(self):
        # The motivating case: a few climbs a few meters apart on one wall,
        # tagged from a single capture (see AreaSheet's photo tag sheet).
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "export.zip"
            assets_dir = Path(tmp) / "assets"
            manifest = [
                {
                    "fileName": "wall_1.jpg",
                    "capturedAtMillis": 1,
                    "targets": [
                        {"targetUuid": "c1", "targetType": "climb", "targetName": "Climb One"},
                        {"targetUuid": "c2", "targetType": "climb", "targetName": "Climb Two"},
                    ],
                }
            ]
            make_export_zip(zip_path, manifest, {"wall_1.jpg": b"shared-photo"})

            photo_count, copy_count = merge_photos(str(assets_dir), str(zip_path))

            self.assertEqual(1, photo_count)
            self.assertEqual(2, copy_count)
            self.assertEqual(b"shared-photo", (assets_dir / "photos" / "c1" / "1.jpg").read_bytes())
            self.assertEqual(b"shared-photo", (assets_dir / "photos" / "c2" / "1.jpg").read_bytes())

    def test_rerunning_for_a_later_trip_appends_not_overwrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets_dir = Path(tmp) / "assets"
            first_targets = [{"targetUuid": "c1", "targetType": "climb", "targetName": "The Beast"}]

            zip1 = Path(tmp) / "trip1.zip"
            make_export_zip(
                zip1,
                [{"fileName": "c1_1.jpg", "capturedAtMillis": 1, "targets": first_targets}],
                {"c1_1.jpg": b"trip-one"},
            )
            merge_photos(str(assets_dir), str(zip1))

            zip2 = Path(tmp) / "trip2.zip"
            make_export_zip(
                zip2,
                [{"fileName": "c1_2.jpg", "capturedAtMillis": 2, "targets": first_targets}],
                {"c1_2.jpg": b"trip-two"},
            )
            merge_photos(str(assets_dir), str(zip2))

            self.assertEqual(b"trip-one", (assets_dir / "photos" / "c1" / "1.jpg").read_bytes())
            self.assertEqual(b"trip-two", (assets_dir / "photos" / "c1" / "2.jpg").read_bytes())

    def test_empty_manifest_returns_zero_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "export.zip"
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("pin_overrides.json", "[]")
            self.assertEqual((0, 0), merge_photos(str(Path(tmp) / "assets"), str(zip_path)))

    def test_manifest_entry_with_missing_zip_file_is_skipped_not_fatal(self):
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "export.zip"
            assets_dir = Path(tmp) / "assets"
            manifest = [
                {
                    "fileName": "missing.jpg",
                    "capturedAtMillis": 1,
                    "targets": [{"targetUuid": "c1", "targetType": "climb", "targetName": "X"}],
                }
            ]
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("photo_overrides.json", json.dumps(manifest))
            photo_count, copy_count = merge_photos(str(assets_dir), str(zip_path))
            self.assertEqual(0, photo_count)
            self.assertEqual(0, copy_count)


if __name__ == "__main__":
    unittest.main()
