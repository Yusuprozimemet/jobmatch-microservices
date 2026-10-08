"""Run scripts/db-setup.py twice against a fresh Postgres and pin what it leaves behind.

No CI job ran db-setup.py before Day 33 (IdentityDbConnectTest only reads its source), so a
change to its grants could merge unseen. This runs it as an unattended job would, twice: the
second run must change nothing, and the databases, roles, schema owners, CONNECT grants and
privileges must equal EXPECTED, taken from the script as it was on Day 33.

It creates databases and roles, so it needs a Postgres with password authentication:

    PW=$(python -c "import secrets; print(secrets.token_hex(16))")
    docker run -d --name dbsetup-test -e POSTGRES_PASSWORD=$PW -p 55432:5432 postgres:18.4-alpine
    POSTGRES_PORT=55432 POSTGRES_PASSWORD=$PW python -m pytest -q scripts/test_db_setup.py

Connection details come from POSTGRES_HOST, POSTGRES_PORT, POSTGRES_USER and POSTGRES_PASSWORD.
The passwords test needs the container to check them: under trust auth it fails, saying so.

Requires: pip install "psycopg[binary]" pytest
"""

from __future__ import annotations

import os
import re
import secrets
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import psycopg
import pytest

HOST = os.getenv("POSTGRES_HOST") or "localhost"
PORT = int(os.getenv("POSTGRES_PORT") or 5432)
ADMIN = os.getenv("POSTGRES_USER") or "postgres"
ADMIN_PASSWORD = os.getenv("POSTGRES_PASSWORD") or secrets.token_hex(16)

SCRIPT = Path(__file__).with_name("db-setup.py")
DATABASES = ("identity_db", "jobs_db", "apps_db")
# Typed out, not imported from the script: the pin is the point.
ROLES = ("app_user", "analytics_user", "analytics_dev_user", "identity_user",
         "applications_user", "matching_user", "jobs_user")

# Each database's default privileges are registered for the admin and every role that may
# create objects there (db-setup.py's `creators`).
IDENTITY = ("ADMIN", "analytics_dev_user", "analytics_user", "app_user", "applications_user",
            "identity_user", "jobs_user", "matching_user")
JOBS = ("ADMIN", "analytics_dev_user", "analytics_user", "jobs_user")
APPS = ("ADMIN", "applications_user")

