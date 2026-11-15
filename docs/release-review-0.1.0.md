# BatchRecover 0.1.0 — final prepublication review

Review date: 2026-11-15. Reviewed baseline: [`a9f4402479aa027c21730a79df8b44702061d863`](https://github.com/hamzador/BatchRecover/commit/a9f4402479aa027c21730a79df8b44702061d863). Evidence: [successful main CI run 43709908332](https://github.com/hamzador/BatchRecover/actions/runs/43709908332), its archived SBOM, quality reports, test-key rehearsal, and the owner-signed corrected bundle `batchrecover-0.1.0-release-signed.zip`.

## Artifact identity

- SHA-256: `ea3026136138f7ae18acb1336155d358f5dd546170070165d69d9ced3f45a408`
- Size: 1,062,030 bytes. Exactly 126 file entries (21 artifacts, 21 detached signatures, 84 checksums).
- Signing fingerprint: `5665F103A52133326804A38EE416B03087856896`. 21 valid RSA/SHA-256 signatures under the supplied RSA-4096 public key in a temporary keyring; all four checksum algorithms match for each artifact. No secret key imported.
- Six POMs match CI after line-ending normalization. Five library binary JARs: 79 identical class files and matching resources after line-ending normalization; five source JARs: 71 identical Java sources after line-ending normalization. Five Javadoc JARs are byte-identical to CI, with 193 HTML pages and 25 complete legal files. Non-Javadoc artifacts match the previous owner-signed candidate. This is content comparison, not a reproducible-build attestation.
- Only parent POM and core, storage-memory, storage-jdbc, spring-batch, spring-boot-starter artifacts included. No test classifiers, examples, coverage, benchmarks, vendored dependency classes, or CI test signatures.

## Evidence and scope

| Area | Result | Scope / residual risk |
| --- | --- | --- |
| CI | PASS | Main run completes reactor, real-backend integration tests, independent consumers, strict coverage/PIT/SpotBugs, dependency audit, JMH smoke and offline signing rehearsal. |
| Code / threat model | PASS (targeted review) | Opt-in capture; typed codecs with explicit versions, bounded payload, Unicode validation; JDBC bind parameters and trusted dialect extensions; optimistic revision checks, lease/fencing and rollback; bounded volatile memory. Trusted in-process API; no remote authorization boundary. Host owns scope authorization, data minimization/protection/retention, safe handler and transaction configuration. |
| Secret review | PASS (snapshot only) | Reviewed released sources/resources and remaining tracked text, config, scripts, examples and test fixtures. Targeted patterns for private keys, popular token formats and explicit credentials. No actual secret identified; not entropy scanning, full Git history or GitHub private settings. Complete historical scan before publication. |
| Maven dependencies | PASS (CI inventory) | CycloneDX 95 external Maven coordinates, OSV zero matches at run time; not a proof of no vulnerabilities and excludes build/plugin/image and non-Maven documentation assets. |
| Licenses | PASS (technical inventory) | All 95 dependencies have declared licenses. Apache-2.0 own code and inherited POMs, license resources present. Test drivers (including licensed MySQL/Oracle/H2 dependencies) not shipped as Java classes. Javadoc includes OpenJDK-origin scripts plus jQuery 3.7.1 and jQuery UI 1.14.1; preserve GPLv2/exception and MIT notices and determine any remaining redistribution obligations; code rights and full legal compliance are owner responsibilities. No legal certification. |
| Build / supply chain | REVIEWED, OPEN | Maven wrapper distribution SHA-256 configured, versioned dependencies, Dependabot, fresh CI runner, read-only workflow and no persisted checkout credentials; deploy disabled, signing and uploading not automated. Actions checkout/setup-java use v5, upload-artifact v4, runner ubuntu-latest and database images use mutable tags. Pin immutable revisions/digests or document explicit acceptance for this release; build plugins/images and Javadoc assets lack a separate comprehensive vulnerability scan, no independent provenance attestation. |
| Public API and docs | PASS (first release) | Reviewed source and quickstart/API guide/storage/transaction/SQL dialect docs; standalone Boot consumers use installed artifacts. No previous public release for binary compatibility comparison. Java 21, Spring Boot 4 / Batch 6 documented matrix; processor-skip capture only. JDBC transaction guarantees require the host's same physical transaction; no universal exactly-once external side effects. Memory is volatile and nontransactional. No production support claim. |
| Maven metadata | PASS (local) | Effective group io.github.hamzador, version 0.1.0, parent POM and child jar packaging, names/descriptions and inherited license/project URL/developer/SCM details. Developer name/id hamzador, public profile URL; owner confirmation pending. Planned SCM tag v0.1.0 not created. Central supported keyserver discovery and remote validation remain pending. |

## Release decision

**GO for the reviewed offline candidate; NO-GO for immediate public release.** Do not regenerate the signed archive merely for this documentation record or change the release baseline silently. This record is not published as part of the audited commit. The owner must close or explicitly accept the residual obligations in the [checklist](release-checklist.md), authorize a tag at the exact baseline, authorize upload separately and review the Central validation result before release. No tag, upload or publication is authorized here.

Review limitations: pattern checks and CI reports are not a penetration test, SAST certification, complete history scan, legal review or fresh independent build. No inspection of hosted secrets or restricted portal/account settings. Central validation has not run; GitHub Actions test-key archives must never be uploaded. The private signing key stays with its owner.

References: [Central requirements](https://central.sonatype.org/publish/requirements/), [PGP and keyserver requirements](https://central.sonatype.org/publish/requirements/gpg/), [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use), [API guide](api.md), [security policy](../SECURITY.md), [existing targeted review](release-review.md).
