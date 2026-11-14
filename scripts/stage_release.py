"""Build an unsigned local preview ZIP; never contact a Maven repository or publish."""
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
GROUP = "io/github/hamzador"
MODULES = ("batchrecover-core", "batchrecover-storage-memory", "batchrecover-storage-jdbc", "batchrecover-spring-batch",
           "batchrecover-spring-boot-starter")


def main():
    version = ET.parse(ROOT / "pom.xml").findtext("m:version", namespaces=NS)
    if version != "0.1.0-SNAPSHOT":
        raise ValueError("Local preview is deliberately limited to 0.1.0-SNAPSHOT")
    output = ROOT / "target/batchrecover-local-preview.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    files = []
    parent = "batchrecover-parent"
    files.append((ROOT / "pom.xml", f"{GROUP}/{parent}/{version}/{parent}-{version}.pom"))
    for module in MODULES:
        prefix = f"{module}-{version}"
        folder = ROOT / module
        base = f"{GROUP}/{module}/{version}/{prefix}"
        files.append((folder / "pom.xml", base + ".pom"))
        for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
            path = folder / "target" / (prefix + suffix)
            # A valid nonempty archive is necessary, not sufficient for Central validation.
            with ZipFile(path) as jar:
                members = jar.namelist()
                expected = {".jar": ".class", "-sources.jar": ".java", "-javadoc.jar": ".html"}[suffix]
                if not any(name.endswith(expected) for name in members) or jar.testzip():
                    raise ValueError(f"Invalid or empty {path}")
            files.append((path, base + suffix))
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for path, entry in files:
            data = path.read_bytes()
            archive.writestr(entry, data)
            archive.writestr(entry + ".sha256", hashlib.sha256(data).hexdigest() + "\n")
    print(f"Local preview: {len(files)} unsigned artifacts/POMs, SHA-256 checksums, {output.name}")
    print("Not a Central upload bundle: SNAPSHOT version, no signatures or approved license.")


if __name__ == "__main__":
    main()
