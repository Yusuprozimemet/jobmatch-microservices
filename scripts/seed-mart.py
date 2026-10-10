"""Load the test suite's sample mart into jobs_db, as scripts/seed-jobs.sh does for compose.

The jobs-seed task runs it from the db-setup image, with analytics_user's password in
POSTGRES_PASSWORD. The image holds the two fixtures in /app/fixtures, copied at build time from
services/identity-service/app/src/test/resources/fixtures.

The fixture drops and recreates the mart, so the script refuses when analytics.fct_postings already
has rows: those are listings the pipeline published, and the fixture would replace them.

Environment: POSTGRES_HOST (localhost), POSTGRES_PORT (5432), POSTGRES_DB (jobs_db), POSTGRES_USER,
POSTGRES_PASSWORD, and FIXTURES_DIR (/app/fixtures).

Requires: psycopg (the db-setup image installs it)
"""

import os
import sys
from pathlib import Path

import psycopg

FIXTURES = Path(os.getenv("FIXTURES_DIR") or "/app/fixtures")
# Schema first: the seed inserts into the tables it creates.
FILES = ("analytics-schema.sql", "analytics-seed.sql")


def count(conn: psycopg.Connection) -> int:
    return conn.execute("SELECT count(*) FROM analytics.fct_postings").fetchone()[0]


def main() -> int:
    with psycopg.connect(
        host=os.getenv("POSTGRES_HOST") or "localhost",
        port=int(os.getenv("POSTGRES_PORT") or 5432),
        dbname=os.getenv("POSTGRES_DB") or "jobs_db",
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
    ) as conn:
        # The connection's block is one transaction: a failed load commits nothing.
        if conn.execute("SELECT to_regclass('analytics.fct_postings') IS NOT NULL").fetchone()[0]:
            existing = count(conn)
            if existing:
                print(f"jobs_db already has {existing} listings; not seeding over them", file=sys.stderr)
                return 1
        for name in FILES:
            # No parameters, so the file's statements run as one script; the files hold no psql commands.
            conn.execute((FIXTURES / name).read_text(encoding="utf-8"))
        print(f"jobs_db has {count(conn)} listings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
