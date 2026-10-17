"""scripts/pr-names.py finds a branch, title, commit or description that disagrees with the rest.

The three escapes it was written for are the first tests, with their real names; the rest take one
rule each away from a pull request named the way this repository names them.

    python scripts/test_pr_names.py
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "pr-names.py")
spec = importlib.util.spec_from_file_location("pr_names", SCRIPT)
pr_names = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pr_names)
problems = pr_names.problems

TRACK = ("day-43/track-d2-KAN-166-outward-docs", "Day 43 track D2: the README says where the migration ended",
         "Sub-task KAN-166 of KAN-152.", ["KAN-166 Day 43 track D2: the README says where the migration ended"])


class EscapesTest(unittest.TestCase):
    def test_d22_013_a_branch_that_names_part_of_a_track(self):
        found = problems("day-22/track-b-dynamodb-client",
                         "Day 22 track B1: matching-service's DynamoDB client, its table, and a 13 s model read", "", [])
        self.assertEqual(["the branch is Day 22 track B, the title Day 22 track B1"], found)

    def test_d27_062_a_description_with_another_key(self):
        found = problems("day-27/track-a-KAN-5-applications-consumer",
                         "Day 27 track A: the applications consumer of user.deleted", "Closes KAN-6.",
                         ["KAN-5 Day 27 track A: the applications consumer of user.deleted"])
        self.assertEqual(["the description names KAN-6, not KAN-5"], found)

    def test_d28_081_a_branch_the_tool_named(self):
        found = problems("claude/focused-bell-qqwqdx",
                         "KAN-82 Day 28 track D: stopping-point review, plan changed after Phase 5", "",
                         ["KAN-82 Day 28 track D: stopping-point review, plan changed after Phase 5"])
        self.assertEqual(3, len(found), found)
        self.assertIn("is neither day-NN/<slug>", found[0])


class RulesTest(unittest.TestCase):
    def test_names_that_agree_pass(self):
        self.assertEqual([], problems(*TRACK))
        self.assertEqual([], problems("day-35/track-c2a-KAN-137-deploy-job",
                                      "Day 35 track C2a (1 of 2): the deploy job in four service workflows", "", []))
        self.assertEqual([], problems("day-38/track-c-fix-harness-waits-for-ready",
                                      "Day 38 track C fix: the gateway harness waits until the gateway is ready", "", []))
        self.assertEqual([], problems("day-42/close-the-day-KAN-97", "Day 42 close: tick the criteria", "KAN-97", []))
        self.assertEqual([], problems("day-43/cost-KAN-171-c35-22", "Day 35 C35.22: the deployment cost 0.40 USD", "", []))
        self.assertEqual([], problems("workflow/pr-names", "Workflow: branch names, titles and Jira keys agree", "", []))
        self.assertEqual([], problems("day-43/close-the-day", "Day 43 close, part 2: Notes", "",
                                      ["KAN-168 Day 43 close, part 2: Notes"]))

    def test_track_on_one_side_only(self):
        self.assertIn("the title has no", problems("day-43/track-d2-x", "Day 43: the README", "", [])[0])
        self.assertIn("has no `track-x-`", problems("day-43/readme", "Day 43 track D2: the README", "", [])[0])

    def test_track_of_another_day(self):
        self.assertEqual(["the branch is Day 43 track D2, the title Day 42 track D2"],
                         problems("day-43/track-d2-x", "Day 42 track D2: the README", "", []))

    def test_title_day_on_a_branch_without_one(self):
        self.assertEqual(["the title names Day 43; the branch `docs/readme` names no day"],
                         problems("docs/readme", "Day 43: the README", "", []))

    def test_close_from_another_branch(self):
        self.assertIn("comes from day-43/close-the-day", problems("day-43/notes", "Day 43 close: Notes", "", [])[0])
        self.assertIn("comes from day-43/close-the-day",
                      problems("day-42/close-the-day", "Day 43 close: Notes", "", [])[0])

    def test_a_second_key_in_a_commit(self):
        branch, title, body, subjects = TRACK
        self.assertEqual(["the branch, the title and the commits name 2 Jira keys: KAN-166, KAN-167"],
                         problems(branch, title, body, subjects + ["KAN-167 fix the link"]))

    def test_a_second_key_in_the_title(self):
        branch, title, body, subjects = TRACK
        self.assertEqual(["the branch, the title and the commits name 2 Jira keys: KAN-166, KAN-46"],
                         problems(branch, "KAN-46 " + title, body, subjects))

    def test_a_description_that_names_the_day_and_not_the_sub_task(self):
        branch, title, _, subjects = TRACK
        self.assertEqual(["the description names KAN-152, not KAN-166"],
                         problems(branch, title, "Day issue KAN-152.", subjects))


class CommandTest(unittest.TestCase):
    def test_reads_the_commits_from_git_and_exits_1(self):
        repo = tempfile.mkdtemp()
        git = ["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@t"]
        subprocess.run(git[:3] + ["init", "-q"], check=True)
        subprocess.run(git + ["commit", "-q", "--allow-empty", "-m", "base"], check=True)
        subprocess.run(git + ["commit", "-q", "--allow-empty", "-m", "KAN-9 Day 43 track D2: x"], check=True)
        env = dict(os.environ, PR_BRANCH="day-43/track-d2-KAN-166-x", PR_TITLE="Day 43 track D2: x", PR_BODY="")
        run = subprocess.run([sys.executable, SCRIPT, "HEAD~1", "HEAD"], cwd=repo, env=env,
                             capture_output=True, text=True)
        self.assertEqual(1, run.returncode, run.stdout + run.stderr)
        self.assertIn("2 Jira keys: KAN-166, KAN-9", run.stdout)
        self.assertIn("1 problems over the branch, the title and 1 commits", run.stdout)


if __name__ == "__main__":
    unittest.main()
