#!/usr/bin/env python3
"""Set up the Postgres databases, their schemas, roles and permissions.

Creates three databases. 'identity_db' has the 'app' schema, owned by 'app_user'. 'jobs_db'
(Day 20) has the marts: 'analytics', owned by 'analytics_user', and 'analytics_dev', owned
by 'analytics_dev_user'. Only those two roles and 'jobs_user' may connect to 'jobs_db'.
'apps_db' (Day 25) has 'applications', owned by 'applications_user'; only it may connect,
and it may not connect to 'identity_db'.
Each role has full access to the schemas it owns and read-only access to the others in
the same database, for both existing and future objects, except the module schemas below,
which only their owner reads.

Each backend module has a schema and a role too (Day 11): 'identity_user' owns
'identity', 'applications_user' 'applications', 'matching_user' 'matching', and
'jobs_user' owns nothing and only reads: job-service reads the mart as it, from 'jobs_db'.
No other role reads a module's schema (Day 38):
each module is to leave the process with its login. 'app_user' still runs the migrations that
move the tables out of 'app', so it is made a member of the three module roles:
moving a table into a schema and handing it to that schema's role needs both.

The third role is the reason there are two analytics schemas. Trainees write
'analytics_dev' by hand while they build a mart; the scheduled pipeline writes
'analytics', which job-service reads. Giving both schemas to one role would mean
handing a trainee the credential that owns production, so they get their own.

The script is idempotent: re-running it never changes existing state, so a
failed run can simply be repeated. Existing roles keep their current password
unless you confirm a reset when asked. A database set up before Day 20 keeps its
analytics schemas; the runbook, docs/runbooks/jobs-db.md, drops them. One set up before Day 28
is still named 'project_db': rename it first (docs/runbooks/identity-db.md), or this script
creates an empty 'identity_db' beside it.

For an unattended run, such as an ECS task, --passwords-from-env takes each role's
password from DB_PASSWORD_<ROLE> (DB_PASSWORD_APP_USER, etc.) and the admin's from
POSTGRES_PASSWORD. It generates none, prints none, sets roles that already exist to the
given password, and exits before connecting when one is missing.

Connection details come from CLI arguments or environment variables:

    POSTGRES_HOST  POSTGRES_PORT  POSTGRES_USER  POSTGRES_PASSWORD

Requires: pip install "psycopg[binary]"

Example:
    ./db-setup.py --host localhost --admin-user admin
"""

from __future__ import annotations

import argparse
import getpass
import logging
import os
import secrets
import string
import sys
from typing import NamedTuple

try:
    import psycopg
    from psycopg import sql
except ImportError:
    sys.exit('❌ Error: psycopg is required. Install it with: pip install "psycopg[binary]"')

# --- Configuration ---------------------------------------------------------

NEW_DATABASE = "identity_db"  # the primary database this script creates
JOBS_DATABASE = "jobs_db"  # the analytics mart database
APPS_DATABASE = "apps_db"  # application-service's database (Day 25)
MAINTENANCE_DATABASE = "postgres"  # the database connected to while creating it

APP_SCHEMA = "app"
ANALYTICS_SCHEMA = "analytics"
ANALYTICS_DEV_SCHEMA = "analytics_dev"
APP_ROLE = "app_user"
ANALYTICS_ROLE = "analytics_user"
ANALYTICS_DEV_ROLE = "analytics_dev_user"

# One schema and one role per backend module, the role named after the schema.
MODULE_SCHEMAS = ("identity", "applications", "matching")
MODULE_ROLES = tuple(f"{schema}_user" for schema in MODULE_SCHEMAS)
# The jobs module reads the analytics mart and owns nothing.
JOBS_ROLE = "jobs_user"

ROLES = (APP_ROLE, ANALYTICS_ROLE, ANALYTICS_DEV_ROLE, *MODULE_ROLES, JOBS_ROLE)

# Roles that can connect to jobs_db (job-service, the pipeline and trainees).
JOBS_ROLES = (ANALYTICS_ROLE, ANALYTICS_DEV_ROLE, JOBS_ROLE)

# Roles that can connect to apps_db (application-service).
APPLICATIONS_ROLE = "applications_user"
APPS_ROLES = (APPLICATIONS_ROLE,)

# The only roles granted CONNECT on identity_db (Day 42).
IDENTITY_DB_ROLES = ("identity_user",)

