"""The token counts move with the replies and only then.

Each test writes a small transcript to a temporary folder and runs token-usage.py's count over it:
a reply is counted once, from its last streamed line, and the day comes from the branch.

    python scripts/test_token_usage.py
"""
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("token_usage", os.path.join(HERE, "token-usage.py"))
tu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tu)


def line(message_id="msg_1", request_id="req_1", output=3, branch="day-43/track-a2-x"):
    """One assistant line, as Claude Code writes it per streamed content block."""
    return json.dumps({
        "type": "assistant",
        "requestId": request_id,
        "timestamp": "2026-10-12T10:00:00Z",
        "gitBranch": branch,
        "message": {
            "id": message_id,
            "model": "claude-haiku-5-5-20260101",
            "usage": {"input_tokens": 10, "cache_creation_input_tokens": 0,
                      "cache_read_input_tokens": 0, "output_tokens": output},
        },
    })


class CountTest(unittest.TestCase):
    def count(self, lines):
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "session.jsonl")
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            return tu.count([path])

    def rows(self, totals):
        return [(key, dict(t)) for key, t in totals.items()]

    def test_a_reply_is_counted_from_its_last_line(self):
        totals = self.count([line(output=3), line(output=120)])
        rows = self.rows(totals)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][1]["output"], 120)
        self.assertEqual(rows[0][1]["replies"], 1)

    def test_identical_repeats_count_once(self):
        totals = self.count([line(output=50), line(output=50), line(output=50)])
        rows = self.rows(totals)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][1]["output"], 50)
        self.assertEqual(rows[0][1]["replies"], 1)

    def test_different_replies_add_up(self):
        totals = self.count([line(message_id="msg_1", output=10), line(message_id="msg_2", output=20)])
        rows = self.rows(totals)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][1]["output"], 30)
        self.assertEqual(rows[0][1]["replies"], 2)

    def test_the_day_comes_from_the_branch(self):
        day43 = self.rows(self.count([line(branch="day-43/track-a2-x")]))
        self.assertEqual(day43[0][0][1], 43)
        main = self.rows(self.count([line(branch="main")]))
        self.assertIsNone(main[0][0][1])


if __name__ == "__main__":
    unittest.main()
