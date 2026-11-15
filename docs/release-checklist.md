# BatchRecover 0.1.0 release preparation

This sprint prepares a local candidate only. **No publication, upload, tag or GitHub release is authorized.** Keep the reactor at 0.1.0-SNAPSHOT until the owner approves release metadata and the remaining gates.

## Local validation

```sh
./mvnw -B -ntp -Prelease-preparation clean install
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DincludeTestScope=true -DoutputReactorProjects=false -Dcyclonedx.skipNotDeployed=false \
  -Dcyclonedx.skipAttach=true -DoutputFormat=json
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/audit_dependencies.py target/bom.json --output target/osv-report.json
python3 scripts/coverage_summary.py batchrecover-coverage/target/site/jacoco-aggregate/jacoco.xml
python3 scripts/stage_release.py
```

Docker and JDK 21 are required. Install writes only to the local Maven repository; do not invoke deploy. The release-preparation profile attaches sources and Javadoc JARs. The local staging script checks archives and creates an unsigned preview ZIP with SHA-256 checksums. It intentionally accepts only the SNAPSHOT version and contains neither signatures nor approved license metadata; it is not a Central-valid bundle. The preview includes the parent POM and five library modules; examples and coverage module are excluded. No active publishing extension or publishing credentials are configured. Sonatype Central Publisher 0.12.0 and Maven GPG 3.2.8 are prepared in pluginManagement only, with autoPublish=false, skipUpload=true and GPG best practices. Maven deployment is disabled.

## Quality and security

JaCoCo aggregates production classes of the five library modules and execution data from those modules plus the example integration tests. Separate module reports also exist. Strict CI requires 95% lines and instructions, 90% branches, 85% critical-domain PIT score, zero SpotBugs findings, and complete reports. Test names are English camelCase; Mockito explicit agent is used for unit tests on JDK 21.

CycloneDX produces a reactor dependency SBOM including test dependencies. The OSV query audit reports known advisories against resolved Maven coordinates; it is not SAST, a secrets scan, plugin dependency analysis, exploitability analysis or container image analysis. A failed request or incomplete response is a failure. CI now blocks on any matching advisory, incomplete response or request failure; review and resolve findings before release. No claims of absence of vulnerabilities beyond the scanned coordinates and data available at scan time.

## Owner decisions and prepublication gates

- [ ] Adopt a license (Apache-2.0 proposed) explicitly; add full LICENSE and accurate POM license metadata. Review dependency license obligations.
- [x] Namespace io.github.hamzador is listed as Verified in the owner's Central Portal (owner-provided screenshot, 2026-11-15). This is not an authenticated API validation or upload authorization.
- [ ] Confirm developer metadata and identity; do not invent contact details.
- [ ] Review the security report and threat model; resolve applicable advisories and document any risk acceptance. Perform a source code/secret and build-supply-chain review.
- [ ] Review public API compatibility, transaction conditions, integration guide and example on an independent application.
- [ ] Select version 0.1.0 (non-SNAPSHOT) and update all reactor versions/tag metadata after approval.
- [ ] Configure GPG signing with keys held by the owner, and generate signed sources, Javadoc, JAR and POM artifacts. Verify the signatures and packaging. No private keys or tokens in Git.
- [ ] After owner approval, produce the final release bundle with approved metadata, verify its signatures against the owner's public key, review the exact allowlist, and obtain separate explicit approval before upload. The offline rehearsal avoids invoking the publishing plugin or deploy at all.

References: [Central requirements](https://central.sonatype.org/publish/requirements/), [Central Maven publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/), [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/), [JaCoCo report aggregation](https://www.jacoco.org/jacoco/trunk/doc/report-aggregate-mojo.html).

The approved roadmap now requires the generic storage and dialect milestones, tests on each advertised backend, and quality hardening before publication. The test-only core contract JAR is not included in the local preview; review attached artifacts before actual release.

Sprint 8 adds PIT, SpotBugs and isolated JMH. Complete the [quality guide](quality.md) commands, review reports and run `python3 scripts/quality_report.py --strict` successfully before publication. Do not bypass an unmet quality target or unreviewed finding.

Before publication, verify both standalone consumer applications with their independent Boot parent POMs and locally installed artifacts. The [quick start](quickstart.md), [API/extension guide](api.md) and [consumer examples](../consumer-examples/README.md) must match the verified release version. Update the snapshot coordinates only when the release is authorized.

## Signed rehearsal (no publication)

After the strict quality gate, CI runs:

```sh
bash scripts/rehearse_release.sh
```

This exports the checked-out Git commit into a temporary directory, changes only POM version references to 0.1.0, rebuilds actual non-SNAPSHOT JARs and POMs in an isolated temporary Maven repository (never cached in the ordinary repository), with tests skipped for that second reactor build (the full snapshot suite already ran), and builds/tests both standalone consumers against the versioned local artifacts. It generates a one-day disposable signing key in an isolated GnuPG home, signs exactly 21 artifacts, verifies each signature with an independent public-key-only keyring, checks all 84 MD5/SHA1/SHA256/SHA512 digests and refuses unexpected archive entries. The key is removed with the temporary directory; only the public key and DO-NOT-UPLOAD ZIP are archived as CI artifacts. Neither is an approved release bundle. No upload, deploy, token or tag. Test keys must never be used for actual publication.

For eventual release, prepare a separate authorized non-SNAPSHOT checkout with approved LICENSE, POM metadata and tag target, rebuild and run all quality/consumer tests; use the owner-controlled GPG key with a primed agent and an explicit signing-key fingerprint. Do not send private keys, tokens or passphrases through chat or commit them. The allowlisted manual Central ZIP layout is documented in the [official upload guide](https://central.sonatype.org/publish/publish-portal-upload/); the official plugin remains inactive until an approved publishing change. No remote Central validation has been performed. Read [release notes](release-notes-0.1.0.md) and [review](release-review.md).
