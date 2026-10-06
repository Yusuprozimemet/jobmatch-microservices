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
        try:
            with sd.Blobs() as blobs:
                return fn(blobs)
        finally:
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
        self.assertIsNone(rows[0]["gap_full"])  # no spec name in the code: no direction to compare
        self.assertLess(rows[1]["gap_full"], 90.0)

    def test_name_coverage_is_the_share_of_spec_names_the_code_has(self):
        rows = self.repo.merge({"src/Foo.java": "class FooService { FooService f; }\n"}).rows()
        self.assertEqual((rows[0]["name_coverage"], rows[1]["name_coverage"]), (0.0, 0.5))  # bar_table not yet
        rows = self.repo.merge({"src/Bar.java": "bar_table b;\n"}).rows()
        self.assertEqual(rows[2]["name_coverage"], 1.0)
        rows = self.repo.merge({"plan.md": "# Plan\n\nThe plan names nothing.\n"}).rows()
        self.assertEqual(rows[3]["name_coverage"], 1.0)  # bar_table, in the code only, is not asked for

    def test_jaccard_counts_a_name_the_specs_dropped_but_the_code_keeps(self):
        rows = self.repo.merge({"src/Foo.java": "class FooService { bar_table b; }\n"}).rows()
        self.assertEqual((rows[1]["name_coverage"], rows[1]["jaccard"]), (1.0, 1.0))
        rows = self.repo.merge({"plan.md": "# Plan\n\nThe plan names nothing.\n"}).rows()
        self.assertEqual((rows[2]["name_coverage"], rows[2]["jaccard"]), (1.0, 0.5))  # bar_table: code only
        self.assertEqual((rows[1]["names_kept"], rows[2]["names_kept"]), (0, 1))

    def test_a_failed_blob_read_names_the_commit_and_the_path(self):
        sha = self.repo.snaps[0]["sha"]

        def read_after_git_dies(blobs):
            self.assertEqual(blobs.read(sha, "no/such/file"), "")  # missing is not a failure
            blobs.proc.kill()
            blobs.proc.wait()
            return blobs.read(sha, "plan.md")
        with self.assertRaises(RuntimeError) as failed:
            self.repo.inside(read_after_git_dies)
        self.assertIn(f"plan.md at {sha}", str(failed.exception))

    def test_a_run_from_the_cache_reads_what_a_fresh_one_does(self):
        # Merge 1 is cached before any spec names BazClient; merge 2's spec does, which shifts
        # every name's place in the vocabulary.
        self.addCleanup(sd.CACHE.clear)
        self.repo.merge({"src/Baz.java": "class BazClient { FooService f; }\n"})
        sd.CACHE.clear()
        self.repo.rows()
        self.repo.merge({"specs/day-01-x.md": DAY + "It calls `BazClient`.\n", "src/Use.java": "BazClient b;\n"})
        calls, real = [], sd.run
        sd.run = lambda *a: calls.append(a) or real(*a)
        try:
            cached = self.repo.rows()
            sd.CACHE.clear()
            fresh_from = len(calls)
            fresh = self.repo.rows()
        finally:
            sd.run = real
        self.assertEqual(cached, fresh)
        self.assertLess(fresh_from, len(calls) - fresh_from)

    def test_a_comment_naming_a_spec_name_is_not_code(self):
        rows = self.repo.merge({"src/Foo.java": "// FooService comes later\n /* FooService */\n",
                                "src/V1.sql": "-- bar_table comes later\nSELECT 1; -- bar_table\n",
                                "run.sh": "# FooService\n"}).rows()
        self.assertIsNone(rows[1]["gap_full"])

    def test_a_later_spec_name_does_not_move_an_earlier_gap(self):
        self.repo.merge({"src/Foo.java": "class FooService { BazClient baz; }\n"})
        before = self.repo.rows()[1]["gap_full"]
        self.repo.merge({"specs/day-01-x.md": DAY + "It calls `BazClient`.\n"})
        self.assertEqual(self.repo.rows()[1]["gap_full"], before)

    def test_each_merge_counts_its_own_lines_by_box_and_a_move_only_what_changed(self):
        rows = self.repo.merge({"src/test/FooTest.java": "a\nb\n", "CLAUDE.md": "c\n", "specs/day-01-x.md": DAY + "d\n",
                                "scripts/x.py": "e\n", "package-lock.json": "{}\n", "src/Big.java": "f\ng\nh\ni\n"}).rows()
        self.assertEqual(rows[1]["work"], dict(spec=1, context=1, prod=4, test=2, tooling=1))
        os.remove(os.path.join(self.repo.path, "src", "Big.java"))
        rows = self.repo.merge({"src/test/BigTest.java": "f\ng\nh\ni\nj\n"}).rows()
        self.assertEqual(rows[2]["work"], dict(spec=0, context=0, prod=0, test=1, tooling=0))

    def test_a_file_goes_in_the_first_box_its_path_matches(self):
        for path, box in [("plan.md", "spec"), ("specs/README.md", "spec"), ("specs/day-01-test-harness.md", "spec"),
                          ("CLAUDE.md", "context"), ("backend/app/README.md", "context"), (".claude/agents/x.md", "context"),
                          (".claude/settings.json", "context"), ("docs/workflow.svg", "context"),
                          ("backend/app/src/test/java/x/support/Harness.java", "test"), ("services/job/src/FooIT.java", "test"),
                          ("scripts/test_spec_drift.py", "test"), ("data/tests/conftest.py", "test"), ("web/a.spec.ts", "test"),
                          ("scripts/spec-drift.py", "tooling"), (".github/workflows/ci.yml", "tooling"),
                          ("docs/dashboard/dashboard.js", "tooling"), ("backend/app/src/main/java/x/Latest.java", "prod"),
                          ("docker-compose.yml", "prod"), ("docs/dashboard/token-usage.json", None),
                          ("frontend/package-lock.json", None), ("screenshots/a.png", None)]:
            self.assertEqual(sd.work_kind(path), box, path)
        self.assertEqual(sd.renamed_to("backend/{app => job}/src/X.java"), "backend/job/src/X.java")
        self.assertEqual(sd.renamed_to("src/{ => job}/X.java"), "src/job/X.java")
        self.assertEqual(sd.renamed_to("a.txt => b/a.txt"), "b/a.txt")

    def test_a_pr_records_a_break_in_one_fixed_line_and_nothing_else(self):
        checklist = "- [x] Spec changes only: each check has been seen to fail (see `specs/README.md`)\n"
        for body, state in [("broken: `ISSUER` back → `AuthIT` failed 2 of 8\n", "recorded"),
                            ("- broken: the guard removed -> expected: 401 but was: 200\n", "recorded"),
                            ("broken: none → this track adds no test\nbroken: the sort reversed → 3 red\n", "recorded"),
                            ("broken: none → this track adds no test\n", "none"),
                            # The free text the regexes read before #310 is not the line.
                            ("**Broken on purpose:** `ISSUER` back. It fails 2 of 8.\n", "silent"),
                            ("Seen red, broken: the guard removed → it failed\n", "silent"),
                            ("broken: the guard removed\n", "silent"),
                            ("", "silent"), (None, "silent")]:
            self.assertEqual(sd.break_state(body and body + checklist, 310), state, body)
        self.assertEqual(sd.break_state(checklist, 310), "silent")
        self.assertEqual(sd.break_state("broken: the guard removed → 401\n", 309), "not measured")

    def test_ci_counts_each_pushed_commit_once_and_only_pull_request_runs(self):
        run = lambda sha, result, flow="PR checks", event="pull_request": dict(
            headBranch="day-01/track-a-x", headSha=sha, conclusion=result, workflowName=flow, event=event)
        runs = [run("a", "failure"), run("a", "failure", "Backend CI/CD"), run("a", "success", "Dashboard"),
                run("b", "success"), run("b", "cancelled", "Backend CI/CD"), run("c", "failure", event="push")]
        self.assertEqual(sd.ci_pushes(runs), {"day-01/track-a-x": (2, 1, ["Backend CI/CD", "PR checks"])})

    def test_a_gh_list_as_long_as_its_limit_is_refused(self):
        real = sd.run
        try:
            sd.run = lambda *args: "[" + ",".join(["{}"] * n) + "]"
            n = 2
            self.assertEqual(len(sd.gh_list(3, "pr", "list")), 2)
            n = 3
            with self.assertRaisesRegex(SystemExit, "pr list returned its limit of 3"):
                sd.gh_list(3, "pr", "list")
        finally:
            sd.run = real

    def test_the_data_names_the_commit_versions_and_draws_that_made_it(self):
        p = sd.provenance()
        self.assertRegex(p["commit"], "^[0-9a-f]{40}$")
        self.assertEqual((p["numpy"], p["chance_draws"]), (sd.np.__version__, sd.CHANCE_DRAWS))
        self.assertRegex(p["python"], r"^3\.\d+\.\d+")
        self.assertIsInstance(p["script_edited"], bool)

    def test_a_day_that_runs_later_is_not_reached_by_a_higher_number(self):
        self.repo.merge({"specs/day-17-x.md": "Day 17 adds `LaterThing`.\n",
                         "specs/day-38-x.md": "Day 38 adds `PlatformThing`.\n"})
        self.repo.merge({"src/P.java": "class PlatformThing { FooService foo; }\n"})
        self.repo.snaps[-1]["branch"] = "day-38/track-a-x"
        by_number = self.repo.rows()[-1]["coverage"]
        rows, files = self.repo.inside(lambda blobs: sd.trajectory([dict(s) for s in self.repo.snaps], blobs,
                                                                   [1, 38, 17])[:2])
        self.assertEqual((by_number, rows[-1]["coverage"]), (0.667, 1.0))  # LaterThing is Day 17's
        self.assertEqual(files, ["plan.md", "specs/day-01-x.md", "specs/day-38-x.md", "specs/day-17-x.md"])
        self.repo.merge({"src/L.java": "class LaterThing {}\n"})
        self.repo.snaps[-1]["branch"] = "day-17/track-a-y"
        rows = self.repo.inside(lambda blobs: sd.trajectory([dict(s) for s in self.repo.snaps], blobs, [1, 38, 17])[0])
        self.assertEqual(rows[-1]["day"], 17)  # after 38 in the run order, though lower in number

    def test_code_in_the_spec_proportions_sits_below_chance(self):
        rows = self.repo.merge({"src/Foo.java": "class FooService { FooService f; bar_table b; }\n"}).rows()
        self.assertEqual((rows[0]["gap_full"], rows[0]["gap_chance"]), (None, None))  # no code: no angle
        self.assertEqual(rows[1]["gap_full"], 0.0)  # FooService twice and bar_table once, as the specs
        self.assertGreater(rows[1]["gap_chance"], 10.0)
        lo, hi = rows[1]["gap_chance_band"]
        self.assertTrue(lo <= rows[1]["gap_chance"] <= hi)
        rows = self.repo.merge({"src/Foo.java": "class FooService { bar_table a; bar_table b; }\n"}).rows()
        self.assertGreater(rows[2]["gap_full"], rows[1]["gap_full"])
        self.assertEqual(rows[2]["gap_chance"], rows[1]["gap_chance"])  # the same counts, shuffled

