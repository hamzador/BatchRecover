"""Check actual runtime dependency resolution from an external standalone consumer POM."""
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0-SNAPSHOT"
MODULES = ("batchrecover-core", "batchrecover-storage-memory", "batchrecover-spring-batch",
           "batchrecover-spring-boot-starter", "batchrecover-storage-jdbc")


def main():
    # Use the installed starter, not the reactor's optional/test dependencies.
    with tempfile.TemporaryDirectory() as temp:
        folder = Path(temp)
        pom = folder / "pom.xml"
        deps = folder / "runtime.txt"
        pom.write_text(f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.preview</groupId><artifactId>consumer</artifactId><version>1</version>
  <dependencies><dependency>
    <groupId>io.github.hamzador</groupId><artifactId>batchrecover-spring-boot-starter</artifactId>
    <version>{VERSION}</version>
  </dependency></dependencies>
</project>""")
        subprocess.run([str(ROOT / "mvnw"), "-B", "-ntp", "-f", str(pom),
                        "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree",
                        "-Dscope=runtime", f"-DoutputFile={deps}"], cwd=ROOT, check=True)
        tree = deps.read_text()
        print(tree)
        for forbidden in ("io.github.hamzador:batchrecover-storage-jdbc:",
                          "org.springframework:spring-web:", "org.springframework:spring-webmvc:",
                          "org.hibernate.orm:hibernate-core:", "org.postgresql:postgresql:",
                          "com.mysql:mysql-connector-j:", "com.oracle.database.jdbc:ojdbc",
                          "com.microsoft.sqlserver:mssql-jdbc:", "org.testcontainers:",
                          "org.mockito:", "org.junit.jupiter:"):
            if forbidden in tree:
                raise ValueError(f"Unexpected runtime dependency: {forbidden}")
        for required in ("batchrecover-core:", "batchrecover-storage-memory:", "batchrecover-spring-batch:"):
            if required not in tree:
                raise ValueError(f"Missing expected runtime module: {required}")
        print("Memory consumer: optional JDBC adapter, vendor drivers, web/ORM and test tools absent")
    for module in MODULES:
        path = ROOT / module / "target" / f"{module}-{VERSION}.jar"
        print(f"Library JAR: {module}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
