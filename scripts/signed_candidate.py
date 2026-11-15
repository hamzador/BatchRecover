"""Local, allowlisted Maven-layout rehearsal; never contacts a publishing service."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
GROUP = "io/github/hamzador"
VERSION = "0.1.0"
MODULES = ("batchrecover-core", "batchrecover-storage-memory", "batchrecover-storage-jdbc",
           "batchrecover-spring-batch", "batchrecover-spring-boot-starter")
SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
HASHES = ("md5", "sha1", "sha256", "sha512")
FINGERPRINT = re.compile(r"[0-9A-F]{40,64}")


def entries(root):
    """Use only six POMs and fifteen distribution JARs, never the contract test JAR."""
    parent = ET.parse(root / "pom.xml").getroot()
    if parent.findtext("m:version", namespaces=NS) != VERSION:
        raise ValueError("Build a separate non-SNAPSHOT rehearsal checkout first")
    if parent.findtext("m:groupId", namespaces=NS) != GROUP.replace("/", "."):
        raise ValueError("Unexpected namespace")
    if parent.findtext("m:artifactId", namespaces=NS) != "batchrecover-parent":
        raise ValueError("Unexpected parent artifact")
    for name in ("name", "description", "url", "scm/url", "developers/developer/id"):
        if not parent.findtext("/".join("m:" + part for part in name.split("/")), namespaces=NS):
            raise ValueError("Missing parent metadata: " + name)
    result = {}
    for module in ("batchrecover-parent", *MODULES):
        folder = root if module == "batchrecover-parent" else root / module
        pom = ET.parse(folder / "pom.xml").getroot()
        if pom.findtext("m:artifactId", namespaces=NS) != module:
            raise ValueError("Unexpected module artifact")
        if module != "batchrecover-parent":
            if pom.findtext("m:parent/m:version", namespaces=NS) != VERSION:
                raise ValueError("Inconsistent module version")
        prefix = module + "-" + VERSION
        base = f"{GROUP}/{module}/{VERSION}/{prefix}"
        for suffix in ((".pom",) if module == "batchrecover-parent" else SUFFIXES):
            path = folder / "pom.xml" if suffix == ".pom" else folder / "target" / (prefix + suffix)
            if suffix == ".pom":
                if b"0.1.0-SNAPSHOT" in path.read_bytes():
                    raise ValueError("Snapshot dependency in release POM")
            else:
                with ZipFile(path) as jar:
                    expected = {".jar": ".class", "-sources.jar": ".java", "-javadoc.jar": ".html"}[suffix]
                    if jar.testzip() or not any(n.endswith(expected) for n in jar.namelist()):
                        raise ValueError("Invalid binary/source/Javadoc archive: " + str(path))
            result[base + suffix] = path
    return result


def gpg(home, *args):
    return subprocess.run(["gpg", "--batch", "--homedir", str(home), *args],
                          capture_output=True, check=True, text=True).stdout


def sign_bundle(root, output, home, fingerprint):
    if not FINGERPRINT.fullmatch(fingerprint):
        raise ValueError("Select an explicit uppercase full signing fingerprint")
    files = entries(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Never overwrite a previous rehearsal; no partially signed ZIP on failure.
    if output.exists():
        raise FileExistsError(output)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        folder = Path(tmp)
        candidate = folder / "candidate.zip"
        key = gpg(home, "--armor", "--export", fingerprint)
        if not key.strip():
            raise ValueError("Missing signing public key")
        verify_home = folder / "verify"
        verify_home.mkdir(mode=0o700)
        pub = folder / "public.asc"
        pub.write_text(key)
        gpg(verify_home, "--import", str(pub))
        with ZipFile(candidate, "w", ZIP_DEFLATED) as archive:
            for index, (entry, source) in enumerate(files.items()):
                # Freeze bytes before signing so checksums and signature address identical content.
                artifact = folder / f"artifact-{index}"
                artifact.write_bytes(source.read_bytes())
                signature = folder / f"artifact-{index}.asc"
                gpg(home, "--local-user", fingerprint + "!", "--armor", "--detach-sign",
                    "--output", str(signature), str(artifact))
                status = gpg(verify_home, "--status-fd", "1", "--verify", str(signature), str(artifact))
                if not any(line.startswith("[GNUPG:] VALIDSIG " + fingerprint + " ")
                           for line in status.splitlines()):
                    raise ValueError("Signature fingerprint mismatch: " + entry)
                data = artifact.read_bytes()
                archive.writestr(entry, data)
                archive.writestr(entry + ".asc", signature.read_bytes())
                for algorithm in HASHES:
                    archive.writestr(entry + "." + algorithm,
                                     hashlib.new(algorithm, data, usedforsecurity=False).hexdigest() + "\n")
        with ZipFile(candidate) as archive:
            expected = {name + suffix for name in files for suffix in ("", ".asc", *("." + h for h in HASHES))}
            if set(archive.namelist()) != expected or len(archive.namelist()) != len(expected) or archive.testzip():
                raise ValueError("Archive layout or integrity failure")
            for name in files:
                data = archive.read(name)
                for algorithm in HASHES:
                    if archive.read(name + "." + algorithm).decode().strip() != hashlib.new(
                            algorithm, data, usedforsecurity=False).hexdigest():
                        raise ValueError("Checksum mismatch: " + name)
        candidate.rename(output)
    print(f"Local signed rehearsal: {len(files)} artifacts, {len(files)} verified signatures, {len(files)*len(HASHES)} checksums")
    print("NOT FOR UPLOAD: license and owner signing/namespace approval are pending.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--gpg-home", type=Path, required=True)
    parser.add_argument("--fingerprint", required=True)
    args = parser.parse_args()
    sign_bundle(args.root.resolve(), args.output.resolve(), args.gpg_home.resolve(), args.fingerprint)


if __name__ == "__main__":
    main()
