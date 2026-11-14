# Security policy

BatchRecover is pre-alpha and not supported for production. Do not submit secrets, production payloads or exploit details in public issues. Use GitHub private vulnerability reporting if enabled, or request a private contact via a minimal public issue without sensitive details. No response SLA is promised.

## Dependency audit and scope

CI retains a CycloneDX SBOM and OSV advisory report. Review and triage all applicable findings before release; the report is not a penetration test or a security guarantee. See [release checklist](docs/release-checklist.md). Do not expose recovery operations to untrusted callers without implementing host authorization.
