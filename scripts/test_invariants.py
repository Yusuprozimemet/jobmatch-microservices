"""The frozen files stay frozen, and the moves and additions around them pass.

Each test builds a scratch git repository with a base commit holding one applied migration, identity's
contract test, the two Day 01 harness self-tests and an unprotected file, changes it on a branch, and
runs scripts/invariants.py over the branch as a subprocess.

    python scripts/test_invariants.py
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "invariants.py")

IDENTITY = "services/identity-service"
TESTS = IDENTITY + "/src/test/java/nl/hackyourfuture/project/backend"
MIGRATION = IDENTITY + "/src/main/resources/db/migration/V1__init.sql"
CONTRACT = TESTS + "/contract/LoginContractTest.java"
HARNESS = TESTS + "/support/TestHarnessTest.java"
APP_TESTS = TESTS + "/BackendApplicationTests.java"
README = IDENTITY + "/README.md"

BASE = {
    MIGRATION: "".join(f"CREATE TABLE users_{i} (id BIGINT PRIMARY KEY);\n" for i in range(10)),
    CONTRACT: "class LoginContractTest {\n    void logsIn() {}\n}\n",
    HARNESS: "class TestHarnessTest {\n    void starts() {}\n}\n",
    APP_TESTS: "class BackendApplicationTests {\n    void loads() {}\n}\n",
    README: "# identity\n",
}


def git(repo, *args):
    subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True)


def write(repo, path, text):
    full = os.path.join(repo, *path.split("/"))
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


class InvariantsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.repo = self.folder.name
        git(self.repo, "init", "-q", "-b", "main")
        # Renames are only 100 when git does not rewrite line endings on checkout (Windows).
        git(self.repo, "config", "core.autocrlf", "false")
        git(self.repo, "config", "user.name", "Test")
        git(self.repo, "config", "user.email", "test@example.com")
        git(self.repo, "config", "commit.gpgsign", "false")
        for path, text in BASE.items():
            write(self.repo, path, text)
        self.commit("base")
        git(self.repo, "checkout", "-q", "-b", "change")

    def tearDown(self):
        self.folder.cleanup()

    def commit(self, message):
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", message)

    def run_script(self):
        return subprocess.run(
            [sys.executable, SCRIPT, "main", "change"],
            cwd=self.repo, capture_output=True, text=True, encoding="utf-8",
        )

    def test_an_edit_of_an_applied_migration_fails(self):
        write(self.repo, MIGRATION, BASE[MIGRATION] + "ALTER TABLE users_0 ADD COLUMN x INT;\n")
        self.commit("edit V1")
        result = self.run_script()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"modified: {MIGRATION}", result.stdout)

    def test_a_deleted_contract_test_fails(self):
        os.remove(os.path.join(self.repo, *CONTRACT.split("/")))
        self.commit("delete the contract test")
        result = self.run_script()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"deleted: {CONTRACT}", result.stdout)

    def test_an_edit_of_the_harness_self_test_fails(self):
        write(self.repo, HARNESS, "class TestHarnessTest {\n    void starts() { }\n}\n")
        self.commit("edit the harness self-test")
        result = self.run_script()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"modified: {HARNESS}", result.stdout)

    def test_an_edit_of_backend_application_tests_fails(self):
        write(self.repo, APP_TESTS, "class BackendApplicationTests {\n    void loads() { }\n}\n")
        self.commit("edit BackendApplicationTests")
        result = self.run_script()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"modified: {APP_TESTS}", result.stdout)

    def test_a_new_migration_passes(self):
        added = IDENTITY + "/src/main/resources/db/migration/V2__more.sql"
        write(self.repo, added, "CREATE TABLE jobs (id BIGINT PRIMARY KEY);\n")
        self.commit("add V2")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("OK", result.stdout)

    def test_a_pure_rename_of_the_whole_tree_passes(self):
        # Moves the src tree one level down, contents identical: every file is R100.
        os.makedirs(os.path.join(self.repo, IDENTITY, "app"))
        os.rename(os.path.join(self.repo, IDENTITY, "src"),
                  os.path.join(self.repo, IDENTITY, "app", "src"))
        self.commit("move src down one level")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("OK", result.stdout)

    def test_a_rename_with_an_edit_fails_and_names_the_old_path(self):
        moved = IDENTITY + "/src/main/resources/db/V1__init.sql"
        os.makedirs(os.path.dirname(os.path.join(self.repo, *moved.split("/"))), exist_ok=True)
        os.rename(os.path.join(self.repo, *MIGRATION.split("/")),
                  os.path.join(self.repo, *moved.split("/")))
        write(self.repo, moved, BASE[MIGRATION].replace("users_0", "accounts_0"))
        self.commit("move V1 and edit it")
        result = self.run_script()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("renamed with an edit", result.stdout)
        self.assertIn(MIGRATION, result.stdout)

    def test_an_edit_of_an_unprotected_file_passes(self):
        write(self.repo, README, "# identity, edited\n")
        self.commit("edit the README")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
