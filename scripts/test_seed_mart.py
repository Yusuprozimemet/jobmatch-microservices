"""Seed jobs_db through the db-setup image the way the jobs-seed task does, twice.

The image's entrypoint creates the roles, as the db-setup task does. Then seed-mart.py runs with
analytics_user's password, as the jobs-seed task does: the first run loads the 24 fixture listings,
and the second must refuse and leave them. jobs_user, which reads the mart, counts them.

It needs the image, named by SEED_IMAGE, and a Postgres that the container can reach:

    PW=$(python -c "import secrets; print(secrets.token_hex(16))")
    docker build --build-context fixtures=services/identity-service/app/src/test/resources/fixtures \
      -t jobmatch-db-setup:ci -f scripts/db-setup.Dockerfile scripts
    docker network create seedtest
    docker run -d --name seedtest-pg --network seedtest -e POSTGRES_PASSWORD=$PW -p 55433:5432 postgres:18.4-alpine
    POSTGRES_PORT=55433 POSTGRES_PASSWORD=$PW SEED_IMAGE=jobmatch-db-setup:ci DOCKER_NETWORK=seedtest \
      CONTAINER_DB_HOST=seedtest-pg CONTAINER_DB_PORT=5432 python -m pytest -q scripts/test_seed_mart.py

The test's own connections use POSTGRES_HOST, POSTGRES_PORT, POSTGRES_USER and POSTGRES_PASSWORD. The
containers reach Postgres at CONTAINER_DB_HOST and CONTAINER_DB_PORT, which default to those, on
DOCKER_NETWORK (default host). The run leaves the roles and databases behind, so scripts/test_db_setup.py
needs a fresh Postgres of its own.

Requires: pip install "psycopg[binary]" pytest, and docker
"""

from __future__ import annotations

import os
import secrets
import subprocess

import psycopg
import pytest

IMAGE = os.getenv("SEED_IMAGE")
if not IMAGE:
    pytest.skip("SEED_IMAGE names the db-setup image (see the docstring)", allow_module_level=True)

HOST = os.getenv("POSTGRES_HOST") or "localhost"
PORT = int(os.getenv("POSTGRES_PORT") or 5432)
ADMIN = os.getenv("POSTGRES_USER") or "postgres"
ADMIN_PASSWORD = os.getenv("POSTGRES_PASSWORD") or secrets.token_hex(16)
NETWORK = os.getenv("DOCKER_NETWORK") or "host"
CONTAINER_HOST = os.getenv("CONTAINER_DB_HOST") or HOST
CONTAINER_PORT = os.getenv("CONTAINER_DB_PORT") or str(PORT)

# Typed out, as in test_db_setup.py: the pin is the point.
ROLES = ("app_user", "analytics_user", "analytics_dev_user", "identity_user",
         "applications_user", "matching_user", "jobs_user")
# The fixture's seed-0001 to seed-0024.
LISTINGS = 24


def password_variable(role: str) -> str:
    return f"DB_PASSWORD_{role.upper()}"


def run_image(variables: dict[str, str], args: list[str], entrypoint: str | None = None) -> subprocess.CompletedProcess:
    """Run the image once. Variables go by name (-e NAME), with their values in the subprocess's
    environment, so no password is on the command line."""
    command = ["docker", "run", "--rm", "--network", NETWORK]
    if entrypoint:
        command += ["--entrypoint", entrypoint]
    for name in variables:
        command += ["-e", name]
    command += [IMAGE, *args]
    return subprocess.run(command, env={**os.environ, **variables}, stdin=subprocess.DEVNULL,
                          capture_output=True, text=True, encoding="utf-8")


def count_listings(password: str) -> int:
    """The mart's row count as jobs_user, who only reads it."""
    with psycopg.connect(host=HOST, port=PORT, dbname="jobs_db", user="jobs_user", password=password) as conn:
        return conn.execute("SELECT count(*) FROM analytics.fct_postings").fetchone()[0]


@pytest.fixture(scope="module")
def runs():
    passwords = {role: secrets.token_hex(16) for role in ROLES}
    setup_variables = {
        "POSTGRES_HOST": CONTAINER_HOST,
        "POSTGRES_PORT": CONTAINER_PORT,
        "POSTGRES_USER": ADMIN,
        "POSTGRES_PASSWORD": ADMIN_PASSWORD,
        **{password_variable(role): passwords[role] for role in ROLES},
    }
    seed_variables = {
        "POSTGRES_HOST": CONTAINER_HOST,
        "POSTGRES_PORT": CONTAINER_PORT,
        "POSTGRES_DB": "jobs_db",
        "POSTGRES_USER": "analytics_user",
        "POSTGRES_PASSWORD": passwords["analytics_user"],
    }
    try:
        # stderr is the log, which holds no password; stdout is the image's report.
        setup = run_image(setup_variables, ["--passwords-from-env"])
        assert setup.returncode == 0, f"db-setup.py exited {setup.returncode}:\n{setup.stderr}"
        first = run_image(seed_variables, ["/app/seed-mart.py"], entrypoint="python")
        after_first = count_listings(passwords["jobs_user"]) if first.returncode == 0 else None
        second = run_image(seed_variables, ["/app/seed-mart.py"], entrypoint="python")
        after_second = count_listings(passwords["jobs_user"])
        return {"first": first, "second": second, "after_first": after_first, "after_second": after_second}
    finally:
        # Admin drops the mart so a rerun starts clean, whatever the runs did.
        with psycopg.connect(host=HOST, port=PORT, dbname="jobs_db", user=ADMIN, password=ADMIN_PASSWORD) as conn:
            conn.execute("DROP TABLE IF EXISTS analytics.fct_postings, analytics.fct_postings_cities, "
                         "analytics.fct_postings_skills")


def test_the_first_seed_exits_zero(runs):
    assert runs["first"].returncode == 0, f"seed-mart.py exited {runs['first'].returncode}:\n{runs['first'].stderr}"


def test_the_first_seed_loads_24_listings(runs):
    assert runs["after_first"] == LISTINGS, f"jobs_user counts {runs['after_first']} listings, want {LISTINGS}"


def test_the_second_seed_refuses_and_leaves_24_listings(runs):
    second = runs["second"]
    assert second.returncode != 0, "a second seed must refuse, but exited 0"
    assert "not seeding over them" in second.stderr, second.stderr
    assert runs["after_second"] == LISTINGS, f"jobs_user counts {runs['after_second']} listings, want {LISTINGS}"
