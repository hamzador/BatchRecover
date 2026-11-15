# BatchRecover 0.1.0 release preparation

This sprint prepares a local candidate only. **No publication, upload, tag or GitHub release is authorized.** The owner has authorized preparing version 0.1.0 and its SCM tag metadata, but not creating the tag or publishing.

## Local validation

```sh
./mvnw -B -ntp -Prelease-preparation clean install
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
  -DincludeTestScope=true -DoutputReactorProjects=false -Dcyclonedx.skipNotDeployed=false \
  -Dcyclonedx.skipAttach=true -DoutputFormat=json
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/audit_dependencies.py target/bom.json --output target/osv-report.json
python3 scripts/coverage_summary.py batchrecover-coverage/target/site/jacoco-aggregate/jacoco.xml
python3 scripts/stage_release.py
```

Docker and JDK 21 are required. Install writes only to the local Maven repository; do not invoke deploy. The release-preparation profile attaches sources and Javadoc JARs. The local staging script checks archives and creates an unsigned preview ZIP with SHA-256 checksums. It validates version 0.1.0, SCM metadata and the approved Apache-2.0 license, but contains no signatures; it is not a Central-valid bundle. The preview includes the parent POM and five library modules; examples and coverage module are excluded. No active publishing extension or publishing credentials are configured. Sonatype Central Publisher 0.12.0 and Maven GPG 3.2.8 are prepared in pluginManagement only, with autoPublish=false, skipUpload=true and GPG best practices. Maven deployment is disabled.

## Quality and security

JaCoCo aggregates production classes of the five library modules and execution data from those modules plus the example integration tests. Separate module reports also exist. Strict CI requires 95% lines and instructions, 90% branches, 85% critical-domain PIT score, zero SpotBugs findings, and complete reports. Test names are English camelCase; Mockito explicit agent is used for unit tests on JDK 21.

CycloneDX produces a reactor dependency SBOM including test dependencies. The OSV query audit reports known advisories against resolved Maven coordinates; it is not SAST, a secrets scan, plugin dependency analysis, exploitability analysis or container image analysis. A failed request or incomplete response is a failure. CI now blocks on any matching advisory, incomplete response or request failure; review and resolve findings before release. No claims of absence of vulnerabilities beyond the scanned coordinates and data available at scan time.

## Owner decisions and prepublication gates

Record of the review of commit `a9f4402479aa027c21730a79df8b44702061d863`, not approval to publish. The evidence is in [the prepublication report](release-review-0.1.0.md).

- [x] Owner approved Apache-2.0 (2026-11-15); full license, POM metadata and library license resources verified.
- [x] Technical third-party inventory: 95 external Maven coordinates with declared licenses; no third-party Java classes bundled; 25 complete Javadoc legal files across five JARs. Attribution review is not a legal opinion.
- [ ] Owner confirms rights to contribute and redistribute the code and all applicable third-party obligations.
- [x] Namespace io.github.hamzador shown Verified in owner-provided Central Portal screenshot (2026-11-15); no authenticated portal verification.
- [x] Maven metadata reviewed: developer id/name hamzador and public GitHub profile, SCM connection and planned tag v0.1.0; no invented personal contact details.
- [ ] Owner confirms developer metadata and identity as published.
- [x] Reviewed security boundaries, code, current tracked source/configuration, CI evidence and threat/operating limits. OSV: zero matches for 95 scanned Maven coordinates. Pattern-based secret review did not identify an actual credential in the reviewed snapshot.
- [x] Build-chain review: Maven distribution checksum/versions, read-only Actions, no persisted checkout credentials or publishing execution, independently checked signatures and comparison against CI.
- [x] Full reachable history Gitleaks scan: 49 commits, no leaks in both initial hardening runs. Repeat on final SHA.
- [ ] Verify hosted secret-scanning/security settings (not accessible from CI).
- [x] Pin the three GitHub Actions to verified full commits used by the previous successful CI.
- [ ] Resolve remaining build-chain findings from build-repository OSV and test-image Trivy gates without lowering thresholds; initial runs reported 59 OSV matches and 108 image occurrences; remediation candidate pending CI; review recorded image digests and mutable runner/JDK, plugin coverage limits, Javadoc assets and lack of independent attestation.
- [x] Public API and supported transaction/storage contracts reviewed against source and CI; two independent consumer examples pass. First pre-1.0 release, not a promise of backwards compatibility or production support.
- [x] Owner authorized version 0.1.0 and planned SCM tag v0.1.0; commit and all library coordinates fixed. Actual tag does not exist.
- [x] Owner signing key configured; 21 final artifact signatures independently verified using the provided public key in an isolated keyring.
- [x] Independent GPG key retrieval from keys.openpgp.org passed both initial hardening runs; repeat on final SHA. This is not Central remote bundle validation.
- [x] Final corrected archive reviewed: exact six POMs, 15 JARs, 21 signatures and 84 checksums; 79 classes, 71 Java sources, 5 CI-identical Javadoc archives; no private signing key in the supplied bundle. Earlier archives superseded.
- [ ] Owner authorizes creation of tag v0.1.0 on the audited commit, and separately authorizes Central upload/publication.
- [ ] Central remote validation succeeds after authorized upload; release only after owner approval.

**Decision: NO-GO for immediate public release.** The prior signed candidate is still tied to a9f4402; this hardening branch changes the commit and requires new CI validation, final build/signing and owner review. See [supply-chain hardening](supply-chain-hardening.md). Do not tag, deploy, upload, or publish based on this document alone.

References: [Central requirements](https://central.sonatype.org/publish/requirements/), [Central Maven publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/), [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/), [JaCoCo report aggregation](https://www.jacoco.org/jacoco/trunk/doc/report-aggregate-mojo.html).

The approved roadmap now requires the generic storage and dialect milestones, tests on each advertised backend, and quality hardening before publication. The test-only core contract JAR is not included in the local preview; review attached artifacts before actual release.

Sprint 8 adds PIT, SpotBugs and isolated JMH. Complete the [quality guide](quality.md) commands, review reports and run `python3 scripts/quality_report.py --strict` successfully before publication. Do not bypass an unmet quality target or unreviewed finding.

Before publication, verify both standalone consumer applications with their independent Boot parent POMs and locally installed artifacts. The [quick start](quickstart.md), [API/extension guide](api.md) and [consumer examples](../consumer-examples/README.md) must match the verified release version. The release coordinates are now 0.1.0; the sample applications keep their independent versions.

## Signed rehearsal (no publication)

After the strict quality gate, CI runs:

```sh
bash scripts/rehearse_release.sh
```

This signs the actual 0.1.0 artifacts from the successful CI build (including integration tests and strict gates), using a one-day disposable signing key in an isolated GnuPG home. Exactly 21 artifacts, 21 signatures and 84 digests are checked. The private test key is removed on exit. Only its public key and DO-NOT-UPLOAD ZIP are archived. No upload, deploy, token or tag. Test keys must never be used for publication.

For the owner build, follow the [Windows signing guide](release-windows.md) from the exact validated final commit. Use the owner-controlled GPG key; keep the prior candidate separate. Do not send private keys, tokens or passphrases through chat or commit them. The allowlisted manual Central ZIP layout is documented in the [official upload guide](https://central.sonatype.org/publish/publish-portal-upload/); the official plugin remains inactive until an approved publishing change. No remote Central validation has been performed. Read [release notes](release-notes-0.1.0.md) and [review](release-review.md).
