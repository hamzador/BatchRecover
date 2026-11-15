"""Summarize actual tool reports; strict mode is the prepublication gate."""
import argparse
from collections import Counter
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("batchrecover-core", "batchrecover-storage-memory", "batchrecover-storage-jdbc",
           "batchrecover-spring-batch", "batchrecover-spring-boot-starter")
TARGETS = {"LINE": 95, "INSTRUCTION": 95, "BRANCH": 90}
BASELINE = {"LINE": 95.0, "INSTRUCTION": 94.8, "BRANCH": 74.3}


def coverage(root):
    doc = ET.parse(root / "batchrecover-coverage/target/site/jacoco-aggregate/jacoco.xml").getroot()
    result = {}
    for counter in doc.findall("counter"):
        covered, missed = (int(counter.attrib[key]) for key in ("covered", "missed"))
        if covered + missed:
            result[counter.attrib["type"]] = 100 * covered / (covered + missed)
    if not all(key in result for key in TARGETS):
        raise ValueError("Missing aggregate coverage metrics")
    return result


def mutation(root):
    doc = ET.parse(root / "batchrecover-core/target/pit-reports/mutations.xml").getroot()
    counts = Counter(node.attrib["status"] for node in doc.findall("mutation"))
    if not counts:
        raise ValueError("Mutation report contains no mutations")
    return counts


def spotbugs(root):
    reports = {}
    for module in MODULES:
        doc = ET.parse(root / module / "target/spotbugsXml.xml").getroot()
        bugs = doc.findall(".//BugInstance")
        # Analyzer errors/missing classes must be visible, not interpreted as a clean scan.
        errors = doc.findall(".//Error") + doc.findall(".//MissingClass")
        if errors:
            raise ValueError(f"Incomplete SpotBugs analysis in {module}: {len(errors)} errors/missing classes")
        reports[module] = Counter(bug.attrib.get("priority", "unknown") for bug in bugs)
    return reports


def benchmarks(root):
    rows = json.loads((root / "benchmarks/target/jmh.json").read_text())
    if {row["benchmark"].rsplit(".", 1)[-1] for row in rows} != {"captureBatch", "replayBatch"}:
        raise ValueError("Missing JMH benchmark results")
    for row in rows:
        metric = row["primaryMetric"]
        if not 0 < metric["score"] < float("inf") or metric["scoreUnit"] != "ops/s":
            raise ValueError("Invalid benchmark throughput")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true", help="Fail release targets, not just regressions")
    args = parser.parse_args()
    cov = coverage(ROOT)
    mutations = mutation(ROOT)
    bugs = spotbugs(ROOT)
    measured = benchmarks(ROOT)
    failures = []
    print("### Quality hardening")
    for key, target in TARGETS.items():
        print(f"- {key}: {cov[key]:.2f}% (target {target}%)")
        if cov[key] + 1e-9 < (target if args.strict else BASELINE[key]):
            failures.append(f"{key} below {'release target' if args.strict else 'Sprint 7 baseline'}")
    score = 100 * mutations.get("KILLED", 0) / sum(mutations.values())
    print(f"- PIT killed mutation score: {score:.2f}% ({dict(mutations)}; target 85%)")
    if score < 85:
        print("::warning::PIT target not yet reached")
        if args.strict:
            failures.append("Mutation score below 85%")
    if any(mutations.get(key, 0) for key in ("RUN_ERROR", "MEMORY_ERROR", "NON_VIABLE")):
        failures.append("Mutation analysis has execution errors")
    for module, counts in bugs.items():
        print(f"- SpotBugs {module}: {sum(counts.values())} findings (priorities {dict(counts)})")
        if counts:
            print(f"::warning::{module} SpotBugs findings require review")
            if args.strict:
                failures.append(f"Unreviewed SpotBugs findings in {module}")
    for row in measured:
        metric = row["primaryMetric"]
        print(f"- JMH {row['benchmark'].rsplit('.', 1)[-1]}: {metric['score']:.0f} {metric['scoreUnit']}")
    for failure in failures:
        print(f"::error::{failure}")
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
