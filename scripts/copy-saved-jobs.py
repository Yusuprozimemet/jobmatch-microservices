#!/usr/bin/env python3
"""Copy saved_jobs from project_db to application-service's apps_db.

Copies applications.saved_jobs from the monolith's project_db into apps_db. Rows already
in apps_db are skipped, never overwritten (so a second run changes nothing): a row there
may have been changed through application-service since the switch. Run once after
compose/production routes saved jobs to application-service and before V16 drops
project_db's table (Track F).

Connection details from CLI arguments or environment variables: POSTGRES_HOST, POSTGRES_PORT,
POSTGRES_USER, POSTGRES_PASSWORD. Options: --project-db (default project_db), --apps-db
(default apps_db). Requires: pip install "psycopg[binary]"

Example:
    ./copy-saved-jobs.py --host localhost --admin-user admin
"""

from __future__ import annotations

import argparse
import getpass
import logging
import os
import sys

try:
    import psycopg
except ImportError:
    sys.exit('❌ Error: psycopg is required. Install it with: pip install "psycopg[binary]"')

logging.basicConfig(level=logging.INFO, format="%(message)s")
_log = logging.getLogger("copy-saved-jobs")
DEFAULT_HOST, DEFAULT_PORT = "localhost", 5432


def step(msg: str, *args) -> None:
    _log.info("\n➡️  " + msg, *args)


def done(msg: str, *args) -> None:
    _log.info("✅ " + msg, *args)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=os.getenv("POSTGRES_HOST") or DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=int(os.getenv("POSTGRES_PORT") or DEFAULT_PORT))
    parser.add_argument("--admin-user", default=os.getenv("POSTGRES_USER"),
                        help="Postgres admin/superuser account")
    parser.add_argument("--admin-password", default=os.getenv("POSTGRES_PASSWORD"),
                        help="prompted for if omitted")
    parser.add_argument("--project-db", default="project_db", help="source database name")
    parser.add_argument("--apps-db", default="apps_db", help="destination database name")
    args = parser.parse_args()
    if not args.admin_user:
        parser.error("no admin user given (use --admin-user or POSTGRES_USER)")
    if not args.admin_password:
        args.admin_password = getpass.getpass(f"Password for {args.admin_user}: ")
    return args


def connect(
    args: argparse.Namespace, database: str
) -> psycopg.Connection:
    """Open a non-autocommit connection to the given database."""
    return psycopg.connect(
        host=args.host, port=args.port, dbname=database,
        user=args.admin_user, password=args.admin_password
    )


def table_exists(conn: psycopg.Connection, schema: str, table: str) -> bool:
    """Check if a table exists using to_regclass."""
    result = conn.execute(
        "SELECT to_regclass(%s) IS NOT NULL",
        (f"{schema}.{table}",)
    ).fetchone()
    return result[0] if result else False


def main() -> None:
    args = parse_args()
    project_conn = connect(args, args.project_db)
    project_conn.read_only = True

    if not table_exists(project_conn, "applications", "saved_jobs"):
        done("No applications.saved_jobs table in %s; nothing to copy", args.project_db)
        project_conn.close()
        return

    project_count = project_conn.execute(
        "SELECT count(*) FROM applications.saved_jobs"
    ).fetchone()[0]
    step("Found %d rows in project_db, copying to apps_db", project_count)

    apps_conn = connect(args, args.apps_db)
    try:
        apps_count_before = apps_conn.execute(
            "SELECT count(*) FROM applications.saved_jobs"
        ).fetchone()[0]

        with project_conn.cursor("copy_cursor") as cursor:
            cursor.execute(
                "SELECT user_id, posting_id, job_state::text "
                "FROM applications.saved_jobs"
            )
            with apps_conn.cursor() as insert_cursor:
                while True:
                    rows = cursor.fetchmany(1000)
                    if not rows:
                        break
                    insert_cursor.executemany(
                        "INSERT INTO applications.saved_jobs "
                        "(user_id, posting_id, job_state) VALUES (%s, %s, %s::applications.job_state) "
                        "ON CONFLICT (user_id, posting_id) DO NOTHING",
                        rows
                    )

        apps_count_after = apps_conn.execute(
            "SELECT count(*) FROM applications.saved_jobs"
        ).fetchone()[0]

        _log.info("")
        _log.info("project_db: %d rows", project_count)
        _log.info("apps_db before: %d rows", apps_count_before)
        _log.info("Copied: %d rows", apps_count_after - apps_count_before)
        _log.info("apps_db after: %d rows", apps_count_after)

        apps_conn.commit()
        done("Copy complete")

        if apps_count_after < project_count:
            _log.error(
                "❌ apps_db has %d rows but project_db has %d",
                apps_count_after, project_count
            )
            sys.exit(1)
    finally:
        project_conn.close()
        apps_conn.close()


if __name__ == "__main__":
    main()