# Schemas in identity_db, and the role that owns each: app and the module schemas.
# A role gets full access to schemas it owns and read-only access to the others,
# so adding a schema here is the only edit needed.
SCHEMA_OWNERS = {
    APP_SCHEMA: APP_ROLE,
    **dict(zip(MODULE_SCHEMAS, MODULE_ROLES)),
}

# Schemas in jobs_db: the analytics marts. Same pattern as SCHEMA_OWNERS.
JOBS_SCHEMA_OWNERS = {
    ANALYTICS_SCHEMA: ANALYTICS_ROLE,
    ANALYTICS_DEV_SCHEMA: ANALYTICS_DEV_ROLE,
}

# Schemas in apps_db: application-service's tables.
APPS_SCHEMA_OWNERS = {
    "applications": APPLICATIONS_ROLE,
}


class Privileges(NamedTuple):
    """What a role may do in a schema: on the schema itself, then per object type.

    The values are SQL keywords pasted into statements, so they must stay
    trusted constants and never come from user input.
    """

    label: str  # how the grant is described in the log
    on_schema: str
    on_objects: dict[str, str]


# "ALL" on the schema (USAGE + CREATE) plus ownership of it is what lets a role
# do everything inside: create tables, views, indexes, constraints, functions.
FULL_ACCESS = Privileges("full access", "ALL",
                         {"TABLES": "ALL", "SEQUENCES": "ALL", "FUNCTIONS": "ALL"})
READ_ONLY = Privileges("read-only access", "USAGE",
                       {"TABLES": "SELECT", "SEQUENCES": "SELECT"})

PASSWORD_LENGTH = 32

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 5432


# --- Logging ---------------------------------------------------------------

logging.basicConfig(level=logging.INFO, format="%(message)s")
_log = logging.getLogger("db-setup")


def step(message: str, *args) -> None:
    """Log a task that is starting, preceded by a blank line separating sections.

    Keeping the spacing here rather than printing it in main() means it always
    lands on the log stream, in order, even when the output is piped.
    """
    _log.info("\n➡️  " + message, *args)


def done(message: str, *args) -> None:
    _log.info("✅ " + message, *args)


def skipped(message: str, *args) -> None:
    """Log a task that had nothing to do because the state already matched."""
    _log.info("⚪ " + message, *args)


def warned(message: str, *args) -> None:
    _log.warning("⚠️  " + message, *args)


def failed(message: str, *args) -> None:
    _log.error("❌ " + message, *args)


# --- Helpers ---------------------------------------------------------------

def password_variable(role: str) -> str:
    """Return the environment variable name for a role's password."""
    return f"DB_PASSWORD_{role.upper()}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    # `or` rather than a getenv default, so an empty variable falls back too.
    parser.add_argument("--host", default=os.getenv("POSTGRES_HOST") or DEFAULT_HOST)
    parser.add_argument("--port", type=int,
                        default=int(os.getenv("POSTGRES_PORT") or DEFAULT_PORT))
    parser.add_argument("--admin-user", default=os.getenv("POSTGRES_USER"),
                        help="Postgres admin/superuser account")
    parser.add_argument("--admin-password", default=os.getenv("POSTGRES_PASSWORD"),
                        help="prompted for if omitted")
    parser.add_argument("--passwords-from-env", action="store_true",
                        help="read role passwords from DB_PASSWORD_<ROLE> variables")

    args = parser.parse_args()
    if not args.admin_user:
        parser.error("no admin user given (use --admin-user or POSTGRES_USER)")

    if args.passwords_from_env:
        # Every missing one named at once, before any connection.
        missing = []
        if not args.admin_password:
            missing.append("POSTGRES_PASSWORD (or --admin-password)")
        for role in ROLES:
            if not os.getenv(password_variable(role)):
                missing.append(password_variable(role))
        if missing:
            parser.error(f"missing environment variable(s): {', '.join(missing)}")
        args.role_passwords = {role: os.getenv(password_variable(role)) for role in ROLES}
    else:
        args.role_passwords = None
        if not args.admin_password:
            args.admin_password = getpass.getpass(f"Password for {args.admin_user}: ")
    return args


def connect(args: argparse.Namespace, database: str) -> psycopg.Connection:
    """Open an autocommit connection (CREATE DATABASE cannot run in a transaction)."""
    return psycopg.connect(host=args.host, port=args.port, dbname=database,
                           user=args.admin_user, password=args.admin_password,
                           autocommit=True)


