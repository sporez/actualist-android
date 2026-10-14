#!/usr/bin/env python3
"""Summarize raw `am instrument -r` output. Exit 1 on any failure, crash or empty run."""
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()

tests = []
current = {}
for line in text.splitlines():
    match = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
    if match:
        current[match.group(1)] = match.group(2)
        continue
    match = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
    if match:
        code = int(match.group(1))
        if code != 1 and "test" in current:  # 1 = test started
            tests.append((current.get("class", "?"), current.get("test", "?"), code))
        current = {}

passed = [t for t in tests if t[2] == 0]
failed = [t for t in tests if t[2] in (-1, -2)]
ignored = [t for t in tests if t[2] in (-3, -4)]
crashed = "INSTRUMENTATION_RESULT: shortMsg=Process crashed" in text or "INSTRUMENTATION_FAILED" in text
ok_line = re.search(r"^OK \((\d+) tests?\)", text, re.M)

print("## Emulator instrumentation results\n")
print(f"- Passed: {len(passed)}")
print(f"- Failed: {len(failed)}")
print(f"- Ignored/assumption failures: {len(ignored)}")
print(f"- Runner OK line: {ok_line.group(0) if ok_line else 'missing'}")
for cls, name, _ in passed:
    print(f"  - PASS `{cls.split('.')[-1]}.{name}`")
for cls, name, _ in failed:
    print(f"  - FAIL `{cls.split('.')[-1]}.{name}`")
if crashed:
    print("\n**Instrumentation crashed or failed to run.**")

if crashed or failed or not passed or not ok_line:
    sys.exit(1)
