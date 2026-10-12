"""Fail a pull request that edits what the migration must not change, and name each file.

The applied migrations (any V*__*.sql under a services/**/db/ directory), identity's contract tests
and the Day 01 harness self-tests are the migration's safety net: a migration that has run is never
changed, and the Day 1-4 suite proves the behaviour the split has to keep. Additions and pure renames
pass, so a move stays possible; a modification, a deletion, a type change or a rename with an edit
fails. Reads the same three-dot diff as the size check in pr-checks.yml.

    python scripts/invariants.py <base> <head>
"""
import re
import subprocess
import sys

IDENTITY_TESTS = r"^services/identity-service/(.+/)?src/test/java/nl/hackyourfuture/project/backend/"
PROTECTED = tuple(re.compile(p) for p in (
    r"^services/.+/db/(.+/)?V[^/]*__[^/]*\.sql$",
    IDENTITY_TESTS + r"contract/",
    IDENTITY_TESTS + r"support/TestHarnessTest\.java$",
    IDENTITY_TESTS + r"BackendApplicationTests\.java$",
))
WORDS = {"M": "modified", "D": "deleted", "T": "type changed"}


def protected(path):
    return any(p.search(path) for p in PROTECTED)


def entries(name_status_lines):
    """(code, status, similarity, old path, new path) per line of `git diff --name-status -M`."""
    for line in name_status_lines:
        if not line.strip():
            continue
        fields = line.split("\t")
        code = fields[0]
        new = fields[2] if len(fields) > 2 else fields[1]
        yield code, code[0], int(code[1:] or 100), fields[1], new


def violations(name_status_lines):
    """One line per protected file that is modified, deleted, retyped or renamed with an edit."""
    found = []
    for code, status, _, old, new in entries(name_status_lines):
        if status == "R" and code != "R100" and protected(old):
            found.append(f"renamed with an edit ({code}): {old} -> {new}")
        elif status in WORDS and protected(old):
            found.append(f"{WORDS[status]}: {old}")
    return found


def passed(name_status_lines):
    """How many protected files the diff adds or moves as they are: the count the OK line prints."""
    count = 0
    for code, status, _, old, new in entries(name_status_lines):
        if status == "A" or status == "C" or code == "R100":
            count += protected(old) or protected(new)
    return count


def main(argv):
    if len(argv) != 2:
        print("usage: python scripts/invariants.py <base> <head>", file=sys.stderr)
        return 2
    base, head = argv
    diff = subprocess.run(
        ["git", "diff", "--name-status", "-M", "-l0", f"{base}...{head}"],
        capture_output=True, text=True, encoding="utf-8",
    )
    if diff.returncode:
        print(diff.stderr, file=sys.stderr)
        return 2
    lines = diff.stdout.splitlines()
    found = violations(lines)
    if found:
        for line in found:
            print(line)
        print("These files are frozen: add a new migration instead of editing an applied one. "
              "A Day 1-4 test edit means stop and ask (see CLAUDE.md).")
        return 1
    print(f"OK: no applied migration or Day 1-4 test was modified, deleted or renamed with an edit "
          f"({passed(lines)} protected files added or moved unchanged).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
