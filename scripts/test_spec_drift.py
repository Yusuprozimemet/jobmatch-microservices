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
        return self.inside(lambda blobs: sd.trajectory([dict(s) for s in self.snaps], blobs)[0])

    def inside(self, fn):
        """fn(blobs), run in the repository."""
        cwd = os.getcwd()
        os.chdir(self.path)
        blobs = sd.Blobs()
        try:
            return fn(blobs)
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


CRITERIA = """- [x] **hold** — Refused: `contract/FooIT` passes. Broken on purpose: the check removed.
      #2: `FooIT.refuses`. Red with the guard removed (`expected: 401 but was: 200`).
- [x] **new** — Saved in `BarIT`, or a case added to `FooIT`. Red today: no table.
      #3: a case in `FooIT.saves`.
- [ ] **new** — Not started.
"""


class EvidenceTest(unittest.TestCase):
    """A closed day's evidence: what each criterion cites, and whether its tests are still there."""

    def setUp(self):
        # The day's PR is #1, the second commit; later commits stand for later days.
        self.repo = Repo().merge({"plan.md": PLAN}).merge(
            {"src/FooIT.java": "class FooIT { void refuses() {} void saves() {} }\n"})

    def tearDown(self):
        self.repo.close()

    def state(self):
        days = [dict(day=1, status="done", prs=[dict(number=1)], evidence=sd.criteria(CRITERIA))]
        return self.repo.inside(lambda blobs: sd.evidence(days, self.repo.snaps, blobs))[0]["evidence"]

    def test_a_criterion_is_read_for_its_kind_prs_tests_and_break(self):
        hold, new, open_ = sd.criteria(CRITERIA)
        self.assertEqual((hold["kind"], hold["ticked"], hold["prs"], hold["red"]), ("hold", True, [2], True))
        self.assertEqual(hold["tests"], ["FooIT", "FooIT.refuses"])
        self.assertEqual((new["red"], open_["ticked"], open_["prs"]), (False, False, []))
        self.assertTrue(hold["claim"].startswith("Refused: `contract/FooIT` passes."), hold["claim"])
        cut = sd.criteria("- [x] **hold** — " + "x" * 130 + " `a/LongNameIT`\n")[0]["claim"]
        self.assertEqual(cut.count("`"), 0)

    def test_a_break_seen_red_is_told_from_a_claim_that_it_is_red_today(self):
        seen = ["Red as named", "Red without the chain", "`savedJobs` went red", "turned two red, not one"]
        for text in seen:
            self.assertTrue(sd.criteria(f"- [x] **hold** — X. {text}.\n")[0]["red"], text)
        self.assertFalse(sd.criteria("- [x] **new** — X. Red today: the route does not exist.\n")[0]["red"])

    def test_a_name_not_in_the_tree_when_the_day_ended_is_not_evidence(self):
        self.assertEqual(self.state()[1]["tests"], {"FooIT": "present", "FooIT.saves": "present"})

    def test_a_test_that_leaves_the_tree_or_can_be_skipped_is_reported(self):
        self.repo.merge({"src/FooIT.java": "@Disabled class FooIT { void refuses() {} }\n"})
        self.assertEqual(self.state()[1]["tests"], {"FooIT": "skippable", "FooIT.saves": "missing"})
        self.repo.merge({"src/FooIT.java": ""})
        self.repo.git("rm", "-q", "src/FooIT.java")
        self.repo.git("commit", "-q", "-m", "gone")
        self.repo.snaps.append(dict(self.repo.snaps[-1], sha=self.repo.git("rev-parse", "--short", "HEAD").strip()))
        self.assertEqual(set(self.state()[0]["tests"].values()), {"missing"})


    def test_the_history_starts_when_the_day_ends_and_shows_the_merge_a_test_left(self):
        self.repo.merge({"src/FooIT.java": "class FooIT { void refuses() {} }\n"})
        days = [dict(day=1, status="done", prs=[dict(number=1)], evidence=sd.criteria(CRITERIA))]
        rows = [{} for _ in self.repo.snaps]

        def history(blobs):
            sd.evidence_history(rows, sd.evidence(days, self.repo.snaps, blobs), self.repo.snaps, blobs)
        self.repo.inside(history)
        self.assertEqual([r["evidence"] for r in rows], [
            None,
            dict(present=3, skippable=0, missing=0),
            dict(present=2, skippable=0, missing=1)])


REMOVALS = """## Acceptance criteria
- [x] **new** — `grep -rn "OldThing\\|old_table" --include=*.java .`
      returns nothing. Red today: two.

## Verify
```bash
cd backend
grep -rn "TODO day-01" */src/ || echo "clean"
grep -rn "kept" app/ | wc -l
```

## Notes
- `grep -rn "NotACheck"` returns nothing, but a note is not a check.
"""