def execute(conn: psycopg.Connection, statement: str, **placeholders) -> None:
    conn.execute(sql.SQL(statement).format(**placeholders))


def database_exists(conn: psycopg.Connection, database: str) -> bool:
    return conn.execute("SELECT 1 FROM pg_database WHERE datname = %s",
                        (database,)).fetchone() is not None


def role_exists(conn: psycopg.Connection, role: str) -> bool:
    return conn.execute("SELECT 1 FROM pg_roles WHERE rolname = %s",
                        (role,)).fetchone() is not None


def generate_password() -> str:
    PASSWORD_ALPHABET = string.ascii_letters + string.digits
    return "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(PASSWORD_LENGTH))


# --- Steps -----------------------------------------------------------------

def create_database(conn: psycopg.Connection, database: str) -> None:
    step("Creating database '%s'", database)
    if database_exists(conn, database):
        skipped("Database '%s' already exists", database)
        return
    execute(conn, "CREATE DATABASE {database}", database=sql.Identifier(database))
    done("Created database '%s'", database)


def revoke_connect(conn: psycopg.Connection, database: str) -> None:
    """Revoke CONNECT from PUBLIC, so only explicitly granted roles can connect."""
    execute(conn, "REVOKE CONNECT ON DATABASE {database} FROM PUBLIC",
            database=sql.Identifier(database))
    done("Revoked CONNECT on database '%s' from PUBLIC", database)


def grant_connect(conn: psycopg.Connection, database: str, roles: list[str]) -> None:
    execute(conn, "GRANT CONNECT ON DATABASE {database} TO {roles}",
            database=sql.Identifier(database),
            roles=sql.SQL(", ").join(map(sql.Identifier, roles)))
    done("Granted CONNECT on database '%s' to %s", database, ", ".join(roles))


def confirm_password_reset(roles: list[str]) -> bool:
    """Ask whether existing roles should get freshly generated passwords."""
    warned("Roles already exist: %s", ", ".join(roles))
    if not sys.stdin.isatty():
        skipped("Not an interactive terminal, keeping the current passwords")
        return False

    # Prompt on stderr, with the log output, so stdout stays limited to the report.
    print("   Reset their passwords? Clients using the current ones will stop "
          "working [y/N]: ", end="", file=sys.stderr, flush=True)
    try:
        return input().strip().lower() in ("y", "yes")
    except EOFError:  # no answer given, keep the safe default
        print(file=sys.stderr)
        return False


def create_role(conn: psycopg.Connection, role: str, password: str | None = None) -> str:
    """Create a login role with a password, generated if not provided."""
    if password is None:
        password = generate_password()
    execute(conn, "CREATE ROLE {role} LOGIN PASSWORD {password}",
            role=sql.Identifier(role), password=sql.Literal(password))
    done("Created role '%s'", role)
    return password


def reset_password(conn: psycopg.Connection, role: str) -> str:
    """Replace an existing role's password with a randomly generated one."""
    password = generate_password()
    execute(conn, "ALTER ROLE {role} PASSWORD {password}",
            role=sql.Identifier(role), password=sql.Literal(password))
    done("Reset the password of role '%s'", role)
    return password


def setup_roles(conn: psycopg.Connection, roles: list[str],
                role_passwords: dict[str, str] | None = None) -> dict[str, str | None]:
    """Create the missing roles, and offer to reset the passwords of existing ones.

    Maps each role to its new password, or to None when an existing password was
    left untouched - so an unattended re-run still changes nothing. With role_passwords
    (--passwords-from-env), every role gets its given password and nothing is asked.
    """
    existing = [role for role in roles if role_exists(conn, role)]
    given = role_passwords is not None
    reset = (not given) and bool(existing) and confirm_password_reset(existing)

    passwords: dict[str, str | None] = {}
    for role in roles:
        password = role_passwords[role] if given else None
        if role not in existing:
            passwords[role] = create_role(conn, role, password)
        elif given:
            execute(conn, "ALTER ROLE {role} PASSWORD {password}",
                    role=sql.Identifier(role), password=sql.Literal(password))
            done("Set the password of role '%s' from %s", role, password_variable(role))
            passwords[role] = password
        elif reset:
            passwords[role] = reset_password(conn, role)
        else:
            skipped("Role '%s' already exists, password left unchanged", role)
            passwords[role] = None
    return passwords


