#!/usr/bin/env bash
# CI-only disposable key and versioned checkout. No deploy, token, upload or Git mutation.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
work="$(mktemp -d)"
cleanup() {
  gpgconf --homedir "$work/gpg" --kill all 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
mkdir -m 700 "$work/gpg"
mkdir "$work/checkout"
git archive HEAD | tar -x -C "$work/checkout"
python3 - "$work/checkout" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
for pom in root.rglob("pom.xml"):
    pom.write_text(pom.read_text().replace("0.1.0-SNAPSHOT", "0.1.0"))
PY
# The normal reactor and strict quality gate have already run on this exact source.
# Rebuild actual non-SNAPSHOT artifacts instead of renaming snapshot JARs.
# Isolate the candidate repo from the ordinary Maven cache: never persist unofficial 0.1.0 artifacts.
mkdir "$work/m2"
if [ -d "$HOME/.m2/repository" ]; then
  cp -a --reflink=auto "$HOME/.m2/repository/." "$work/m2/"
fi
rm -rf "$work/m2/io/github/hamzador"
cd "$work/checkout"
./mvnw -B -ntp "-Dmaven.repo.local=$work/m2" -Prelease-preparation -DskipTests clean install
./mvnw -B -ntp "-Dmaven.repo.local=$work/m2" -f consumer-examples/memory/pom.xml clean verify
./mvnw -B -ntp "-Dmaven.repo.local=$work/m2" -f consumer-examples/jdbc/pom.xml clean verify
gpg --homedir "$work/gpg" --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key "BatchRecover CI rehearsal (NOT FOR UPLOAD) <ci-rehearsal@example.invalid>" rsa3072 sign 1d
fingerprint="$(gpg --homedir "$work/gpg" --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" && !seen++ {print $10}')"
cd "$root"
python3 scripts/signed_candidate.py --root "$work/checkout" \
  --output "$root/target/batchrecover-0.1.0-DO-NOT-UPLOAD.zip" \
  --gpg-home "$work/gpg" --fingerprint "$fingerprint"
gpg --homedir "$work/gpg" --armor --export "$fingerprint" > target/rehearsal-public-key.asc
