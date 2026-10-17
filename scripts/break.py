"""Break the code on purpose once, run the check, put the code back, and print the `broken:` line.

    python scripts/break.py --file <path> --find <text> --replace <text> --what "<what was broken>" \
        [--cwd <dir>] -- <command> [args...]

The break is one exact replacement in one file: --find must occur exactly once. The file's bytes are
kept in memory and written back as they were, line endings included, whatever the command does; git
is never used to revert, so unstaged work elsewhere is untouched. The command runs as an argument
list, with no shell: on Windows, `mvnw` runs as `mvnw.cmd`. A Maven command must clean, or an
incremental build can skip the broken source and pass. One break at a time: a second run while one
is active refuses.

It exits 0 only when the check failed with the break in and the file is back as it was, and prints
the record for the PR (specs/README.md): `broken: <what> → <what the check reported>`.

Each rule here is a defect the paper's record holds (docs/paper, Appendix F): an incremental compile
that skipped a break (D23-027), breaks run through cmd.exe into WSL's bash that never started Maven
(D26-056), `git checkout` over unstaged work (D21-007, D25-050), two breaks run together (D25-043).
"""
import argparse
import os
import re
import subprocess
import sys

# The lines a failed check reports, in order of preference: a JUnit or pytest summary, then an
# assertion, then a failure line. The last match of the first pattern found is the report.
REPORTED = [re.compile(p) for p in (
    r"Tests run: \d+, Failures: \d+, Errors: \d+.*",
    r"\d+ failed.*",
    r"(?:AssertionError|AssertionFailedError|ComparisonFailure)[:\s].*",
    r"(?:FAIL|FAILED|ERROR|BUILD FAILURE)\b.*",
)]
LOCK = ".break-active"


def reported(output):
    """The line a failed check's output is summed up by, or its last non-empty line."""
    lines = [l.strip() for l in output.splitlines() if l.strip()]
    for pattern in REPORTED:
        hits = [m.group(0) for l in lines if (m := pattern.search(l))]
        if hits:
            return hits[-1]
    return lines[-1] if lines else "no output"


def argv_for(command, cwd):
    """The command as it runs here: a relative path to the program taken from cwd (Windows looks
    for it from the caller's directory), and mvnw as mvnw.cmd on Windows, since a sh script needs a
    shell."""
    head = command[0]
    if os.sep in head or "/" in head:
        head = os.path.abspath(os.path.join(cwd, head))
    if os.name == "nt" and os.path.basename(head) == "mvnw":
        head += ".cmd"
    return [head, *command[1:]]


def refuse(message):
    print(f"break.py: {message}", file=sys.stderr)
    return 2


def main(args=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--file", required=True)
    p.add_argument("--find", required=True)
    p.add_argument("--replace", required=True)
    p.add_argument("--what", required=True, help="what was broken, for the broken: line")
    p.add_argument("--cwd", default=".")
    p.add_argument("command", nargs="+")
    a = p.parse_args(args)
    sys.stdout.reconfigure(encoding="utf-8")  # the arrow; a Windows console's default is cp1252

    if os.path.basename(a.command[0]) in ("mvnw", "mvn") and "clean" not in a.command:
        return refuse("a Maven check must clean, or the broken source may not be recompiled")
    with open(a.file, "rb") as f:
        original = f.read()
    find, replace = a.find.encode(), a.replace.encode()
    if original.count(find) != 1:
        return refuse(f"--find occurs {original.count(find)} times in {a.file}, want exactly once")
    lock = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", LOCK)
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        return refuse(f"another break is active ({os.path.normpath(lock)}); finish it, or delete the file")
    try:
        os.write(fd, a.file.encode())
        os.close(fd)
        with open(a.file, "wb") as f:
            f.write(original.replace(find, replace))
        try:
            run = subprocess.run(argv_for(a.command, a.cwd), cwd=a.cwd, capture_output=True, text=True,
                                 encoding="utf-8", errors="replace")
        except FileNotFoundError as e:
            return refuse(f"the command did not start: {e}")
    finally:
        with open(a.file, "wb") as f:
            f.write(original)
        os.remove(lock)
    with open(a.file, "rb") as f:
        if f.read() != original:
            return refuse(f"{a.file} is not back as it was: restore it by hand")
    output = run.stdout + run.stderr
    if run.returncode == 0:
        print(f"The check passed with the break in: it cannot see this break.\n  last line: {reported(output)}")
        return 1
    print(f"broken: {a.what} → {reported(output)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
