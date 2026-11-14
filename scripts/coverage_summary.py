"""Fail on a missing/empty aggregate report; report measured coverage without an arbitrary gate."""
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
counters = {c.attrib["type"]: (int(c.attrib["covered"]), int(c.attrib["missed"]))
            for c in root.findall("counter")}
print("### BatchRecover library coverage (unit + integration)")
print("| Metric | Covered | Total | Coverage |")
print("| --- | ---: | ---: | ---: |")
for metric in ("LINE", "BRANCH", "INSTRUCTION"):
    covered, missed = counters[metric]
    total = covered + missed
    if total <= 0 or covered <= 0:
        raise ValueError(f"Empty aggregate coverage for {metric}")
    print(f"| {metric} | {covered} | {total} | {covered / total:.1%} |")
