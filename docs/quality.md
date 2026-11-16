# Quality, testing and performance (Sprint 8)

## Regression and release gates

The Sprint 7 verified baseline is 167 Java tests (88 unit, 79 integration); 95.1% lines, 94.8% instructions, 74.3% branches. Sprint 8 extends domain boundaries, fencing, stale claims, retry, payload validation, and shared storage contracts. Each real backend (PostgreSQL 16, MySQL 8.4, Oracle Free 23, native H2) and memory execute a bounded 80-record queue with four competing workers; each record must be invoked once, finalized once and have a complete audit trail. The tests use a bounded test-only connection pool for portable engines and retry only the same reserved token on transient SKIP LOCKED misses (no callback run). All worker threads are joined before test cleanup. Integration load tests exposed transient locks from stale candidate scans, so the repository uses native locking limits where supported; Oracle retains its portable two-phase strategy. Concurrent corrections must have exactly one version winner. The memory-only load test fills 1,000 records with all retained revisions, drains the queue and verifies no silent eviction. Portable SQL tests explicitly prove a duplicate inside an enclosing JDBC transaction does not destroy the enclosing transaction or its rollback.

Run `./mvnw -B -ntp -Prelease-preparation clean install` with Docker. JaCoCo includes unit and integration coverage; the aggregate report excludes example production classes and includes their execution data. CI now runs the strict quality gate: at least 95% lines and instructions, 90% branches, 85% killed mutations, no SpotBugs findings and complete valid reports. The earlier non-strict regression mode remains available for local diagnosis. No thresholds are lowered or source classes excluded to reach these targets.

## Mutation testing

```sh
./mvnw -B -ntp -pl batchrecover-core -Pmutation org.pitest:pitest-maven:mutationCoverage
```

PIT 1.22.0 and JUnit-platform plugin 1.2.3 operate on all core domain classes plus RetryPolicy and RecoverySettings. Standard default mutators; no exclusion of uncovered logic to inflate results. HTML/XML reports in core target/pit-reports. Killed mutations / all generated mutations is the conservative score (timeouts are not counted as killed). Investigate survivors, uncovered and timed-out mutations rather than asserting the same implementation. This is a developer-time plugin; it is not included in published dependencies.

### Sprint 8 review of the last validated baseline

Both [PR CI](https://github.com/hamzador/BatchRecover/actions/runs/43696995333) and [branch CI](https://github.com/hamzador/BatchRecover/actions/runs/43696992265) passed at commit b4e9d01: 242 tests, zero failures or skips; aggregate JaCoCo 98.34% lines, 97.98% instructions, 91.10% branches; PIT 70/77 killed (90.91%), seven survived; all five SpotBugs reports had zero findings and OSV found zero advisory matches among 84 Maven coordinates.

Review of the seven surviving mutations from the XML report:
- Rejection.claim: changing the attempt budget boundary from >= to > cannot admit an exhausted QUEUED snapshot, because its constructor already rejects it. Retain the defensive check.
- Rejection.correct, Rejection.failed and requirePending: removing the explicit transition call is equivalent with the current mandatory state checks and fixed allowed destinations. Retain the checks for clarity and defense in depth.
- RetryPolicy.delayAfter loop boundary: evaluating the loop once more at an already reached cap returns the same cap. The odd-nanosecond half-cap comparison, however, can change a result by one nanosecond; a new exact boundary test covers this.
- DomainChecks.text: a boundary mutation could allow a NUL at index zero. A new public-API test rejects leading NULs in identity fields and payloads.

The two added tests check real observable behavior, not internal call counts. No survivor is excluded from PIT, including the equivalent defensive paths. The next CI run must independently validate these changes and the strict gate.

## Static analysis

```sh
./mvnw -B -ntp \
  -pl batchrecover-core,batchrecover-storage-memory,batchrecover-storage-jdbc,batchrecover-spring-batch,batchrecover-spring-boot-starter \
  com.github.spotbugs:spotbugs-maven-plugin:4.10.5.0:spotbugs -Dspotbugs.xmlOutput=true
```

Reports cover all five library modules, no broad exclusion filters. Analyzer errors/missing classes fail the report check. Findings are reported by priority; strict CI refuses unreviewed findings. OSV dependency audit and CycloneDX SBOM remain separate, not a complete security audit.

## Performance

The standalone benchmarks project is **outside the production reactor**, not a release artifact, and has no impact on library consumers. Install the reactor first. Run:

```sh
./mvnw -B -ntp -f benchmarks/pom.xml compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt -DincludeScope=runtime
java -cp "benchmarks/target/classes:$(cat benchmarks/target/classpath.txt)" org.openjdk.jmh.Main \
  '.*MemoryRecoveryBenchmark.*' -prof gc -rf json -rff benchmarks/target/jmh.json
```

Two JMH throughput benchmarks measure 100 captures or 100 claim/replay operations per invocation in volatile storage with a no-I/O handler. Fresh bounded state per invocation prevents unbounded histories. Invocation setup/GC can affect measurements; allocation statistics include setup overhead. CI uses a short smoke run (1 fork, 1 warmup, 2 measurement iterations), not an SLA or production benchmark. For comparisons use the default longer run on a stable machine with documented JDK, hardware and workload; do not compare noisy runner scores or extrapolate JDBC performance from memory. Load tests verify correctness instead of hard-coding timing thresholds other than bounded test deadlines.

```sh
python3 scripts/quality_report.py
python3 scripts/quality_report.py --strict
```

Report the actual results and outstanding release blockers. No publication, no new runtime dependency, no hidden fallback when persistence fails. The standalone consumer Maven checks reject JMH, PIT, SpotBugs, vendor drivers and test tooling in runtime dependencies.
