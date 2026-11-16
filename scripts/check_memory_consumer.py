"""Check actual runtime dependency resolution from an external standalone consumer POM."""
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = ET.parse(ROOT / "pom.xml").findtext("m:version", namespaces={"m": "http://maven.apache.org/POM/4.0.0"})
MAVEN = ([sys.executable, str(ROOT / "scripts/run_maven_ci.py")]
         if os.environ.get("BATCHRECOVER_TRACE_MAVEN") == "1" else [str(ROOT / "mvnw")])
MODULES = ("batchrecover-core", "batchrecover-storage-memory", "batchrecover-spring-batch",
           "batchrecover-spring-boot-starter", "batchrecover-storage-jdbc")
# CLI-invoked maven-dependency-plugin 3.11.0 pulls maven-reporting-impl 4.0.0 →
# doxia-site-renderer → velocity-tools-generic 3.1 → commons-beanutils 1.9.4.
DEPENDENCY_PLUGIN_PINS = """
    <build><pluginManagement><plugins><plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-dependency-plugin</artifactId>
      <version>3.11.0</version>
      <dependencies>
        <dependency><groupId>commons-beanutils</groupId><artifactId>commons-beanutils</artifactId><version>1.11.0</version></dependency>
        <dependency><groupId>commons-io</groupId><artifactId>commons-io</artifactId><version>2.22.0</version></dependency>
        <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.18.0</version></dependency>
        <dependency><groupId>org.codehaus.plexus</groupId><artifactId>plexus-archiver</artifactId><version>4.16.0</version></dependency>
        <dependency><groupId>org.iq80.snappy</groupId><artifactId>snappy</artifactId><version>0.5</version></dependency>
      </dependencies>
    </plugin></plugins></pluginManagement></build>
"""


def consumer_pom(version, jdbc=False):
    jdbc_dep = ""
    if jdbc:
        jdbc_dep = f"""
    <dependency>
      <groupId>io.github.hamzador</groupId>
      <artifactId>batchrecover-storage-jdbc</artifactId>
      <version>{version}</version>
    </dependency>"""
    return f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.preview</groupId><artifactId>consumer</artifactId><version>1</version>
  <dependencies>
    <dependency>
      <groupId>io.github.hamzador</groupId>
      <artifactId>batchrecover-spring-boot-starter</artifactId>
      <version>{version}</version>
    </dependency>{jdbc_dep}
  </dependencies>
{DEPENDENCY_PLUGIN_PINS}
</project>
"""


def main():
    # Use the installed starter, not the reactor's optional/test dependencies.
    with tempfile.TemporaryDirectory() as temp:
        folder = Path(temp)
        pom = folder / "pom.xml"
        deps = folder / "runtime.txt"
        pom.write_text(consumer_pom(VERSION))
        subprocess.run([*MAVEN, "-B", "-ntp", "-f", str(pom),
                        "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree",
                        "-Dscope=runtime", f"-DoutputFile={deps}"], cwd=ROOT, check=True)
        tree = deps.read_text()
        print(tree)
        for forbidden in ("io.github.hamzador:batchrecover-storage-jdbc:",
                          "org.springframework:spring-web:", "org.springframework:spring-webmvc:",
                          "org.hibernate.orm:hibernate-core:", "org.postgresql:postgresql:",
                          "com.h2database:h2:", "com.mysql:mysql-connector-j:", "com.oracle.database.jdbc:ojdbc",
                          "com.microsoft.sqlserver:mssql-jdbc:", "org.testcontainers:", "org.openjdk.jmh:", "org.pitest:", "com.github.spotbugs:",
                          "org.mockito:", "org.junit.jupiter:"):
            if forbidden in tree:
                raise ValueError(f"Unexpected runtime dependency: {forbidden}")
        for required in ("batchrecover-core:", "batchrecover-storage-memory:", "batchrecover-spring-batch:"):
            if required not in tree:
                raise ValueError(f"Missing expected runtime module: {required}")
        print("Memory consumer: optional JDBC adapter, vendor drivers, web/ORM and test tools absent")
        pom.write_text(consumer_pom(VERSION, jdbc=True))
        deps.unlink()
        subprocess.run([*MAVEN, "-B", "-ntp", "-f", str(pom),
                        "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree",
                        "-Dscope=runtime", f"-DoutputFile={deps}"], cwd=ROOT, check=True)
        tree = deps.read_text()
        if "io.github.hamzador:batchrecover-storage-jdbc:" not in tree:
            raise ValueError("Missing JDBC adapter in JDBC consumer")
        for forbidden in ("org.postgresql:postgresql:", "com.mysql:mysql-connector-j:",
                          "com.oracle.database.jdbc:ojdbc", "com.h2database:h2:",
                          "com.microsoft.sqlserver:mssql-jdbc:", "org.testcontainers:", "org.openjdk.jmh:", "org.pitest:", "com.github.spotbugs:",
                          "org.mockito:", "org.junit.jupiter:"):
            if forbidden in tree:
                raise ValueError(f"Unexpected JDBC runtime dependency: {forbidden}")
        print("JDBC consumer: all vendor drivers and test tools absent")
    for module in MODULES:
        path = ROOT / module / "target" / f"{module}-{VERSION}.jar"
        print(f"Library JAR: {module}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