# Taken from postgres:18.4. Privileges are aclitem letters, grantor dropped, '=' alone is
# PUBLIC: r SELECT, a INSERT, w UPDATE, d DELETE, D TRUNCATE, x REFERENCES, t TRIGGER,
# m MAINTAIN, X EXECUTE, U USAGE, C CREATE. Default privileges are keyed (database, schema,
# object type: r tables, S sequences, f functions, grant) and map to the creators above.
EXPECTED = {
    "connect": {"apps_db": ["ADMIN", "applications_user"],
                "identity_db": ["ADMIN", "identity_user"],
                "jobs_db": ["ADMIN", "analytics_dev_user", "analytics_user", "jobs_user"]},
    "databases": {"apps_db": "ADMIN", "identity_db": "ADMIN", "jobs_db": "ADMIN"},
    # login, superuser, createdb, createrole
    "roles": dict.fromkeys(ROLES, (True, False, False, False)),
    "memberships": [("applications_user", "app_user"), ("identity_user", "app_user"),
                    ("matching_user", "app_user")],
    "schemas": {
        ("apps_db", "applications"): ("applications_user", ["applications_user=UC"]),
        ("apps_db", "public"): ("pg_database_owner", ["=U", "pg_database_owner=UC"]),
        ("identity_db", "app"): ("app_user", [
            "analytics_dev_user=U", "analytics_user=U", "app_user=UC", "applications_user=U",
            "identity_user=U", "jobs_user=U", "matching_user=U"]),
        ("identity_db", "applications"): ("applications_user", ["applications_user=UC"]),
        ("identity_db", "identity"): ("identity_user", ["identity_user=UC"]),
        ("identity_db", "matching"): ("matching_user", ["matching_user=UC"]),
        ("identity_db", "public"): ("pg_database_owner", ["=U", "pg_database_owner=UC"]),
        ("jobs_db", "analytics"): ("analytics_user", [
            "analytics_dev_user=U", "analytics_user=UC", "jobs_user=U"]),
        ("jobs_db", "analytics_dev"): ("analytics_dev_user", [
            "analytics_dev_user=UC", "analytics_user=U", "jobs_user=U"]),
        ("jobs_db", "public"): ("pg_database_owner", ["=U", "pg_database_owner=UC"]),
    },
    "default_privileges": {
        ("apps_db", "applications", "S", "applications_user=rwU"): APPS,
        ("apps_db", "applications", "f", "applications_user=X"): APPS,
        ("apps_db", "applications", "r", "applications_user=arwdDxtm"): APPS,
        ("identity_db", "app", "S", "analytics_dev_user=r"): IDENTITY,
        ("identity_db", "app", "S", "analytics_user=r"): IDENTITY,
        ("identity_db", "app", "S", "app_user=rwU"): IDENTITY,
        ("identity_db", "app", "S", "applications_user=r"): IDENTITY,
        ("identity_db", "app", "S", "identity_user=r"): IDENTITY,
        ("identity_db", "app", "S", "jobs_user=r"): IDENTITY,
        ("identity_db", "app", "S", "matching_user=r"): IDENTITY,
        ("identity_db", "app", "f", "app_user=X"): IDENTITY,
        ("identity_db", "app", "r", "analytics_dev_user=r"): IDENTITY,
        ("identity_db", "app", "r", "analytics_user=r"): IDENTITY,
        ("identity_db", "app", "r", "app_user=arwdDxtm"): IDENTITY,
        ("identity_db", "app", "r", "applications_user=r"): IDENTITY,
        ("identity_db", "app", "r", "identity_user=r"): IDENTITY,
        ("identity_db", "app", "r", "jobs_user=r"): IDENTITY,
        ("identity_db", "app", "r", "matching_user=r"): IDENTITY,
        ("identity_db", "applications", "S", "applications_user=rwU"): IDENTITY,
        ("identity_db", "applications", "f", "applications_user=X"): IDENTITY,
        ("identity_db", "applications", "r", "applications_user=arwdDxtm"): IDENTITY,
        ("identity_db", "identity", "S", "identity_user=rwU"): IDENTITY,
        ("identity_db", "identity", "f", "identity_user=X"): IDENTITY,
        ("identity_db", "identity", "r", "identity_user=arwdDxtm"): IDENTITY,
        ("identity_db", "matching", "S", "matching_user=rwU"): IDENTITY,
        ("identity_db", "matching", "f", "matching_user=X"): IDENTITY,
        ("identity_db", "matching", "r", "matching_user=arwdDxtm"): IDENTITY,
        ("jobs_db", "analytics", "S", "analytics_dev_user=r"): JOBS,
        ("jobs_db", "analytics", "S", "analytics_user=rwU"): JOBS,
        ("jobs_db", "analytics", "S", "jobs_user=r"): JOBS,
        ("jobs_db", "analytics", "f", "analytics_user=X"): JOBS,
        ("jobs_db", "analytics", "r", "analytics_dev_user=r"): JOBS,
        ("jobs_db", "analytics", "r", "analytics_user=arwdDxtm"): JOBS,
        ("jobs_db", "analytics", "r", "jobs_user=r"): JOBS,
        ("jobs_db", "analytics_dev", "S", "analytics_dev_user=rwU"): JOBS,
        ("jobs_db", "analytics_dev", "S", "analytics_user=r"): JOBS,
        ("jobs_db", "analytics_dev", "S", "jobs_user=r"): JOBS,
        ("jobs_db", "analytics_dev", "f", "analytics_dev_user=X"): JOBS,
        ("jobs_db", "analytics_dev", "r", "analytics_dev_user=arwdDxtm"): JOBS,
        ("jobs_db", "analytics_dev", "r", "analytics_user=r"): JOBS,
        ("jobs_db", "analytics_dev", "r", "jobs_user=r"): JOBS,
    },
}


def name(role: str) -> str:
    """The admin under one name, whatever POSTGRES_USER is."""
    return "ADMIN" if role == ADMIN else role


def acl_item(item: str) -> str:
    """'grantee=privileges/grantor' as 'grantee=privileges', the admin renamed; PUBLIC is '='."""
    grantee, privileges = item.split("/")[0].split("=")
    return f"{name(grantee) if grantee else ''}={privileges}"


def connect(database: str) -> psycopg.Connection:
    return psycopg.connect(host=HOST, port=PORT, dbname=database, user=ADMIN,
                           password=ADMIN_PASSWORD, autocommit=True)