CRITERIA = """- [x] **hold** — Refused: `contract/FooIT` passes. Broken on purpose: the check removed.
      #2: `FooIT.refuses`.
      broken: the guard removed → `expected: 401 but was: 200`
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

    def test_a_criterion_from_day_28_on_carries_its_id_and_an_older_one_none(self):
        tagged, untagged = sd.criteria("- [ ] C28.3 **new** — `FooIT` refuses.\n- [ ] **hold** — C28.3 holds.\n")
        self.assertEqual((tagged["id"], tagged["kind"], tagged["claim"]), ("C28.3", "new", "`FooIT` refuses."))
        self.assertEqual((untagged["id"], untagged["claim"]), (None, "C28.3 holds."))

    def test_a_criterion_is_seen_red_only_by_a_broken_line_with_what_it_reported(self):
        red = lambda text: sd.criteria(f"- [x] C28.1 **hold** — X. #2: `FooIT`.\n      {text}\n")[0]["red"]
        self.assertTrue(red("broken: the guard removed → `FooIT` failed 1 of 3"))
        self.assertTrue(red("broken: the sort reversed -> 31 of 31 red"))
        # A baseline, a break planned but not reported, a `none`, or the free text the regexes read
        # before #310 is not a break seen red.
        for text in ["Red today: the route does not exist.", "broken: the guard removed",
                     "broken: none → the grep is the check",
                     "Broken on purpose in #74: the auth tests reported `Tests run: 49, Failures: 8`.",
                     "Red again with the migration's revoke removed. broken: x → y"]:
            self.assertFalse(red(text), text)

    def test_a_spec_with_no_criterion_tagged_predates_the_evidence_format(self):
        self.assertFalse(sd.in_evidence_format(sd.criteria("- [x] Login returns 200.\n- [x] Logout clears it.\n")))
        self.assertTrue(sd.in_evidence_format(sd.criteria("- [x] Login returns 200.\n- [x] **hold** — X.\n")))
        self.assertTrue(sd.in_evidence_format(sd.criteria(CRITERIA)))

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

    def test_a_test_moved_under_a_new_name_is_followed_from_the_merge_it_arrives(self):
        # Day 17: SavedJobCountsUnavailableIT left in #174, and its Test arrived in #180. A helper
        # leaving as a test of its name arrives is not a move: only tests are followed.
        self.repo.merge({"src/Stub.java": "class Stub {}\n"})
        self.repo.git("rm", "-q", "src/FooIT.java", "src/Stub.java")
        self.repo.merge({"src/Other.java": "class Other {}\n"})
        self.repo.merge({"svc/FooTest.java": "class FooTest { void refuses() {} void saves() {} }\n",
                         "svc/StubTest.java": "class StubTest {}\n"})
        self.assertEqual(self.repo.inside(lambda blobs: sd.moves(self.repo.snaps)), [(4, "FooIT", "FooTest")])
        self.assertEqual(self.state()[1]["tests"], {"FooIT": "present", "FooIT.saves": "present"})

    def test_a_class_of_the_same_stem_there_before_the_test_left_is_not_where_it_went(self):
        self.repo.merge({"svc/FooTest.java": "class FooTest { void refuses() {} void saves() {} }\n"})
        self.repo.git("rm", "-q", "src/FooIT.java")
        self.repo.merge({"src/Other.java": "class Other {}\n"})
        self.assertEqual(self.repo.inside(lambda blobs: sd.moves(self.repo.snaps)), [])
        self.assertEqual(set(self.state()[1]["tests"].values()), {"missing"})

    def test_a_name_there_before_the_test_left_and_arriving_again_after_is_followed_to_the_later(self):
        # InternalCallsObservedTest: in matching-service from Day 21, then in application-service
        # in #305, after InternalCallsObservedIT left in #296.
        self.repo.merge({"svc/FooTest.java": "class FooTest { void other() {} }\n"})
        self.repo.git("rm", "-q", "src/FooIT.java")
        self.repo.merge({"src/Other.java": "class Other {}\n"})
        self.repo.merge({"app/FooTest.java": "class FooTest { void refuses() {} void saves() {} }\n"})
        self.assertEqual(self.repo.inside(lambda blobs: sd.moves(self.repo.snaps)), [(4, "FooIT", "FooTest")])
        self.assertEqual(self.state()[1]["tests"], {"FooIT": "present", "FooIT.saves": "present"})

    def test_a_move_to_another_stem_is_followed_only_by_the_rename_table(self):
        self.repo.git("rm", "-q", "src/FooIT.java")
        self.repo.merge({"svc/BarTest.java": "class BarTest { void refuses() {} void saves() {} }\n"})
        self.assertEqual(set(self.state()[1]["tests"].values()), {"missing"})
        renamed, sd.RENAMED = sd.RENAMED, {2: ("FooIT", "BarTest")}
        try:
            self.assertEqual(self.repo.inside(lambda blobs: sd.moves(self.repo.snaps)), [(2, "FooIT", "BarTest")])
            self.assertEqual(self.state()[1]["tests"], {"FooIT": "present", "FooIT.saves": "present"})
        finally:
            sd.RENAMED = renamed

    def test_a_test_run_on_a_system_property_is_run_when_ci_passes_it_and_disabled_is_not(self):
        on = '@EnabledIfSystemProperty(named = "harness.gateway", matches = "true")\n'
        self.repo.merge({"src/FooIT.java": on + "class FooIT { void refuses() {} void saves() {} }\n"})
        self.assertEqual(set(self.state()[1]["tests"].values()), {"skippable"})
        self.repo.merge({".github/workflows/ci.yml": "run: ./mvnw -B verify -Dharness.gateway=false\n"})
        self.assertEqual(set(self.state()[1]["tests"].values()), {"skippable"})
        self.repo.merge({".github/workflows/ci.yml": "run: ./mvnw -B verify -pl app -am -Dharness.gateway=true\n"})
        self.assertEqual(set(self.state()[1]["tests"].values()), {"present"})
        self.repo.merge({"src/FooIT.java": on + "@Disabled class FooIT { void refuses() {} void saves() {} }\n"})
        self.assertEqual(set(self.state()[1]["tests"].values()), {"skippable"})

    def test_the_history_counts_a_moved_test_missing_until_it_arrives(self):
        days = [dict(day=1, status="done", prs=[dict(number=1)], evidence=sd.criteria(CRITERIA))]
        self.repo.git("rm", "-q", "src/FooIT.java")
        self.repo.merge({"src/Other.java": "class Other {}\n"})
        self.repo.merge({"svc/FooTest.java": "class FooTest { void refuses() {} void saves() {} }\n"})
        rows = [{} for _ in self.repo.snaps]
        self.repo.inside(lambda blobs: sd.evidence_history(rows, sd.evidence(days, self.repo.snaps, blobs),
                                                           self.repo.snaps, blobs))
        self.assertEqual([r["evidence"] for r in rows][1:], [
            dict(present=3, skippable=0, missing=0),
            dict(present=0, skippable=0, missing=3),
            dict(present=3, skippable=0, missing=0)])


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

    def test_a_hand_off_with_an_id_is_picked_up_only_by_a_spec_citing_it(self):
        repo = Repo().merge({
            "specs/day-01-a.md": "## Notes\n- **Hand-off** H01.1 → Day 03: the key.\n"
                                 "- **Hand-off** H01.2 → Day 02: the queue.\n"
                                 "- **Hand-off** H01.3 → the maintainer: branch protection.\n"
                                 "- **Hand-off** H01.4 → the maintainer: the volume.\n",
            "specs/day-02-b.md": "# Day 02\n\nFrom Day 01: nothing cited.\n",
            "specs/day-03-c.md": "# Day 03\n\nFrom Day 01: the key.\n\n## Notes\n- H01.4 done by hand.\n",
            "specs/day-04-d.md": "# Day 04\n\nPicks up H01.1.\n",
            "src/Foo.java": "// Day 01 wrote this.\n",
        })
        days = [dict(day=n, file=f"specs/day-0{n}-{c}.md", status="done" if n < 3 else "ready")
                for n, c in zip(range(1, 5), "abcd")]
        try:
            found = repo.inside(lambda blobs: sd.hand_offs(days, [1, 2, 3, 4], repo.snaps[-1]["sha"], blobs))
        finally:
            repo.close()
        self.assertEqual([(h["to"], h["id"], h["picked"]) for h in found], [
            (3, "H01.1", False),                # Day 03 names Day 01, not the ID; Day 04 cites it
            (None, "H01.2", False),             # Day 02 finished without citing it
            (None, "H01.3", False)])            # no day takes it; H01.4 is cited by Day 03's Notes

    def test_a_track_a_pr_announces_that_no_branch_names_is_the_next_step(self):
        prs = [dict(number=298, headRefName="day-25/track-e1b-x", body="**E1c** (next). Not Day 21's E1d; `grep -A3`."),
               dict(number=297, headRefName="day-25/track-e1a-x", body="Track E1 splits; B1 stays."),
               dict(number=290, headRefName="day-25/track-b-x", body="B1 here")]
        self.assertEqual(sd.unopened_tracks(prs), [dict(track="E1c", pr=298)])
        self.assertEqual(sd.unopened_tracks(prs + [dict(number=305, headRefName="day-25/track-e1c-y", body="")]), [])
        day = dict(a_day(25), status="active", prs=[dict(kind="spec")], tracks_merged=["A"],
                   tracks_unopened=sd.unopened_tracks(prs))
        self.assertEqual(sd.next_step([day], [], [25], None), "Day 25 Track E1c: announced in #298, no branch names it")


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

    def test_the_days_out_of_order_are_those_that_break_the_rising_run(self):
        order = list(range(1, 17)) + [38, 39, 40, 18, 19, 17, 20, 21, 26, 27, 25, 28, sd.PLATFORM]
        self.assertEqual(sd.out_of_order(order), [38, 39, 40, 17, 25])
        self.assertEqual(sd.out_of_order(list(range(1, 10))), [])
        self.assertEqual(sd.out_of_order([]), [])

    def test_the_repositorys_plan_parses(self):
        with open(os.path.join(HERE, os.pardir, "plan.md"), encoding="utf-8") as f:
            plan = f.read()
        days = sorted({sd.day_of(n) for n in os.listdir(os.path.join(HERE, os.pardir, "specs")) if sd.day_of(n)})
        order, stop = sd.run_order(plan, days)
        self.assertEqual(sorted(d for d in order if d not in sd.PLACEHOLDERS), days)
        self.assertIsNone(stop)  # evaluated: plan.md has the course correction after Phase 5

    def test_the_cleanup_day_comes_after_the_stop_once_it_is_evaluated(self):
        plan = self.PLAN.replace("5. Phases 6–7 (Days 29–37): rewritten after the evaluation, or not started.",
                                 "5. The cleanup day: its spec is written next, numbered\n"
                                 "   after the last.\n"
                                 "6. Phases 6–7 (Days 29–37).") + "\n## Course correction after Phase 5\n\nCleanup first.\n"
        order, stop = sd.run_order(plan, list(range(1, 39)))
        self.assertIsNone(stop)
        self.assertEqual(order[order.index(28) + 1], sd.CLEANUP)
        days = sd.settle([a_day(28, kinds=["close"]), a_day(29)], [28, sd.CLEANUP, 29])
        self.assertIn("cleanup day: write its day spec, Day 30", sd.next_step(days, [], [28, sd.CLEANUP, 29], None))
        order, _ = sd.run_order(plan, list(range(1, 40)))
        self.assertEqual(order[order.index(28) + 1], 39)  # written: the day takes the placeholder's place
        self.assertEqual(sd.run_order(plan.split("\n## Course correction")[0], list(range(1, 42)))[1], 28)

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
        # A track split again past the gate (Day 17's B1 into b1a..b1c): the letter after the digits
        # is a part of that track, not a track of its own.
        self.assertEqual(sd.merged_tracks(["day-17/track-b1a-service-key", "day-17/track-b1c-trusted-callers",
                                           "day-17/track-b2b-internal-client-and-breaker"],
                                          ["0", "A1", "A2", "B1", "B2", "C1"]),
                         ["B1", "B2"])
        # A table that names the letter (Day 20's 0a, 0b) makes it a track of its own.
        self.assertEqual(sd.track_names("| 0a | | Work |\n| 0b | | Work |\n| A | | Work |"), ["0a", "0b", "A"])
        self.assertEqual(sd.merged_tracks(["day-20/track-0a-jobs-db-seams"], ["0a", "0b", "A"]), ["0a"])

    def test_a_branch_that_names_no_day_is_read_by_its_title(self):
        # #321, Day 28's Track D, came from a branch that named no day.
        pr = dict(headRefName="claude/focused-bell-qqwqdx",
                  title="KAN-82 Day 28 track D: stopping-point review, plan changed after Phase 5")
        self.assertEqual(sd.merged_tracks([sd.day_branch(pr)], ["0", "A1", "E", "D"]), ["D"])
        # A day's own branch wins over its title, and a title that names no track changes nothing.
        own = dict(headRefName="day-28/track-e-KAN-84-identity-healthcheck", title="Day 28 track D: wrong")
        self.assertEqual(sd.day_branch(own), own["headRefName"])
        other = dict(headRefName="claude/focused-sagan-96xo1w", title="identity-service README: pull the image")
        self.assertEqual(sd.day_branch(other), other["headRefName"])


if __name__ == "__main__":
    unittest.main()