def grant_role_membership(conn: psycopg.Connection, roles: list[str], member: str) -> None:
    """Make the admin a member of the new roles, unless it is a superuser.

    Both ALTER SCHEMA ... OWNER TO and ALTER DEFAULT PRIVILEGES FOR ROLE require
    membership in the target role. Superusers are exempt; the restricted admin
    accounts of managed Postgres services are not.
    """
    if conn.execute("SELECT current_setting('is_superuser') = 'on'").fetchone()[0]:
        return

    for role in roles:
        execute(conn, "GRANT {role} TO {member}",
                role=sql.Identifier(role), member=sql.Identifier(member))
    done("Granted membership of %s to '%s'", ", ".join(roles), member)


def create_schema(conn: psycopg.Connection, schema: str, owner: str) -> None:
    execute(conn, "CREATE SCHEMA IF NOT EXISTS {schema}", schema=sql.Identifier(schema))
    execute(conn, "ALTER SCHEMA {schema} OWNER TO {owner}",
            schema=sql.Identifier(schema), owner=sql.Identifier(owner))
    done("Schema '%s' ready, owned by '%s'", schema, owner)


def grant_access(conn: psycopg.Connection, schema: str, role: str,
                 privileges: Privileges, creators: list[str]) -> None:
    """Grant `privileges` on a schema's existing and future objects to `role`."""
    execute(conn, "GRANT {privilege} ON SCHEMA {schema} TO {role}",
            privilege=sql.SQL(privileges.on_schema), schema=sql.Identifier(schema),
            role=sql.Identifier(role))

    for objects, privilege in privileges.on_objects.items():
        execute(conn, "GRANT {privilege} ON ALL {objects} IN SCHEMA {schema} TO {role}",
                privilege=sql.SQL(privilege), objects=sql.SQL(objects),
                schema=sql.Identifier(schema), role=sql.Identifier(role))
        # Default privileges apply only to objects created by one specific role,
        # so they have to be registered for every role that creates objects here.
        for creator in creators:
            execute(conn,
                    "ALTER DEFAULT PRIVILEGES FOR ROLE {creator} IN SCHEMA {schema} "
                    "GRANT {privilege} ON {objects} TO {role}",
                    creator=sql.Identifier(creator), schema=sql.Identifier(schema),
                    privilege=sql.SQL(privilege), objects=sql.SQL(objects),
                    role=sql.Identifier(role))

    done("Granted %s on schema '%s' to '%s'", privileges.label, schema, role)


def report(args: argparse.Namespace, passwords: dict[str, str | None]) -> None:
    unchanged = "(unchanged)"
    print(f"\n✅ Setup complete on {args.host}:{args.port}\n")
    print(f"  {NEW_DATABASE} : {', '.join(SCHEMA_OWNERS)}")
    print(f"  {JOBS_DATABASE:<{len(NEW_DATABASE)}} : {', '.join(JOBS_SCHEMA_OWNERS)}")
    print(f"  {APPS_DATABASE:<{len(NEW_DATABASE)}} : {', '.join(APPS_SCHEMA_OWNERS)}\n")
    for role in ROLES:
        full, read_only = [], []
        for database, owners in ((NEW_DATABASE, SCHEMA_OWNERS),
                                 (JOBS_DATABASE, JOBS_SCHEMA_OWNERS),
                                 (APPS_DATABASE, APPS_SCHEMA_OWNERS)):
            if database == JOBS_DATABASE and role not in JOBS_ROLES:
                continue
            if database == APPS_DATABASE and role not in APPS_ROLES:
                continue
            for schema, owner in owners.items():
                if owner == role:
                    full.append(f"{database}.{schema}")
                elif schema not in MODULE_SCHEMAS:
                    read_only.append(f"{database}.{schema}")
        print(f"  {role}")
        if args.role_passwords:
            password_display = f"from {password_variable(role)}"
        else:
            password_display = passwords[role] or unchanged
        print(f"    password : {password_display}")
        access = [f"full on {', '.join(full)}"] if full else []
        access += [f"read-only on {', '.join(read_only)}"] if read_only else []
        print(f"    access   : {' — '.join(access)}")


# --- Entry point -----------------------------------------------------------