def setup_env(port: int, passwords: dict[str, str] | None = None) -> dict[str, str]:
    """The script's environment: the admin, `passwords`, and no inherited DB_PASSWORD_*."""
    env = {k: v for k, v in os.environ.items() if not k.startswith("DB_PASSWORD_")}
    env.update({
        "POSTGRES_HOST": HOST,
        "POSTGRES_PORT": str(port),
        "POSTGRES_USER": ADMIN,
        "POSTGRES_PASSWORD": ADMIN_PASSWORD,
        "PYTHONIOENCODING": "utf-8",  # its log has emoji; a Windows console is not UTF-8
    })
    if passwords:
        env.update(passwords)
    return env


def run_setup() -> subprocess.CompletedProcess:
    """Run the script with no terminal and the admin password in the environment."""
    env = setup_env(PORT)
    result = subprocess.run([sys.executable, str(SCRIPT)], env=env, stdin=subprocess.DEVNULL,
                            capture_output=True, text=True, encoding="utf-8")
    # stderr is the log, which holds no password; stdout, the report, can.
    assert result.returncode == 0, f"db-setup.py exited {result.returncode}:\n{result.stderr}"
    return result


def run_setup_from_env(env: dict[str, str]) -> subprocess.CompletedProcess:
    """Run the script with --passwords-from-env and no terminal; the caller checks the exit."""
    return subprocess.run([sys.executable, str(SCRIPT), "--passwords-from-env"], env=env,
                          stdin=subprocess.DEVNULL, capture_output=True, text=True,
                          encoding="utf-8")


def snapshot() -> dict:
    state: dict = {"connect": defaultdict(list), "schemas": {}}
    with connect("postgres") as conn:
        state["databases"] = {db: name(owner) for db, owner in conn.execute(
            "SELECT datname, pg_get_userbyid(datdba) FROM pg_database WHERE datname = ANY(%s)",
            [list(DATABASES)])}
        for db, grantee in conn.execute(
                "SELECT datname, CASE WHEN a.grantee = 0 THEN 'PUBLIC'"
                " ELSE pg_get_userbyid(a.grantee) END"
                " FROM pg_database, aclexplode(datacl) a"
                " WHERE datname = ANY(%s) AND a.privilege_type = 'CONNECT'",
                [list(DATABASES)]):
            state["connect"][db].append(name(grantee))
        state["connect"] = {db: sorted(grantees) for db, grantees in state["connect"].items()}
        state["roles"] = {role: tuple(flags) for role, *flags in conn.execute(
            "SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole"
            " FROM pg_roles WHERE rolname = ANY(%s)", [list(ROLES)])}
        state["memberships"] = sorted((name(role), name(member)) for role, member in conn.execute(
            "SELECT pg_get_userbyid(roleid), pg_get_userbyid(member) FROM pg_auth_members"
            " WHERE pg_get_userbyid(roleid) = ANY(%s) OR pg_get_userbyid(member) = ANY(%s)",
            [list(ROLES), list(ROLES)]))

    creators: dict = defaultdict(list)
    for db in DATABASES:  # a database the script did not create fails the connect, as it should
        with connect(db) as conn:
            for schema, owner, acl in conn.execute(
                    "SELECT nspname, pg_get_userbyid(nspowner), nspacl::text[] FROM pg_namespace"
                    " WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema'"):
                state["schemas"][(db, schema)] = (name(owner), sorted(map(acl_item, acl or [])))
            for creator, schema, objtype, acl in conn.execute(
                    "SELECT pg_get_userbyid(defaclrole), nspname, defaclobjtype, defaclacl::text[]"
                    " FROM pg_default_acl LEFT JOIN pg_namespace n ON n.oid = defaclnamespace"):
                for item in acl:
                    creators[(db, schema, objtype, acl_item(item))].append(name(creator))
    # One entry per grant, mapped to the roles whose new objects get it.
    state["default_privileges"] = {key: tuple(sorted(roles)) for key, roles in creators.items()}
    return state


def reported_passwords(report: str) -> dict:
    """Each role's 'password :' line in the script's report, by role."""
    return dict(re.findall(r"^  (\w+)\n    password : (.+)$", report, re.MULTILINE))


@pytest.fixture(scope="module")
def runs():
    hashes = "SELECT rolname, rolpassword FROM pg_authid WHERE rolname = ANY(%s)"
    with connect("postgres") as conn:
        leftovers = conn.execute(
            "SELECT datname FROM pg_database WHERE datname = ANY(%s)"
            " UNION ALL SELECT rolname FROM pg_roles WHERE rolname = ANY(%s)",
            [list(DATABASES), list(ROLES)]).fetchall()
    assert not leftovers, (f"needs a fresh Postgres, found {[row[0] for row in leftovers]}:"
                           " use a throwaway container (see the docstring)")
    first = run_setup()
    after_first = snapshot()
    with connect("postgres") as conn:
        hashes_first = dict(conn.execute(hashes, [list(ROLES)]))
    second = run_setup()
    with connect("postgres") as conn:
        # Kept as a bool, so a failure never prints a hash.
        hashes_kept = dict(conn.execute(hashes, [list(ROLES)])) == hashes_first
    return {"first": first, "second": second, "after_first": after_first,
            "after_second": snapshot(), "hashes_kept": hashes_kept}


