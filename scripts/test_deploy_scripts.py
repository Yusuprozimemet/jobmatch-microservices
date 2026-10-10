"""The deploy scripts (Day 35) against stub aws and dig, and infra-checks.py --state on a fixture.

    python scripts/test_deploy_scripts.py
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BASH = shutil.which("bash")  # on Windows a bare "bash" can be the WSL launcher in System32

# The stubs log each call as one tab-separated line to $STUB_LOG, then answer from STUB_* variables
STUB_AWS = r"""#!/usr/bin/env bash
(IFS=$'\t'; printf 'aws\t%s\n' "$*") >> "$STUB_LOG"
case "$*" in
    *describe-services*) echo network ;;
    *run-task*) echo arn:aws:ecs:eu-west-1:123456789012:task/jobmatch/stubtask ;;
    *exitCode*) printf '%s\tstub stop\n' "${STUB_EXIT:-0}" ;;
    *get-log-events*) echo "stub log line" ;;
    *describe-orderable*) printf '18.1\t18.2\n' ;;
    *list-open-id*) echo arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com ;;
    *get-hosted-zone*) echo "${STUB_ZONE_NS:-ns-2.example.net. ns-1.example.com.}" ;;
esac
"""

STUB_DIG = r"""#!/usr/bin/env bash
(IFS=$'\t'; printf 'dig\t%s\n' "$*") >> "$STUB_LOG"
for ns in ${STUB_DIG_NS-ns-1.example.com ns-2.example.net}; do echo "$ns."; done
"""


def posix(path):
    return path.replace("\\", "/")


def terraform_secret_names():
    with open(os.path.join(REPO, "infra", "terraform", "secrets.tf"), encoding="utf-8") as f:
        text = f.read()
    roles = re.findall(r'"([^"]+)"', re.search(r"db_roles = \[(.*?)\]", text).group(1))
    listed = re.search(r"secret_names = concat\(\[.*?\], \[(.*?)\]\)", text, re.S).group(1)
    return [f"db-password-{role}" for role in roles] + re.findall(r'"([^"]+)"', listed)


class DeployScriptsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="deploy-scripts-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.stubs = os.path.join(self.root, "stubs")
        self.log = os.path.join(self.root, "stub.log")
        self.secrets = os.path.join(self.root, "secrets")
        os.mkdir(self.stubs)
        for name, body in (("aws", STUB_AWS), ("dig", STUB_DIG)):
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
        """The argument lists of the stub aws calls that name the verb, e.g. put-secret-value."""
        if not os.path.exists(self.log):
            return []
        with open(self.log, encoding="utf-8") as f:
            rows = [line.rstrip("\n").split("\t")[1:] for line in f if line.strip()]
        return [row for row in rows if verb in row]

    def maintainer_files(self, **overrides):
        # Made per run, not literals: a fixed value here reads as a leaked secret to a scanner
        values = {name: f"sentinel-{uuid.uuid4().hex}"
                  for name in ("google-client-id", "google-client-secret", "llm-api-key")}
        values.update(overrides)
        os.makedirs(self.secrets, exist_ok=True)
        for name, value in values.items():
            if value is not None:
                with open(os.path.join(self.secrets, name), "w", encoding="utf-8") as f:
                    f.write(value + "\n")
        return values

    def test_write_secrets_writes_the_fourteen_terraform_names_and_prints_no_value(self):
        values = self.maintainer_files()
        result = self.run_script("write-secrets.sh", posix(self.secrets))
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, output)
        self.assertIn("write-secrets: 14 secrets written", output)
        with open(os.path.join(self.secrets, "llm-api-key"), encoding="utf-8", newline="") as f:
            self.assertEqual(f.read(), values["llm-api-key"], "the editor's newline would be sent")
        for name in os.listdir(self.secrets):
            with open(os.path.join(self.secrets, name), encoding="utf-8") as f:
                for line in f.read().split():
                    self.assertNotIn(line, output, f"part of {name} was printed")
        puts = self.calls("put-secret-value")
        self.assertEqual(len(puts), 14)
        self.assertEqual({p[p.index("--secret-id") + 1] for p in puts},
                         {f"jobmatch/{n}" for n in terraform_secret_names()})
        for p in puts:
            name = p[p.index("--secret-id") + 1].split("/")[1]
            self.assertTrue(p[p.index("--secret-string") + 1].endswith(f"/{name}"), p)

    def test_write_secrets_stops_before_writing_when_a_maintainer_file_is_missing(self):
        self.maintainer_files(**{"llm-api-key": None})
        result = self.run_script("write-secrets.sh", posix(self.secrets))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("llm-api-key", result.stdout + result.stderr)
        self.assertEqual(self.calls("put-secret-value"), [])

    def test_write_secrets_refuses_a_directory_inside_the_repository(self):
        scripts = os.path.join(REPO, "scripts")
        before = sorted(os.listdir(scripts))
        result = self.run_script("write-secrets.sh", posix(scripts))
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(sorted(os.listdir(scripts)), before)
        self.assertEqual(self.calls("put-secret-value"), [])

    def test_run_once_exits_with_the_container_and_prints_its_code(self):
        for code, failed in (("1", True), ("0", False)):
            with self.subTest(code=code):
                result = self.run_script("run-once.sh", "db-setup", STUB_EXIT=code)
                self.assertEqual(result.returncode != 0, failed, result.stdout + result.stderr)
                self.assertIn(f"exit code: {code}", result.stdout)
        run = self.calls("run-task")[0]
        self.assertEqual(run[run.index("--task-definition") + 1], "jobmatch-db-setup")

    def preflight(self, alert_email="ops@jobmatch.example.org", **stub):
        var_file = os.path.join(self.root, "deploy.tfvars")
        with open(var_file, "w", encoding="utf-8", newline="\n") as f:
            f.write(f'alert_email = "{alert_email}"\ndomain = "jobmatch.example.org"\n'
                    'route53_zone_id = "Z0123456789ABC"\n')
        return self.run_script("deploy-preflight.sh", "--var-file", posix(var_file), **stub)

    def test_preflight_answers_five_questions_and_passes_when_all_are_good(self):
        result = self.preflight()
        lines = result.stdout.splitlines()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(lines), 5, result.stdout)
        self.assertTrue(all(line.startswith("ok") for line in lines), result.stdout)

    def assert_one_failure(self, result, question):
        lines = result.stdout.splitlines()
        failures = [line for line in lines if line.startswith("FAIL")]
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(lines), 5, result.stdout)
        self.assertEqual(len(failures), 1, result.stdout)
        self.assertIn(question, failures[0])

    def test_preflight_fails_on_the_placeholder_alert_email(self):
        self.assert_one_failure(self.preflight(alert_email="alerts@example.com"), "alert_email")

    def test_preflight_fails_when_the_public_name_servers_differ_from_the_zone(self):
        self.assert_one_failure(self.preflight(STUB_DIG_NS="ns-1.example.com ns-9.example.org"),
                                "NS delegation")

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
