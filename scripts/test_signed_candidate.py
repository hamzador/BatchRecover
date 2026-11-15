"""Check release allowlisting and rejection of inconsistent metadata; no signing keys required."""
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from signed_candidate import entries, MODULES, VERSION


class SignedCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.license = (Path(__file__).resolve().parents[1] / "LICENSE").read_bytes()
        (self.root / "LICENSE").write_bytes(self.license)
        self.root.joinpath("pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<groupId>io.github.hamzador</groupId><artifactId>batchrecover-parent</artifactId>'
            f'<version>{VERSION}</version><name>BatchRecover</name><description>test</description>'
            '<url>https://example.invalid</url><scm><url>https://example.invalid</url></scm>'
            '<developers><developer><id>owner</id></developer></developers>'
            '<licenses><license><name>Apache License, Version 2.0</name>'
            '<url>https://www.apache.org/licenses/LICENSE-2.0</url><distribution>repo</distribution>'
            '</license></licenses></project>')
        for module in MODULES:
            folder = self.root / module
            (folder / "target").mkdir(parents=True)
            (folder / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f'<parent><version>{VERSION}</version></parent><artifactId>{module}</artifactId></project>')
            for suffix, member in ((".jar", "Example.class"), ("-sources.jar", "Example.java"),
                                   ("-javadoc.jar", "index.html"), ("-tests.jar", "Contract.class")):
                with ZipFile(folder / "target" / (module + "-" + VERSION + suffix), "w") as archive:
                    archive.writestr(member, "fixture")
                    if suffix == ".jar":
                        archive.writestr("META-INF/LICENSE", self.license)

    def test_allowlist_excludes_test_artifacts(self):
        result = entries(self.root)
        self.assertEqual(21, len(result))
        self.assertFalse(any("-tests.jar" in name for name in result))

    def test_rejects_incorrect_license_metadata(self):
        pom = self.root / "pom.xml"
        pom.write_text(pom.read_text().replace("Apache License, Version 2.0", "MIT"))
        with self.assertRaisesRegex(ValueError, "POM license metadata"):
            entries(self.root)

    def test_rejects_missing_or_changed_binary_license(self):
        module = MODULES[0]
        path = self.root / module / "target" / (module + "-" + VERSION + ".jar")
        for replacement in (None, b"wrong license"):
            with ZipFile(path, "w") as archive:
                archive.writestr("Example.class", "fixture")
                if replacement is not None:
                    archive.writestr("META-INF/LICENSE", replacement)
            with self.assertRaisesRegex(ValueError, "license in binary JAR"):
                entries(self.root)

    def test_rejects_snapshot_parent(self):
        pom = self.root / "pom.xml"
        pom.write_text(pom.read_text().replace(VERSION, VERSION + "-SNAPSHOT"))
        with self.assertRaisesRegex(ValueError, "non-SNAPSHOT"):
            entries(self.root)

    def test_rejects_corrupt_or_empty_javadoc(self):
        module = MODULES[0]
        with ZipFile(self.root / module / "target" / (module + "-" + VERSION + "-javadoc.jar"), "w"):
            pass
        with self.assertRaisesRegex(ValueError, "Invalid binary"):
            entries(self.root)

    def test_rejects_inconsistent_module_version(self):
        pom = self.root / MODULES[0] / "pom.xml"
        pom.write_text(pom.read_text().replace(VERSION, "0.2.0"))
        with self.assertRaisesRegex(ValueError, "Inconsistent module"):
            entries(self.root)
