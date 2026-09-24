"""The dashboard's numbers move when they should and only then.

Each drift test builds a small git repository, one commit per merge, and runs spec-drift.py's
trajectory over it; the roadmap tests check the run order, the statuses and the next step. Needs
git and numpy.

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


def a_day(n, ticked=1, total=1, kinds=()):
    return dict(day=n, status="provisional", criteria=dict(ticked=ticked, total=total),
                prs=[dict(kind=k) for k in kinds], tracks=["A"], tracks_merged=[], track_work={"A": "work"})


class RoadmapTest(unittest.TestCase):
    """Where the dashboard says the work is: the run order plan.md gives, and what counts as done."""

    PLAN = """## Day-by-day specs

Days keep their numbers; they run in this order:

1. The record fix and the platform step (new day specs, numbered from 38).
2. Phase 3: Days 18, 19, 17, 20.
3. Phase 4: the profile endpoint out of Day 24 first, then Days 21, 22, 23, the rest of 24.
4. Phase 5: Days 26, 27, 25, 28. **Stop and evaluate.**
5. Phases 6–7 (Days 29–37): rewritten after the evaluation, or not started.
"""

    def test_a_spec_change_must_touch_a_spec(self):
        self.assertEqual(sd.kind("Spec change: every criterion is new or hold", "specs/criterion-kinds",
                                 ["specs/README.md", "specs/_template.md"]), "other")
        self.assertEqual(sd.kind("Day 16 spec change: checks", "day-16/spec-x", ["specs/day-16-cutover.md"]), "spec")
        self.assertEqual(sd.kind("Plan change: seam first", "plan/course", ["plan.md"]), "spec")
        self.assertEqual(sd.kind("Add spec-drift.py", "tooling/spec-drift-script", []), "other")

    def test_the_run_order_is_the_plans(self):
        order, stop = sd.run_order(self.PLAN, list(range(1, 38)))
        self.assertEqual(order[:17], list(range(1, 17)) + [sd.PLATFORM])
        self.assertEqual(order[17:29], [18, 19, 17, 20, 21, 22, 23, 24, 26, 27, 25, 28])
        self.assertEqual(order[29:], list(range(29, 38)))
        self.assertEqual(stop, 28)
        self.assertEqual(sd.run_order(self.PLAN, list(range(1, 40)))[0][16:18], [38, 39])

    def test_the_repositorys_plan_parses(self):
        with open(os.path.join(HERE, os.pardir, "plan.md"), encoding="utf-8") as f:
            order, stop = sd.run_order(f.read(), list(range(1, 38)))
        self.assertEqual(sorted(d for d in order if d != sd.PLATFORM), list(range(1, 38)))
        self.assertEqual(stop, 28)

    def test_a_higher_number_does_not_finish_a_day_that_runs_later(self):
        days = sd.settle([a_day(17), a_day(18, kinds=["code"])], [18, 17])
        self.assertEqual([d["status"] for d in days], ["provisional", "active"])  # 17 waits for 18

    def test_a_finished_day_with_boxes_open_is_closed_not_done(self):
        days = sd.settle([a_day(1, ticked=0, total=7), a_day(2), a_day(3, kinds=["code"])], [1, 2, 3])
        self.assertEqual([d["status"] for d in days], ["closed", "done", "active"])

    def test_the_next_step_is_the_platform_then_the_stop(self):
        days = sd.settle([a_day(16, kinds=["close"]), a_day(17)], [16, sd.PLATFORM, 17])
        self.assertIn("platform step", sd.next_step(days, [], [16, sd.PLATFORM, 17], None))
        days = sd.settle([a_day(28, kinds=["close"]), a_day(29)], [28, 29])
        self.assertIn("stop and evaluate", sd.next_step(days, [], [28, 29], 28))

    def test_numbered_tracks_are_their_own_and_a_split_track_is_its_letter(self):
        tracks_section = """| Track | Owner | Work |
|---|---|---|
| 0 | | Work |
| A1 | | Work |
| A2 | | Work |
| B | | Work |"""
        self.assertEqual(sd.track_names(tracks_section), ["0", "A1", "A2", "B"])
        self.assertEqual(sd.merged_tracks(["day-39/track-0-x", "day-39/track-a1-key"], ["0", "A1", "A2", "B"]),
                         ["0", "A1"])
        self.assertEqual(sd.merged_tracks(["day-15/track-c1-rate", "day-15/track-c2-cors", "day-38/track-c-fix-harness"],
                                          ["0", "A", "B", "C", "D"]),
                         ["C"])
        self.assertEqual(sd.merged_tracks(["day-39/spec-written"], ["0", "A1", "A2", "B"]), [])


if __name__ == "__main__":
    unittest.main()
