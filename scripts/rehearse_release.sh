#!/usr/bin/env bash
# CI-only disposable signing key. Sign the actual tested release build; no network publication.
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
gpg --homedir "$work/gpg" --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key "BatchRecover CI rehearsal (NOT FOR UPLOAD) <ci-rehearsal@example.invalid>" rsa3072 sign 1d
fingerprint="$(gpg --homedir "$work/gpg" --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" && !seen++ {print $10}')"
python3 scripts/signed_candidate.py --root "$root" \
  --output "$root/target/batchrecover-0.1.0-DO-NOT-UPLOAD.zip" \
  --gpg-home "$work/gpg" --fingerprint "$fingerprint"
gpg --homedir "$work/gpg" --armor --export "$fingerprint" > target/rehearsal-public-key.asc
