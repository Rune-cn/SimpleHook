#!/usr/bin/env python3
"""Prints a compact summary of the JVM unit-test results and writes it to the job summary.

Usage:
    python3 scripts/summarize_tests.py [results-dir] [summary-file]

Both arguments are optional; they default to the Gradle locations used by CI. The script
always exits 0: it only reports, the Gradle task decides whether the build failed.
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_RESULTS = "app/build/test-results/testRootDebugUnitTest/*.xml"


def main() -> int:
    results = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RESULTS
    summary_file = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("GITHUB_STEP_SUMMARY")

    total = failures = errors = skipped = 0
    rows: list[tuple[str, int, int]] = []

    for path in sorted(glob.glob(results)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"skip {path}: {error}")
            continue
        tests = int(root.get("tests", 0))
        failed = int(root.get("failures", 0)) + int(root.get("errors", 0))
        total += tests
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        rows.append((root.get("name", os.path.basename(path)), tests, failed))

    for name, tests, failed in rows:
        marker = "FAIL" if failed else "ok  "
        suffix = f", {failed} failed" if failed else ""
        print(f"{marker} {name}: {tests} test(s){suffix}")

    line = f"total={total} failed={failures + errors} skipped={skipped}"
    print(line)

    if summary_file:
        try:
            with open(summary_file, "a", encoding="utf-8") as handle:
                handle.write(f"```\n{line}\n```\n")
        except OSError as error:
            print(f"cannot write summary: {error}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