def test_the_first_run_leaves_the_pinned_state(runs):
    assert runs["after_first"] == EXPECTED


def test_the_second_run_changes_nothing(runs):
    assert runs["after_second"] == runs["after_first"]
    assert runs["hashes_kept"], "the second run changed a role's password"


def test_the_second_run_reports_every_password_unchanged(runs):
    first = reported_passwords(runs["first"].stdout)
    assert sorted(first) == sorted(ROLES), "the first run's report does not list every role"
    assert "(unchanged)" not in first.values(), "the first run should create every role"
    second = reported_passwords(runs["second"].stdout)
    # Roles, not values, in the messages: a failure must not print a password.
    assert sorted(second) == sorted(ROLES), "the second run's report does not list every role"
    changed = [role for role in ROLES if second[role] != "(unchanged)"]
    assert not changed, f"the second run reported a new password for {changed}"
    output = runs["second"].stdout + runs["second"].stderr
    leaked = [role for role, password in first.items() if password in output]
    assert not leaked, f"the second run printed the first run's password of {leaked}"


def test_a_missing_password_fails_before_connecting():
    passwords = {f"DB_PASSWORD_{role.upper()}": secrets.token_hex(16)
                 for role in ROLES if role not in ("jobs_user", "matching_user")}
    env = setup_env(1, passwords)  # port 1: a connection attempt would fail differently
    env.pop("POSTGRES_PASSWORD")
    result = run_setup_from_env(env)
    assert result.returncode == 2, result.stderr
    stderr = result.stderr
    assert "DB_PASSWORD_JOBS_USER" in stderr
    assert "DB_PASSWORD_MATCHING_USER" in stderr
    assert "POSTGRES_PASSWORD" in stderr
    for var in passwords:
        assert var not in stderr, f"{var} is set but named as missing"
    assert "Connecting to" not in stderr
    assert "Could not connect" not in stderr


def test_passwords_from_env_sets_every_role_and_keeps_the_pin(runs):
    # jobs_user owns nothing, so it can go: the run then creates one role and resets the rest.
    for db in DATABASES:
        with connect(db) as conn:
            conn.execute("DROP OWNED BY jobs_user")
    with connect("postgres") as conn:
        conn.execute("DROP ROLE jobs_user")
    passwords = {role: secrets.token_hex(16) for role in ROLES}
    result = run_setup_from_env(setup_env(PORT, {
        f"DB_PASSWORD_{role.upper()}": password for role, password in passwords.items()}))
    # stderr only: stdout is the report, which should hold no password but might.
    assert result.returncode == 0, f"db-setup.py exited {result.returncode}:\n{result.stderr}"
    output = result.stdout + result.stderr
    leaked = [role for role, password in passwords.items() if password in output]
    assert not leaked, f"db-setup.py printed the password of {leaked}"
    # Under trust auth every login below would pass whatever the password.
    try:
        psycopg.connect(host=HOST, port=PORT, dbname="postgres", user="identity_user",
                        password=secrets.token_hex(16), autocommit=True).close()
    except psycopg.OperationalError:
        pass
    else:
        pytest.fail("Postgres accepts any password (trust auth); run it with"
                    " POSTGRES_PASSWORD, see the docstring")
    connection_tests = [
        ("identity_user", "identity_db"),
        ("applications_user", "apps_db"),
        ("analytics_user", "jobs_db"),
        ("analytics_dev_user", "jobs_db"),
        ("jobs_user", "jobs_db"),
        ("app_user", "postgres"),
        ("matching_user", "postgres"),
    ]
    failures = []
    for role, database in connection_tests:
        try:
            with psycopg.connect(host=HOST, port=PORT, dbname=database, user=role,
                                 password=passwords[role], autocommit=True) as conn:
                if conn.execute("SELECT current_user").fetchone()[0] != role:
                    failures.append(f"{role}: logged in as another role")
        except psycopg.OperationalError as error:  # the type only: a message may hold more
            failures.append(f"{role}: {type(error).__name__}")
    assert not failures, "\n".join(failures)
    assert snapshot() == EXPECTED
