"""Tests for Maven build dependency inventory."""
import tempfile
import unittest
from pathlib import Path
from audit_build_repository import inventory


class BuildRepositoryInventoryTest(unittest.TestCase):
    def test_deduplicates_classifier_jars_and_keeps_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("org/example/plugin/1/plugin-1.jar",
                         "org/example/plugin/1/plugin-1-tests.jar",
                         "org/example/plugin/2/plugin-2.jar",
                         "org/example/bom/1/bom-1.pom"):
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.touch()
            self.assertEqual({"components": [
                {"group": "org.example", "name": "plugin", "version": "1"},
                {"group": "org.example", "name": "plugin", "version": "2"},
            ]}, inventory(root))

    def test_rejects_empty_inventory(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "No resolved JARs"):
                inventory(Path(tmp))

    def test_rejects_unexpected_jar_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            folder = root / "org/example/plugin/1"
            folder.mkdir(parents=True)
            (folder / "unexpected.jar").touch()
            with self.assertRaisesRegex(ValueError, "Unexpected Maven JAR filename"):
                inventory(root)
