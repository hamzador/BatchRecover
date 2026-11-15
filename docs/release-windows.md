# Windows: build and sign the final 0.1.0 bundle

Local preparation only. Do not upload the archive, invoke Maven deploy, or create a Git tag. The POM's SCM tag v0.1.0 is metadata for a planned future tag, not proof that it exists.

Prerequisites: Java 21, Git, Python 3, GnuPG with the owner-controlled signing key. Docker is required for the full local integration suite; if Docker is unavailable, rely on the complete green CI for the **exact** commit and treat the local build as packaging only, not a new test run.

From the existing clone, fetch the repository and select the exact commit approved for release after CI. Replace the placeholder with the validated full SHA. Use a fresh worktree and a separate Maven repository.

```powershell
Set-Location "$HOME\BatchRecover-release"
git fetch origin
$commit = "REPLACE_WITH_VALIDATED_COMMIT_SHA"
git worktree add --detach "$HOME\BatchRecover-0.1.0-final" $commit
Set-Location "$HOME\BatchRecover-0.1.0-final"
git rev-parse HEAD
git status --porcelain
```

Stop if the commit differs or the worktree is not clean. No manual POM replacement is needed.

```powershell
.\mvnw.cmd -B -ntp -Prelease-preparation "-DskipTests" "-Dmaven.repo.local=$HOME\.m2\batchrecover-final-0.1.0" clean install
```

Stop on any error. If Docker is available, omit -DskipTests. Validate the unsigned preview, then sign using the actual signing key fingerprint from gpg --list-secret-keys --with-fingerprint. The private key and passphrase remain on your machine.

```powershell
python scripts/stage_release.py
python scripts/signed_candidate.py --root . --output target/batchrecover-0.1.0-final-signed.zip --gpg-home "$env:APPDATA\gnupg" --fingerprint YOUR_FULL_SIGNING_FINGERPRINT
Get-FileHash target\batchrecover-0.1.0-final-signed.zip -Algorithm SHA256
```

Expect 21 artifacts, 21 valid signatures, 84 checksums. The previous rehearsal ZIP is not the final bundle. Preserve the commit SHA and ZIP hash together. Submit the final ZIP and public key for independent review; never transmit a secret key. The script still prints a publication warning deliberately. No Central validation or upload is authorized. See [release checklist](release-checklist.md).
