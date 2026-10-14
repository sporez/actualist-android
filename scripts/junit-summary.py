#!/usr/bin/env python3
"""Print a Markdown summary of JUnit XML results found under the given directories."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
rows = []
for root in sys.argv[1:]:
    for path in glob.glob(os.path.join(root, "**", "*.xml"), recursive=True):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        if suite.tag != "testsuite":
            continue
        counts = {key: int(suite.get(key, 0)) for key in totals}
        for key in totals:
            totals[key] += counts[key]
        rows.append((suite.get("name", "?"), counts))

print("## JVM unit tests\n")
print(f"**{totals['tests']} tests, {totals['failures']} failures, {totals['errors']} errors, {totals['skipped']} skipped**\n")
print("| Suite | Tests | Failures | Errors | Skipped |")
print("|---|---|---|---|---|")
for name, counts in sorted(rows):
    print(f"| {name.split('.')[-1]} | {counts['tests']} | {counts['failures']} | {counts['errors']} | {counts['skipped']} |")
