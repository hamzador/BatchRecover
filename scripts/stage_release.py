"""Unsigned local preview; never contact a publishing service."""
import hashlib
from signed_candidate import entries, ROOT
from zipfile import ZipFile, ZIP_DEFLATED


def main():
    files = entries(ROOT)
    output = ROOT / "target/batchrecover-local-preview.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for entry, path in files.items():
            data = path.read_bytes()
            archive.writestr(entry, data)
            archive.writestr(entry + ".sha256", hashlib.sha256(data).hexdigest() + "\n")
    print(f"Local preview: {len(files)} unsigned artifacts/POMs, SHA-256 checksums, {output.name}")
    print("Not a Central upload bundle: no signatures.")


if __name__ == "__main__":
    main()
