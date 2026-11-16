#!/usr/bin/env bash
# Public retrieval only: do not use owner's private key or contact Central publishing APIs.
set -euo pipefail
fingerprint=5665F103A52133326804A38EE416B03087856896
work="$(mktemp -d)"
cleanup() {
  gpgconf --homedir "$work" --kill all 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
chmod 700 "$work"
curl --fail --silent --show-error --location --retry 3 --max-time 60 \
  "https://keys.openpgp.org/vks/v1/by-fingerprint/$fingerprint" -o "$work/public.asc"
gpg --batch --homedir "$work" --import "$work/public.asc" >"$work/import.log" 2>&1
# Verify a non-expired, non-revoked signing-capable primary key with exact fingerprint.
gpg --batch --homedir "$work" --with-colons --list-keys "$fingerprint" |
  awk -F: -v expected="$fingerprint" -v now="$(date +%s)" '
    $1 == "pub" { primary=1; valid=($2 !~ /^[red]/ && ($7 == "" || $7 > now) && $12 ~ /s/) }
    $1 == "fpr" && primary { if (valid && $10 == expected) found=1; primary=0 }
    END { if (!found) exit 1 }
  '
echo "Central-supported keys.openpgp.org: verified active signing fingerprint $fingerprint"
