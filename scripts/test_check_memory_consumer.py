import unittest

from check_memory_consumer import consumer_pom


class ConsumerPomPinsTest(unittest.TestCase):
    def test_pins_beanutils_on_active_dependency_plugin(self):
        pom = consumer_pom("0.1.0")
        self.assertIn("commons-beanutils</artifactId><version>1.11.0</version>", pom)
        self.assertIn("<plugins><plugin>", pom)
        self.assertNotIn("pluginManagement", pom)
        self.assertNotIn("batchrecover-storage-jdbc", pom)

    def test_jdbc_variant_keeps_the_same_plugin_pins(self):
        pom = consumer_pom("0.1.0", jdbc=True)
        self.assertIn("batchrecover-storage-jdbc", pom)
        self.assertIn("commons-beanutils</artifactId><version>1.11.0</version>", pom)
        self.assertIn("maven-dependency-plugin", pom)
        self.assertNotIn("pluginManagement", pom)
