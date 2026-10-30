"""Turns JaCoCo's CSV report into a Markdown table for the GitHub Actions run summary."""
import csv
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
report = sys.argv[1] if len(sys.argv) > 1 else "target/site/jacoco/jacoco.csv"
rows = list(csv.DictReader(open(report, encoding="utf-8")))


def percent(missed, covered):
    total = missed + covered
    return f"{100 * covered / total:.1f}%" if total else "n/a"


by_package = defaultdict(lambda: [0, 0, 0, 0])  # line missed/covered, branch missed/covered
for row in rows:
    totals = by_package[row["PACKAGE"].removeprefix("com.abhishekraj.BookMySeat").lstrip(".") or "(root)"]
    totals[0] += int(row["LINE_MISSED"])
    totals[1] += int(row["LINE_COVERED"])
    totals[2] += int(row["BRANCH_MISSED"])
    totals[3] += int(row["BRANCH_COVERED"])

overall = [sum(t[i] for t in by_package.values()) for i in range(4)]
print("## Test coverage (JaCoCo)\n")
print(f"**Lines: {percent(overall[0], overall[1])}** · Branches: {percent(overall[2], overall[3])}\n")
print("| Package | Lines | Branches |")
print("|---|---|---|")
for name, t in sorted(by_package.items()):
    print(f"| `{name}` | {percent(t[0], t[1])} | {percent(t[2], t[3])} |")
