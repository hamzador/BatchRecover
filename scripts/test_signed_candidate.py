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
        self.root.joinpath("pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<groupId>io.github.hamzador</groupId><artifactId>batchrecover-parent</artifactId>'
            f'<version>{VERSION}</version><name>BatchRecover</name><description>test</description>'
            '<url>https://example.invalid</url><scm><url>https://example.invalid</url></scm>'
            '<developers><developer><id>owner</id></developer></developers></project>')
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

    def test_allowlist_excludes_test_artifacts(self):
        result = entries(self.root)
        self.assertEqual(21, len(result))
        self.assertFalse(any("-tests.jar" in name for name in result))

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
