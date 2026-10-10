"""The deploy scripts (Day 35) against a stub aws, and infra-checks.py --state on a fixture.

    python scripts/test_deploy_scripts.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BASH = shutil.which("bash")  # on Windows a bare "bash" can be the WSL launcher in System32

# The stub logs each call as one tab-separated line to $STUB_LOG, then answers from STUB_* variables
STUB_AWS = r"""#!/usr/bin/env bash
(IFS=$'\t'; printf 'aws\t%s\n' "$*") >> "$STUB_LOG"
case "$*" in
    *describe-services*) echo network ;;
    *run-task*) echo arn:aws:ecs:eu-west-1:123456789012:task/jobmatch/stubtask ;;
    *exitCode*) printf '%s\tstub stop\n' "${STUB_EXIT:-0}" ;;
    *get-log-events*) echo "stub log line" ;;
esac
"""

def posix(path):
    return path.replace("\\", "/")


class DeployScriptsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="deploy-scripts-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.stubs = os.path.join(self.root, "stubs")
        self.log = os.path.join(self.root, "stub.log")
        os.mkdir(self.stubs)
        for name, body in (("aws", STUB_AWS),):
            path = os.path.join(self.stubs, name)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            os.chmod(path, 0o755)

    def run_script(self, script, *args, **stub):
        env = {k: v for k, v in os.environ.items() if k.upper() != "PATH"}
        env.update(PATH=os.pathsep.join([self.stubs, os.environ.get("PATH", "")]),
                   STUB_LOG=posix(self.log), **stub)
        path = posix(os.path.join(REPO, "scripts", script))
        return subprocess.run([BASH, path, *args], env=env, capture_output=True, text=True)

    def calls(self, verb):
        """The argument lists of the stub aws calls that name the verb, e.g. run-task."""
        if not os.path.exists(self.log):
            return []
        with open(self.log, encoding="utf-8") as f:
            rows = [line.rstrip("\n").split("\t")[1:] for line in f if line.strip()]
        return [row for row in rows if verb in row]

    def test_run_once_exits_with_the_container_and_prints_its_code(self):
        for code, failed in (("1", True), ("0", False)):
            with self.subTest(code=code):
                result = self.run_script("run-once.sh", "db-setup", STUB_EXIT=code)
                self.assertEqual(result.returncode != 0, failed, result.stdout + result.stderr)
                self.assertIn(f"exit code: {code}", result.stdout)
        run = self.calls("run-task")[0]
        self.assertEqual(run[run.index("--task-definition") + 1], "jobmatch-db-setup")

    def infra_checks_state(self, password):
        path = os.path.join(self.root, "state.json")
        state = {"version": 4, "resources": [{"type": "aws_db_instance",
                                              "instances": [{"attributes": {"password": password}}]}]}
        with open(path, "w", encoding="utf-8") as f:
            json.dump(state, f)
        return subprocess.run([sys.executable, os.path.join(HERE, "infra-checks.py"), "--state", path],
                              capture_output=True, text=True)

    def test_infra_checks_state_fails_on_a_password_without_printing_it_and_passes_on_null(self):
        result = self.infra_checks_state("hunter-sentinel-2210")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("C32.5", result.stdout)
        self.assertNotIn("hunter-sentinel-2210", result.stdout)
        result = self.infra_checks_state(None)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ok (state)", result.stdout)

if __name__ == "__main__":
    unittest.main()
