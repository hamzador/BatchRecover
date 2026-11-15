import unittest

from run_maven_ci import parse_realm_line


class MavenRealmTraceTest(unittest.TestCase):
    def testTracksPluginAndValidCoordinate(self):
        realm, entry = parse_realm_line(
            "[DEBUG] Populating class realm plugin>org.example:tool:1.0", None)
        self.assertEqual("org.example:tool:1.0", realm)
        self.assertIsNone(entry)
        self.assertEqual((realm, "org.example:dep:jar:2.0"), parse_realm_line(
            "[DEBUG]   Included: org.example:dep:jar:2.0", realm))

    def testRejectsNonCoordinateAndResetsOtherRealms(self):
        realm = "org.example:tool:1.0"
        self.assertEqual((realm, None), parse_realm_line(
            "[DEBUG]   Included: https://example.invalid/private", realm))
        self.assertEqual((None, None), parse_realm_line(
            "[DEBUG] Populating class realm project>org.example:demo:1", realm))
