"""The dashboard's numbers move when they should and only then.

Each test builds a small git repository, one commit per merge, and runs spec-drift.py's
trajectory over it. Needs git and numpy.

    python scripts/test_spec_drift.py
"""
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("spec_drift", os.path.join(HERE, "spec-drift.py"))
sd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sd)

PLAN = "# Plan\n\nThe plan names `FooService` and `bar_table`.\n"
DAY = "# Day 01\n\nThe day adds `FooService`, then the rest of the day.\n"


class Repo:
    def __init__(self):
        self.path = tempfile.mkdtemp(prefix="spec-drift-test-")
        self.git("init", "-q")
        self.snaps = []

    def git(self, *args):
        return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "core.autocrlf=false",
                               *args], cwd=self.path, capture_output=True, text=True, check=True).stdout

    def merge(self, files):
        """One commit, standing in for one merge on main."""
        for name, text in files.items():
            full = os.path.join(self.path, name)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", f"merge {len(self.snaps)}")
        n = len(self.snaps)
        self.snaps.append(dict(sha=self.git("rev-parse", "--short", "HEAD").strip(), date="2026-09-20",
                               pr=n or None, title=f"Day 01 track A: {n}", branch="day-01/track-a-x" if n else ""))
        return self

    def rows(self):
        cwd = os.getcwd()
        os.chdir(self.path)
        blobs = sd.Blobs()
        try:
            return sd.trajectory([dict(s) for s in self.snaps], blobs)[0]
        finally:
            blobs.proc.stdin.close()
            blobs.proc.stdout.close()
            blobs.proc.wait()
            os.chdir(cwd)

    def close(self):
        shutil.rmtree(self.path, ignore_errors=True)


class SpecDriftTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo().merge({"plan.md": PLAN, "specs/day-01-x.md": DAY, "src/App.java": "class App {}\n"})

    def tearDown(self):
        self.repo.close()

    def test_code_that_names_a_spec_name_narrows_the_gap(self):
        rows = self.repo.merge({"src/Foo.java": "class FooService {}\n"}).rows()
        self.assertEqual(rows[0]["gap_full"], 90.0)
        self.assertLess(rows[1]["gap_full"], 90.0)

    def test_a_comment_naming_a_spec_name_is_not_code(self):
        rows = self.repo.merge({"src/Foo.java": "// FooService comes later\n /* FooService */\n",
                                "src/V1.sql": "-- bar_table comes later\nSELECT 1; -- bar_table\n",
                                "run.sh": "# FooService\n"}).rows()
        self.assertEqual(rows[1]["gap_full"], 90.0)

    def test_a_later_spec_name_does_not_move_an_earlier_gap(self):
        self.repo.merge({"src/Foo.java": "class FooService { BazClient baz; }\n"})
        before = self.repo.rows()[1]["gap_full"]
        self.repo.merge({"specs/day-01-x.md": DAY + "It calls `BazClient`.\n"})
        self.assertEqual(self.repo.rows()[1]["gap_full"], before)

    def test_drift_ignores_words_every_file_uses_and_sees_new_ones(self):
        rows = self.repo.merge({"specs/day-01-x.md": DAY + "the " * 200 + "\n"}).rows()
        self.assertEqual(rows[1]["drift"], 0.0)
        rows = self.repo.merge({"specs/day-01-x.md": DAY + "gateway routes the login\n"}).rows()
        self.assertGreater(rows[2]["drift"], 0.0)


if __name__ == "__main__":
    unittest.main()
