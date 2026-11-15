# Prepublication supply-chain hardening

2026-11-15. Previous verified baseline: a9f4402479aa027c21730a79df8b44702061d863, run [43709908332](https://github.com/hamzador/BatchRecover/actions/runs/43709908332). The owner-signed ZIP hash ea3026136138f7ae18acb1336155d358f5dd546170070165d69d9ced3f45a408 and 21 signatures were independently rechecked with the supplied public key. That evidence is for the previous commit only.

## Changes and evidence required

- Pin actions/checkout v5 to fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09, actions/setup-java v5 to b6effb05e454b25005698d916606bdc6ffcbf961 and actions/upload-artifact v4 to ea165f8d65b6e75b540449e92b4886f43607fa02. Verified refs and commit verification through upstream GitHub APIs; these match the successful baseline job's action download log. No major version upgrade. Dependabot remains enabled.
- Full non-shallow checkout and Gitleaks 8.30.0 with archive SHA-256 from upstream release asset metadata. Scan all locally reachable history, redacted output, nonzero exit on findings. Not a scan of removed refs, hosted secrets or private GitHub security settings.
- Isolate the Maven repository for this build; enumerate actual downloaded JAR Maven coordinates (including plugins and their dependencies) and query OSV with the existing fail-closed audit, in addition to the reactor SBOM. Not a full dependency provenance graph, and inactive pluginManagement entries may not be resolved. Maven distribution SHA-256 remains checked by wrapper.
- Review the three database images plus the Testcontainers Ryuk helper used by CI. Pull before the tests, record local IDs and repository digests, confirm unchanged after tests, then scan the local images with Trivy 0.76.2 (archive SHA-256 from upstream asset). HIGH/CRITICAL, including unfixed findings, or scanner failure blocks CI. H2 runs natively. No image is shipped in the release JARs. This records observed immutable identities but does not yet pin source tags; future digest pinning requires actual observed values and revalidation. Additional helper images, hosted runner, JDK and JS assets remain separate review scopes.
- Retrieve the release public key independently from keys.openpgp.org into a temporary isolated public keyring; require exact fingerprint and active signing-capable primary key. No key upload, private key or publication credentials. Central lists this service as supported: https://central.sonatype.org/publish/requirements/gpg/.
- Existing strict test, coverage, mutation, SpotBugs, OSV and signing rehearsal gates retained. No publishing mechanism is activated. Scanner downloads are checksum-verified and used only in CI.

## Release gate

CI outcomes for this new branch are pending when this document is written. An unsuccessful or incomplete scan is not evidence of absence of findings. Review actual reports and resolve applicable findings or document formal residual-risk approval. Do not silently suppress results, change thresholds or use a previous green build as proof for this commit. Preserve the previously signed archive; after a new baseline is validated, rebuild and sign from that precise commit. No tag, deploy, upload or publication authorized.
