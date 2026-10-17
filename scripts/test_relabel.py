"""scripts/relabel.py hides a day's Defect tags from the rater and finds where its labels disagree.

The first test replays the paper's sample, Days 21-28: 82 entries, 11 of them escapes. The rest
take one spec written the way specs/_template.md asks.

    python scripts/test_relabel.py
"""
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("relabel", os.path.join(HERE, "relabel.py"))
relabel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relabel)

NOTES = """## Notes
- Not a defect · cause: spec.
- **Defect** — found: auditor · cause: spec · made: Day 44 · C44.2 counted 9 files; there
  are 11. Fixed in this spec-change PR.
- **Defect** — found: review · cause: implementation · made: Day 44 · the draft kept a
  `found: close` comment · made: by hand · and a dead branch (#501).

- **Defect** — found: Track A · cause: spec · made: Day 42 · Day 42's grep matched comments.
- **Defect** — found: live run · cause: environment · made: none · the runner ran out of disk.
"""


def write_spec(text):
    d = tempfile.mkdtemp()
    path = os.path.join(d, "day-44-example.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return d, path


class PaperSampleTest(unittest.TestCase):
    def test_days_21_to_28_give_the_papers_82_entries_and_11_escapes(self):
        paths = [os.path.join(HERE, "..", "specs", f) for f in sorted(os.listdir(os.path.join(HERE, "..", "specs")))
                 if f[:6] in {f"day-{n}" for n in range(21, 29)}]
        rows = [r for p in paths for r in relabel.entries(p)]
        self.assertEqual(82, len(rows))
        self.assertEqual(11, sum(relabel.stage(r) == "escape" for r in rows))
        self.assertEqual([], [r["found"] for r in rows if not relabel.stage(r)])


class EntriesTest(unittest.TestCase):
    def setUp(self):
        _, self.path = write_spec(NOTES)
        self.rows = relabel.entries(self.path)

    def test_each_defect_line_with_its_continuation_and_nothing_else(self):
        self.assertEqual(["D44-1", "D44-2", "D44-3", "D44-4"], [r["id"] for r in self.rows])
        self.assertEqual("C44.2 counted 9 files; there are 11. Fixed in this spec-change PR.", self.rows[0]["text"])

    def test_a_dot_or_a_tag_word_inside_the_text_stays_text(self):
        self.assertEqual("the draft kept a `found: close` comment · made: by hand · and a dead branch (#501).", self.rows[1]["text"])
        self.assertEqual(("review", "implementation", "Day 44"),
                         (self.rows[1]["found"], self.rows[1]["cause"], self.rows[1]["made"]))

    def test_a_defect_made_on_an_earlier_day_is_an_escape_whatever_found_says(self):
        self.assertEqual(["before_code", "before_merge", "escape", ""], [relabel.stage(r) for r in self.rows])
        self.assertEqual(["this", "this", "earlier", "none"], [relabel.made(r) for r in self.rows])

    def test_the_blind_file_holds_no_tag(self):
        out = tempfile.mkdtemp()
        relabel.blind([self.path], out)
        text = open(os.path.join(out, "blind.md"), encoding="utf-8").read()
        for tag in ("found: auditor", "found: review", "cause:", "made: Day", "made: none", "live run"):
            self.assertNotIn(tag, text)
        self.assertIn("### D44-3 (Day 44)\nDay 42's grep matched comments.", text)
        self.assertEqual(4, len(json.load(open(os.path.join(out, "key.json"), encoding="utf-8"))))


class CompareTest(unittest.TestCase):
    def setUp(self):
        _, path = write_spec(NOTES)
        self.key = relabel.entries(path)
        self.labels = [dict(id=r["id"], stage=relabel.stage(r) or "escape", cause=r["cause"], made=relabel.made(r),
                            confidence="high", note="") for r in self.key]

    def test_full_agreement_lists_no_disagreement(self):
        self.assertEqual(["stage: 3 of 3 agree (1 untagged or not a README value, not compared)",
                          "cause: 4 of 4 agree", "made: 4 of 4 agree"], relabel.compare(self.key, self.labels))

    def test_each_disagreement_is_one_line_with_the_raters_note(self):
        self.labels[1].update(cause="spec", confidence="low", note="the spec asked for the comment")
        self.labels[2].update(stage="before_merge", made="unclear")
        lines = relabel.compare(self.key, self.labels)
        self.assertIn("cause: 3 of 4 agree", lines)
        self.assertIn("rater unclear: made 1", lines)
        self.assertIn("- D44-2 cause: tagged implementation, rater spec (low): the spec asked for the comment", lines)
        self.assertIn("- D44-3 stage: tagged escape, rater before_merge (high): ", lines)
        self.assertIn("- D44-3 made: tagged earlier, rater unclear (high): ", lines)

    def test_an_entry_the_rater_left_out_stops_the_comparison(self):
        with self.assertRaises(SystemExit) as e:
            relabel.compare(self.key, self.labels[:3])
        self.assertEqual("no label for D44-4", str(e.exception))


if __name__ == "__main__":
    unittest.main()