def main() -> None:
    args = parse_args()
    roles = list(ROLES)

    step("Connecting to %s:%s as '%s'", args.host, args.port, args.admin_user)
    with connect(args, MAINTENANCE_DATABASE) as conn:
        done("Connected to PostgreSQL %s",
             conn.execute("SHOW server_version").fetchone()[0])

        create_database(conn, NEW_DATABASE)
        create_database(conn, JOBS_DATABASE)
        create_database(conn, APPS_DATABASE)

        # Roles live in the cluster, not in the database, so create them here.
        step("Creating roles: %s", ", ".join(roles))
        passwords = setup_roles(conn, roles, args.role_passwords)
        grant_role_membership(conn, roles, args.admin_user)
        # app_user runs the migrations that move tables into the module schemas and hand them
        # over (OWNER TO), which both need membership. Not a superuser, so always granted.
        for role in MODULE_ROLES:
            execute(conn, "GRANT {role} TO {member}",
                    role=sql.Identifier(role), member=sql.Identifier(APP_ROLE))
        done("'%s' is a member of %s", APP_ROLE, ", ".join(MODULE_ROLES))

        # Database-level grants. identity_db: only identity_user (Day 42); app_user, which runs
        # the migrations, connects as a member of identity_user, granted above.
        # jobs_db: only analytics roles and jobs_user. apps_db: only applications_user.
        revoke_connect(conn, NEW_DATABASE)
        # Earlier runs granted it by name, so revoking PUBLIC alone would leave it connecting.
        for role in roles:
            if role not in IDENTITY_DB_ROLES:
                execute(conn, "REVOKE CONNECT ON DATABASE {database} FROM {role}",
                        database=sql.Identifier(NEW_DATABASE), role=sql.Identifier(role))
                done("Revoked CONNECT on database '%s' from %s", NEW_DATABASE, role)
        grant_connect(conn, NEW_DATABASE, list(IDENTITY_DB_ROLES))
        revoke_connect(conn, JOBS_DATABASE)
        grant_connect(conn, JOBS_DATABASE, list(JOBS_ROLES))
        revoke_connect(conn, APPS_DATABASE)
        grant_connect(conn, APPS_DATABASE, list(APPS_ROLES))

    with connect(args, NEW_DATABASE) as conn:
        step("Creating schemas in %s:", NEW_DATABASE)
        for schema, owner in SCHEMA_OWNERS.items():
            create_schema(conn, schema, owner)

        step("Granting schema privileges in %s", NEW_DATABASE)
        creators = [args.admin_user, *roles]
        for schema, owner in SCHEMA_OWNERS.items():
            for role in roles:
                if role == owner:
                    grant_access(conn, schema, role, FULL_ACCESS, creators)
                elif schema not in MODULE_SCHEMAS:
                    grant_access(conn, schema, role, READ_ONLY, creators)

    with connect(args, JOBS_DATABASE) as conn:
        step("Creating schemas in %s:", JOBS_DATABASE)
        for schema, owner in JOBS_SCHEMA_OWNERS.items():
            create_schema(conn, schema, owner)

        step("Granting schema privileges in %s", JOBS_DATABASE)
        # The admin and the analytics roles are the creators here.
        creators = [args.admin_user, *JOBS_ROLES]
        for schema, owner in JOBS_SCHEMA_OWNERS.items():
            # The owner gets full access; jobs_user and the other analytics role get read-only.
            for role in JOBS_ROLES:
                if role == owner:
                    grant_access(conn, schema, role, FULL_ACCESS, creators)
                else:
                    grant_access(conn, schema, role, READ_ONLY, creators)

    with connect(args, APPS_DATABASE) as conn:
        step("Creating schemas in %s:", APPS_DATABASE)
        for schema, owner in APPS_SCHEMA_OWNERS.items():
            create_schema(conn, schema, owner)

        step("Granting schema privileges in %s", APPS_DATABASE)
        # The admin and applications_user are the creators here.
        creators = [args.admin_user, *APPS_ROLES]
        for schema, owner in APPS_SCHEMA_OWNERS.items():
            grant_access(conn, schema, owner, FULL_ACCESS, creators)

    report(args, passwords)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        failed("Cancelled")
        sys.exit(130)
    except psycopg.OperationalError as error:
        failed("Could not connect to Postgres: %s", str(error).strip())
        sys.exit(1)
    except Exception as error:  # noqa: BLE001 - top-level guard for a CLI script
        failed("Setup failed: %s: %s", type(error).__name__, str(error).strip())
        sys.exit(1)
