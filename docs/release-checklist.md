# BatchRecover 0.1.0 release preparation

This sprint prepares a local candidate only. **No publication, upload, tag or GitHub release is authorized.** The owner has authorized preparing version 0.1.0 and its SCM tag metadata, but not creating the tag or publishing.

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

Docker and JDK 21 are required. Install writes only to the local Maven repository; do not invoke deploy. The release-preparation profile attaches sources and Javadoc JARs. The local staging script checks archives and creates an unsigned preview ZIP with SHA-256 checksums. It validates version 0.1.0, SCM metadata and the approved Apache-2.0 license, but contains no signatures; it is not a Central-valid bundle. The preview includes the parent POM and five library modules; examples and coverage module are excluded. No active publishing extension or publishing credentials are configured. Sonatype Central Publisher 0.12.0 and Maven GPG 3.2.8 are prepared in pluginManagement only, with autoPublish=false, skipUpload=true and GPG best practices. Maven deployment is disabled.

## Quality and security

JaCoCo aggregates production classes of the five library modules and execution data from those modules plus the example integration tests. Separate module reports also exist. Strict CI requires 95% lines and instructions, 90% branches, 85% critical-domain PIT score, zero SpotBugs findings, and complete reports. Test names are English camelCase; Mockito explicit agent is used for unit tests on JDK 21.

CycloneDX produces a reactor dependency SBOM including test dependencies. The OSV query audit reports known advisories against resolved Maven coordinates; it is not SAST, a secrets scan, plugin dependency analysis, exploitability analysis or container image analysis. A failed request or incomplete response is a failure. CI now blocks on any matching advisory, incomplete response or request failure; review and resolve findings before release. No claims of absence of vulnerabilities beyond the scanned coordinates and data available at scan time.

## Owner decisions and prepublication gates

- [x] Owner approved Apache-2.0 (2026-11-15); full [LICENSE](../LICENSE), inherited POM license metadata and binary JAR license resources added.
- [ ] Review third-party license obligations and existing attribution notices before publication.
- [x] Namespace io.github.hamzador is listed as Verified in the owner's Central Portal (owner-provided screenshot, 2026-11-15). This is not an authenticated API validation or upload authorization.
- [ ] Confirm developer metadata and identity; do not invent contact details.
- [ ] Review the security report and threat model; resolve applicable advisories and document any risk acceptance. Perform a source code/secret and build-supply-chain review.
- [ ] Review public API compatibility, transaction conditions, integration guide and example on an independent application.
- [x] Owner authorized version 0.1.0 and planned SCM tag v0.1.0; reactor, benchmarks and consumer library versions updated. The actual Git tag is not created.
- [x] Owner signing key configured and public key retrieved; earlier candidate signed and validated locally.
- [ ] Rebuild and verify the signed bundle from the final approved commit (previous signed ZIP is superseded). No private keys or tokens in Git.
- [ ] After owner approval, produce the final release bundle with approved metadata, verify its signatures against the owner's public key, review the exact allowlist, and obtain separate explicit approval before upload. The offline rehearsal avoids invoking the publishing plugin or deploy at all.

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
