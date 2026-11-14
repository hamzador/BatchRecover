import unittest
from audit_dependencies import components, audit


class AuditTests(unittest.TestCase):
    def testDeduplicatesMavenCoordinatesAndExcludesReactor(self):
        lib = {"group": "org.example", "name": "dep", "version": "1"}
        own = {"group": "io.github.hamzador", "name": "batchrecover-core", "version": "0.1.0-SNAPSHOT"}
        self.assertEqual([("org.example:dep", "1")], components({"components": [lib, lib, own]}))

    def testFollowsPaginationAndDeduplicatesAdvisories(self):
        def query(queries):
            if queries[0].get("page_token"):
                return [{"vulns": [{"id": "TEST-1"}, {"id": "TEST-2"}]}]
            return [{"vulns": [{"id": "TEST-1"}], "next_page_token": "next"}]
        result = audit([("org.example:dep", "1")], query)
        self.assertEqual(["TEST-1", "TEST-2"], [f["advisory"] for f in result])

    def testRejectsIncompleteResponse(self):
        with self.assertRaises(ValueError):
            audit([("org.example:dep", "1")], lambda queries: [])


if __name__ == "__main__":
    unittest.main()
