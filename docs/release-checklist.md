# BatchRecover 0.1.0 release preparation

This sprint prepares a local candidate only. **No publication, upload, tag or GitHub release is authorized.** Keep the reactor at 0.1.0-SNAPSHOT until the owner approves release metadata and the remaining gates.

## Local validation

```sh
./mvnw -B -ntp -Prelease-preparation clean install
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DincludeTestScope=true -DoutputReactorProjects=false -DskipNotDeployed=false \
  -Dcyclonedx.skipAttach=true -DoutputFormat=json
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/audit_dependencies.py target/bom.json --output target/osv-report.json
python3 scripts/coverage_summary.py batchrecover-coverage/target/site/jacoco-aggregate/jacoco.xml
python3 scripts/stage_release.py
```

Docker and JDK 21 are required. Install writes only to the local Maven repository; do not invoke deploy. The release-preparation profile attaches sources and Javadoc JARs. The local staging script checks archives and creates an unsigned preview ZIP with SHA-256 checksums. It intentionally accepts only the SNAPSHOT version and contains neither signatures nor approved license metadata; it is not a Central-valid bundle. The preview includes the parent POM and five library modules; examples and coverage module are excluded. No publishing plugin or publishing credentials are configured.

## Quality and security

JaCoCo aggregates production classes of the five library modules and execution data from those modules plus the example integration tests. Separate module reports also exist. Review measured line/branch/instruction coverage; there is no arbitrary minimum gate yet. CI checks that the aggregate report exists and is nonempty. Test names are English camelCase; Mockito explicit agent is used for unit tests on JDK 21.

CycloneDX produces a reactor dependency SBOM including test dependencies. The OSV query audit reports known advisories against resolved Maven coordinates; it is not SAST, a secrets scan, plugin dependency analysis, exploitability analysis or container image analysis. A failed request or incomplete response is a failure. CI uses report-only for advisory matches so they remain visible without blocking unrelated development; review and resolve any applicable advisory before release. Run the command without report-only as a conservative release gate. No claims of absence of vulnerabilities beyond the scanned coordinates and data available at scan time.

## Owner decisions and prepublication gates

- [ ] Adopt a license (Apache-2.0 proposed) explicitly; add full LICENSE and accurate POM license metadata. Review dependency license obligations.
- [ ] Verify control of io.github.hamzador in Sonatype Central Portal; confirm identity and developer metadata.
- [ ] Review the security report and threat model; resolve applicable advisories and document any risk acceptance. Perform a source code/secret and build-supply-chain review.
- [ ] Review public API compatibility, transaction conditions, integration guide and example on an independent application.
- [ ] Select version 0.1.0 (non-SNAPSHOT) and update all reactor versions/tag metadata after approval.
- [ ] Configure GPG signing with keys held by the owner, and generate signed sources, Javadoc, JAR and POM artifacts. Verify the signatures and packaging. No private keys or tokens in Git.
- [ ] Configure the official Sonatype Central Publisher plugin with autoPublish=false and skipUpload=true for a real signed local dry run, excluding examples and coverage. Inspect the bundle, then obtain separate explicit approval before any upload or release.

References: [Central requirements](https://central.sonatype.org/publish/requirements/), [Central Maven publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/), [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/), [JaCoCo report aggregation](https://www.jacoco.org/jacoco/trunk/doc/report-aggregate-mojo.html).

The approved roadmap now requires the generic storage and dialect milestones, tests on each advertised backend, and quality hardening before publication. The test-only core contract JAR is not included in the local preview; review attached artifacts before actual release.