class RemovalTest(unittest.TestCase):
    """A day's greps that must print nothing, run at every merge from the day's end."""

    def test_the_checks_are_the_criteria_and_verify_greps_that_expect_nothing(self):
        self.assertEqual(sd.removal_checks(REMOVALS), ['grep -rn "OldThing\\|old_table" --include=*.java .',
                                                       'grep -rn "TODO day-01" */src/'])

    def test_a_basic_regex_alternation_is_an_alternation_and_a_bare_bar_is_literal(self):
        rx, paths, include = sd.grep_rule('grep -rn "OldThing\\|old_table" --include=*.java .')
        self.assertEqual((bool(rx.search("old_table")), paths, include), (True, ["."], "*.java"))
        self.assertFalse(sd.grep_rule('grep -rn "a|b" x/')[0].search("a"))
        self.assertTrue(sd.grep_rule('grep -rnE "a|b" x/')[0].search("a"))
        self.assertIsNone(sd.grep_rule('grep -rn "x" a/ | grep -v b'))

    def test_a_check_counts_lines_in_its_scope_from_the_day_it_ended(self):
        repo = Repo().merge({"backend/app/A.java": "OldThing a;\nold_table b;\n", "backend/app/B.txt": "OldThing\n",
                             "other/C.java": "OldThing c;\n", "backend/app/src/D.java": "",
                             "backend/pom.txt": "TODO day-01\n"})
        repo.merge({"backend/app/A.java": "class A {}\n"})
        repo.merge({"backend/app/A.java": "class A { OldThing back; }\n", "backend/app/src/D.java": "// TODO day-01\n"})
        def run(ended_at):
            days = [dict(day=1, ended_at=ended_at, removal_checks=sd.removal_checks(REMOVALS))]
            return [(c["base"], c["hits"]) for c in repo.inside(lambda blobs: sd.removal_history(days, repo.snaps, blobs))]
        try:
            # B.txt is not *.java, other/ is not under backend/, and pom.txt is not under */src/.
            self.assertEqual(run(0), [("backend", [2, 0, 1]), ("backend", [0, 0, 1])])
            self.assertEqual(run(1), [("backend", [0, 1]), ("backend", [0, 1])])
        finally:
            repo.close()


class HandOffTest(unittest.TestCase):
    """What finished days leave to days not yet run, and whether the receiving spec took it."""

    def test_an_excerpt_ends_at_a_word_and_outside_a_code_span(self):
        self.assertEqual(sd.excerpt("alpha  beta\n gamma", 40), "alpha beta gamma")
        self.assertEqual(sd.excerpt("alpha beta gamma", 12), "alpha beta…")
        self.assertEqual(sd.excerpt("see `foo bar baz` now", 12), "see…")

    def test_the_days_a_sentence_names(self):
        self.assertEqual(sd.days_named("Day 17 adds it; Days 18-19 and Days 19, 21 and 24 build theirs"),
                         {17, 18, 19, 21, 24})
        self.assertEqual(sd.days_named("Days 26–28, or Day 30"), {26, 27, 28, 30})

    def test_a_hand_off_is_picked_up_by_the_spec_that_names_its_day_or_its_file(self):
        repo = Repo().merge({
            "specs/day-01-a.md": "## Notes\n- For Day 03: the key.\n- Day 02's findings for Day 04 are there.\n"
                                 "- Day 05 has nothing from this day.\n",
            "specs/day-02-b.md": "# Day 02\n",
            "specs/day-03-c.md": "# Day 03\n\nFrom Day 01: the key.\n",
            "specs/day-04-d.md": "# Day 04\n\nFrom Day 02.\n",
            "specs/day-05-e.md": "# Day 05\n\nIt reads `Foo`.\n",
            "src/Foo.java": "// Day 05 moves this.\nclass Foo {} // Day 05\n",
            "src/Bar.java": "/* Day 04 deletes this. */\n",
        })
        days = [dict(day=n, file=f"specs/day-0{n}-{c}.md", status="done" if n < 3 else "ready")
                for n, c in zip(range(1, 6), "abcde")]
        try:
            found = repo.inside(lambda blobs: sd.hand_offs(days, [1, 2, 3, 4, 5], repo.snaps[-1]["sha"], blobs))
        finally:
            repo.close()
        self.assertEqual([(h["to"], h["source"], h["picked"]) for h in found], [
            (3, "Day 01 Notes", True),          # in run order, open ones first within a day
            (4, "src/Bar.java:1", False),       # Day 04 never names Bar
            (4, "Day 01 Notes", True),          # points to Day 02, which Day 04 names
            (5, "Day 01 Notes", False),
            (5, "src/Foo.java:1", True)])       # the code line after it is not a comment
        self.assertEqual(found[4]["text"], "Day 05 moves this.")


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
