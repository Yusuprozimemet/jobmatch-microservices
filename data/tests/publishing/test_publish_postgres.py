"""Real-Postgres tests of sync.publish into jobs_db.

The fake-connection tests in test_sync.py stay green with autocommit=True, so
they cannot see the window between the drop and the rename, when the table is
missing. These run the swap against a real server. A reader that arrives in
the middle has to wait on the publish's lock (a lock timeout here), never find
the table gone ("relation does not exist").

To run locally, against a container you throw away afterwards:

  docker run -d --rm --name publish-pg -p 55432:5432 \
    -e POSTGRES_HOST_AUTH_METHOD=trust postgres:18.4-alpine
  PUBLISH_TEST_ADMIN_DSN="host=localhost port=55432 user=postgres dbname=postgres" \
    uv run pytest -q tests/publishing
  docker stop publish-pg

It creates and drops jobs_db, analytics_user and jobs_user, and refuses to
start if any of them already exists. Never point it at a database you care
about.
"""

import os
import secrets
from collections.abc import Iterator
from dataclasses import dataclass

import psycopg
import pytest
from psycopg import conninfo
from psycopg.sql import SQL, Identifier, Literal

from src.publishing import sync

ADMIN_DSN = os.getenv("PUBLISH_TEST_ADMIN_DSN")
COLUMNS = [("posting_id", "STRING"), ("title", "STRING")]


@dataclass
class JobsDb:
    """Where the publish writes, as the two roles that use it."""

    analytics_dsn: str
    jobs_dsn: str


@pytest.fixture(scope="module")
def jobs_db() -> Iterator[JobsDb]:
    """jobs_db in the shape scripts/db-init/20-jobs-db.sh gives it."""
    if not ADMIN_DSN:
        # CI sets both. There, a misspelt variable must fail the run rather
        # than turn two tests into a quiet skip.
        if os.getenv("CI"):
            pytest.fail("PUBLISH_TEST_ADMIN_DSN is not set; see data-ci-cd.yaml")
        pytest.skip("PUBLISH_TEST_ADMIN_DSN not set: these need a throwaway Postgres")

    admin_dsn: str = ADMIN_DSN

    # Fresh passwords each run, so there is none in the repository to leak.
    passwords = {role: secrets.token_urlsafe(16) for role in ("analytics_user", "jobs_user")}

    with psycopg.connect(admin_dsn, autocommit=True) as admin:
        taken = admin.execute(
            "select 1 from pg_database where datname = 'jobs_db' union all "
            "select 1 from pg_roles where rolname in ('analytics_user', 'jobs_user')"
        ).fetchone()
        if taken:
            pytest.fail("jobs_db or its roles already exist: point this at a throwaway server")

        for role, password in passwords.items():
            admin.execute(
                SQL("create role {} login password {}").format(Identifier(role), Literal(password))
            )
        admin.execute("create database jobs_db")
        admin.execute("revoke connect on database jobs_db from public")
        admin.execute("grant connect on database jobs_db to jobs_user, analytics_user")

    # The admin creates the schema, because analytics_user has no CREATE on the
    # database. The publish drops and recreates the table, so jobs_user's read
    # has to be a default privilege: a plain GRANT is gone after the first swap.
    with psycopg.connect(admin_dsn, dbname="jobs_db", autocommit=True) as admin:
        admin.execute("create schema analytics authorization analytics_user")
        admin.execute("grant usage on schema analytics to jobs_user")
        admin.execute(
            "alter default privileges for role analytics_user in schema analytics "
            "grant select on tables to jobs_user"
        )

    def dsn(role: str) -> str:
        return conninfo.make_conninfo(
            admin_dsn, dbname="jobs_db", user=role, password=passwords[role]
        )

    yield JobsDb(analytics_dsn=dsn("analytics_user"), jobs_dsn=dsn("jobs_user"))

    with psycopg.connect(admin_dsn, autocommit=True) as admin:
        admin.execute("drop database jobs_db with (force)")
        admin.execute("drop role analytics_user")
        admin.execute("drop role jobs_user")


def read_as_jobs_user(jobs_db: JobsDb) -> list[tuple]:
    with psycopg.connect(jobs_db.jobs_dsn) as connection:
        return connection.execute(
            "select posting_id, title from analytics.fct_postings order by 1"
        ).fetchall()


def test_jobs_user_reads_after_a_second_publish(jobs_db: JobsDb):
    """The second publish replaces the table the first one created, grants and all."""
    sync.publish(jobs_db.analytics_dsn, "analytics", "fct_postings", COLUMNS, [["p1", "One"]])
    sync.publish(jobs_db.analytics_dsn, "analytics", "fct_postings", COLUMNS, [["p2", "Two"]])

    assert read_as_jobs_user(jobs_db) == [("p2", "Two")]


def test_the_swap_leaves_no_window(jobs_db: JobsDb, monkeypatch):
    """Between the drop and the rename, a reader waits: it never finds no table.

    The publish's connection is wrapped so that, just before the rename, a
    second connection asks for the table with a short lock_timeout. Inside the
    publish's transaction the old table is already dropped, so only that
    transaction's lock stands between the reader and "relation does not exist".
    """
    sync.publish(jobs_db.analytics_dsn, "analytics", "fct_postings", COLUMNS, [["p1", "One"]])

    real_connect = psycopg.connect
    probes: list[psycopg.Error | None] = []

    def probe() -> psycopg.Error | None:
        try:
            with real_connect(jobs_db.jobs_dsn, autocommit=True) as reader:
                reader.execute("set lock_timeout = '200ms'")
                reader.execute("select count(*) from analytics.fct_postings")
        except psycopg.Error as error:
            return error
        return None

    class Cursor:
        def __init__(self, cursor, connection):
            self.cursor, self.connection = cursor, connection

        def execute(self, statement, params=None):
            rendered = " ".join(statement.as_string(self.connection).split()).lower()
            if rendered.startswith("alter table") and " rename to " in rendered:
                probes.append(probe())
            return self.cursor.execute(statement, params)

        def executemany(self, statement, rows):
            return self.cursor.executemany(statement, rows)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return self.cursor.__exit__(*exc)

    class Connection:
        def __init__(self, connection):
            self.connection = connection

        def cursor(self):
            return Cursor(self.connection.cursor(), self.connection)

        def commit(self):
            return self.connection.commit()

        def close(self):
            return self.connection.close()

    monkeypatch.setattr(sync.psycopg, "connect", lambda *a, **k: Connection(real_connect(*a, **k)))
    sync.publish(jobs_db.analytics_dsn, "analytics", "fct_postings", COLUMNS, [["p2", "Two"]])
    monkeypatch.undo()

    assert len(probes) == 1, "the publish never reached its rename"
    assert isinstance(probes[0], psycopg.errors.LockNotAvailable), repr(probes[0])
    assert read_as_jobs_user(jobs_db) == [("p2", "Two")]
