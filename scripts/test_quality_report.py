"""Tests for SpotBugs finding details in the quality gate."""
import tempfile
import unittest
from pathlib import Path
from quality_report import MODULES, spotbugs

FINDING_XML = """<?xml version="1.0" encoding="UTF-8"?>
<BugCollection>
  <BugInstance type="CT_CONSTRUCTOR_THROW" priority="2">
    <ShortMessage>Be wary of letting constructors throw exceptions.</ShortMessage>
    <SourceLine primary="true" sourcefile="RejectionService.java" start="21"/>
  </BugInstance>
</BugCollection>
"""
CLEAN_XML = """<?xml version="1.0" encoding="UTF-8"?>
<BugCollection/>
"""
ERROR_XML = """<?xml version="1.0" encoding="UTF-8"?>
<BugCollection>
  <Errors>
    <Error>missing class</Error>
  </Errors>
  <MissingClass>example.Missing</MissingClass>
</BugCollection>
"""


class SpotBugsReportTest(unittest.TestCase):
    def test_lists_finding_type_and_source_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write_reports(root, FINDING_XML)
            reports, findings = spotbugs(root)
            self.assertEqual(1, sum(reports["batchrecover-core"].values()))
            self.assertEqual(
                ["CT_CONSTRUCTOR_THROW at RejectionService.java:21 "
                 "(Be wary of letting constructors throw exceptions.)"],
                findings["batchrecover-core"],
            )
            self.assertEqual([], findings["batchrecover-storage-memory"])

    def test_rejects_incomplete_analysis(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write_reports(root, ERROR_XML)
            with self.assertRaisesRegex(ValueError, "Incomplete SpotBugs analysis"):
                spotbugs(root)

    def _write_reports(self, root, core_xml):
        for module in MODULES:
            target = root / module / "target"
            target.mkdir(parents=True)
            xml = core_xml if module == "batchrecover-core" else CLEAN_XML
            (target / "spotbugsXml.xml").write_text(xml)
