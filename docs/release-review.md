# Pre-release review

Scope: targeted maintainer review of domain validation, capture and state transitions, optimistic versions, handler dispatch, fencing, transaction boundaries, starter configuration, SQL dialects, and release scripts/build settings. Not an independent security audit or exhaustive review of third-party code.

| Area | Review result / residual requirement |
| --- | --- |
| Payload handling | Explicit typed codecs; bounded UTF-8 payloads; no arbitrary class loading. The host must prevent sensitive payload logging, restrict access and retain compatible decoders. |
| Identity | Metadata had a malformed UTF-16 gap: isolated surrogates could be rejected or replaced differently by drivers. Domain text validation now rejects malformed Unicode; supplementary characters remain valid. Public boundary regression tests included. |
| Capture | Default JDBC capture requires existing compatible transaction; no detached fallback. Memory is explicitly volatile and nontransactional. |
| Replay | Version/token/revision fencing and transactional local JDBC effect checked by real backend contracts. Idempotent handlers and bounded blocking remain host obligations. External side effects are out of scope. |
| SQL | Values parameterized; only trusted extension fragments enter SQL. Versioned PostgreSQL DDL preserved. Oracle/portable schema differences and H2 deployment limitations documented. |
| Authorization | Library API is trusted local code, not an authorization boundary; host must authorize scope and reject IDs. No web endpoint or remote scheduler. |
| Dependencies | OSV is now blocking for scanned reactor dependencies. SBOM, strict JaCoCo/PIT, SpotBugs, JMH and consumer checks retained. Not a scan of all build plugins, runner/container images or hosted secrets. |
| Build | Read-only GitHub Actions permissions, no persisted checkout credentials; no deployment execution or publishing token. Action tags and database image tags are not immutable digests; review/pin them separately as part of supply-chain hardening. |
| Distribution | Exact artifact allowlist excludes test classifiers and internal modules. Non-SNAPSHOT rehearsal uses a disposable key, validates each signature and four checksums; Apache-2.0 is approved by the owner and the namespace is verified from the supplied screenshot; third-party license review and verified owner signing remain blocking. |

Prior to public release, the owner must confirm all code and third-party distribution license obligations, Central namespace control, developer metadata, an independently held signing key, and review the release notes/operations limits. Fresh security scan and independent review remain recommended; the current checks do not establish production safety by themselves.
