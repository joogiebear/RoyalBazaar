#!/usr/bin/env python3
"""Generate grouped release notes from conventional commits in a git range.

Usage: changelog.py <version> [<git-range>]
Prints Markdown to stdout.
"""
import subprocess
import sys
import re
import datetime

# Emit UTF-8 regardless of the platform locale (Windows consoles default cp1252).
try:
    sys.stdout.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

version = sys.argv[1]
rng = sys.argv[2] if len(sys.argv) > 2 else "HEAD"

log = subprocess.run(
    ["git", "log", rng, "--no-merges", "--pretty=format:%s%x1f%h"],
    capture_output=True, text=True,
).stdout.strip()

# Ordered display groups
GROUPS = [
    ("feat", "✨ Features"),
    ("fix", "\U0001f41b Fixes"),
    ("perf", "⚡ Performance"),
    ("refactor", "♻️ Refactors"),
    ("docs", "\U0001f4dd Documentation"),
    ("other", "\U0001f527 Other"),
]
# Conventional types intentionally hidden from the changelog
SKIP = {"chore", "ci", "build", "test", "style"}

buckets = {key: [] for key, _ in GROUPS}
pat = re.compile(r"^(\w+)(\([^)]*\))?(!)?:\s*(.+)$")

for line in log.splitlines():
    if not line.strip():
        continue
    subj, _, sha = line.partition("\x1f")
    m = pat.match(subj)
    if m:
        typ = m.group(1).lower()
        desc = m.group(4)
        if typ in SKIP:
            continue
        key = typ if typ in buckets else "other"
    else:
        key, desc = "other", subj
    buckets[key].append(f"- {desc} (`{sha}`)")

date = datetime.date.today().isoformat()
out = [f"## {version} — {date}", ""]
has_entries = False
for key, title in GROUPS:
    if buckets[key]:
        has_entries = True
        out.append(f"### {title}")
        out.extend(buckets[key])
        out.append("")
if not has_entries:
    out.append("_Maintenance release._")
    out.append("")

print("\n".join(out))
