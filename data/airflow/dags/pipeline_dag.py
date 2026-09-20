"""Daily orchestration for the final project pipeline.

    ingest -> list_landing_files (SPIKE) -> dbt_build -> publish_to_backend

Each step is separate so that when dbt fails you re-run dbt, not the fetch, and
so the publish cannot run on a mart that failed its own tests. Enrichment is
not a task here: it is a dbt Python model, so `dbt_build` already runs it in
the right order. See data/dbt/models/marts/fct_postings_enriched.py.

`list_landing_files` is a temporary SPIKE: it logs every file under
LANDING_PATH via `read_files(..., format => 'binaryFile')` because dbt does
not print those paths itself. Remove the task when the experiment is done.

Settings come from Airflow Variables (Admin -> Variables), read when the task
runs. Secrets never do: each is fetched from Key Vault inside the task that
needs it, using the machine's identity. See data/README.md, "What runs in
Airflow".

The dev integration DAG (`final_project_pipeline_dev`) lives in
`pipeline_dag_dev.py` on the team VM only.
"""

from __future__ import annotations

import json
import logging
import os
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta

import pendulum
from airflow.sdk import Variable, dag, task
from alerts import slack_alert

logger = logging.getLogger(__name__)

DEFAULT_ARGS = {
    "owner": "data-team",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    # Inherited by every task, including the one you add at 11pm on a Thursday.
    "on_failure_callback": slack_alert,
}

# Astro mounts the project under /usr/local/airflow; the team VM uses
# /opt/airflow. Neither is hardcoded.
DBT_PROJECT_DIR = os.environ.get("DBT_PROJECT_DIR", "/opt/airflow/include/dbt")

# Pinned exactly, and they have to be: dbt-databricks 1.10.11 requires
# dbt-core <1.10.10, so a wildcard stops resolving on the next patch release.
# Bump the two together. uvx, because the Airflow image ships a newer Python
# than stable dbt-core supports.
DBT_RUNNER = "uvx --python 3.11 --from 'dbt-core==1.10.9' --with 'dbt-databricks==1.10.11' dbt"


@dataclass(frozen=True)
class PipelineProfile:
    """One runnable target: prod schedule or dev integration on the team VM."""

    dag_id: str
    description: str
    schedule: str | None
    tags: tuple[str, ...]
    aca_ingest_job_var: str
    aca_ingest_job_default: str
    dbt_schema_var: str
    dbt_schema_default: str
    landing_path_var: str
    landing_path_suffix: str  # landing/<volume>/<prefix> under the catalog (no source folder)
    ingest_mode_default: str
    backend_pg_user_var: str
    backend_pg_user_default: str
    backend_pg_publish_schema_var: str
    backend_pg_publish_schema_default: str
    backend_pg_secret_var: str
    backend_pg_secret_fallback: Callable[[str], str]


PROD_PROFILE = PipelineProfile(
    dag_id="final_project_pipeline",
    description="Ingest to the lakehouse, build and enrich dbt models, publish to the backend",
    schedule="0 9 * * *",
    tags=("final-project", "prod"),
    aca_ingest_job_var="ACA_INGEST_JOB",
    aca_ingest_job_default="job-fp-ingest",
    dbt_schema_var="DBT_SCHEMA",
    dbt_schema_default="analytics",
    landing_path_var="LANDING_PATH",
    landing_path_suffix="landing/prod",
    ingest_mode_default="aca",
    backend_pg_user_var="BACKEND_PG_USER",
    backend_pg_user_default="analytics_user",
    backend_pg_publish_schema_var="BACKEND_PG_PUBLISH_SCHEMA",
    backend_pg_publish_schema_default="analytics",
    backend_pg_secret_var="BACKEND_PG_SECRET",
    backend_pg_secret_fallback=lambda team: f"fp-pg-analytics-writer-{team}",
)


def dbt_command() -> str:
    """The dbt command, aimed at whoever is running it.

    The same rule as databricks_environment(), and it has to be the same rule
    or the two disagree: a token in the environment is you on your machine, so
    target `dev`; no token is the VM, so target `prod` and the team's service
    principal. Hardcoding `--target prod` made the documented local run fail
    with "Env var required but not provided: 'DATABRICKS_CLIENT_ID'", asking a
    laptop for a credential it is deliberately not allowed to have.
    """
    target = "dev" if os.environ.get("DATABRICKS_TOKEN") else "prod"
    return (
        f"{DBT_RUNNER} build --target {target} "
        f"--project-dir {DBT_PROJECT_DIR} --profiles-dir {DBT_PROJECT_DIR}"
    )


def setting(name: str, default: str | None = None) -> str:
    """One setting from Airflow Variables, environment as a local fallback.

    Called inside tasks, never at module scope: a DAG file is re-parsed every
    few seconds.
    """
    value = Variable.get(name, default=None) or os.environ.get(name) or default
    if value is None:
        raise RuntimeError(f"{name} is not set. Add it in the Airflow UI under Admin -> Variables.")
    return value


def team_slug() -> str:
    """Key Vault / Log Analytics suffix (team-a) from catalog name (team_a)."""
    return setting("DATABRICKS_CATALOG").replace("_", "-")


def secret(env_name: str, secret_name: str) -> str:
    """One secret: the environment first, then your team's Key Vault.

    On the VM nothing is in the environment, so every secret comes from Key
    Vault through the machine's own identity. On your laptop there is no such
    identity, so the same task reads what you put in `data/.env`. That is what
    lets you run a task locally without a copy of the DAG that skips the
    security.

    Never logged, never written to disk, and fetched inside the task rather
    than at parse time.
    """
    from_env = os.environ.get(env_name)
    if from_env:
        return from_env

    from src.common.aca import VAULT_SCOPE, azure_token

    token = azure_token(VAULT_SCOPE)
    url = (
        f"https://{setting('KEY_VAULT', 'kv-hyf-data')}.vault.azure.net"
        f"/secrets/{secret_name}?api-version=7.4"
    )
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    return json.load(urllib.request.urlopen(request, timeout=20))["value"]


def databricks_environment(profile: PipelineProfile) -> dict[str, str]:
    """Exactly what dbt/profiles.yml reads, plus what it needs to sign in."""
    where = {
        "DATABRICKS_HOST": setting("DATABRICKS_HOST"),
        "DATABRICKS_HTTP_PATH": setting("DATABRICKS_HTTP_PATH"),
        "DATABRICKS_CATALOG": setting("DATABRICKS_CATALOG"),
        "DBT_SCHEMA": setting(profile.dbt_schema_var, profile.dbt_schema_default),
        "LANDING_PATH": landing_path(profile),
    }
    if os.environ.get("DATABRICKS_TOKEN"):
        return where

    team = team_slug()
    return {
        **where,
        "AZURE_TENANT_ID": setting("AZURE_TENANT_ID"),
        "DATABRICKS_CLIENT_ID": secret("DATABRICKS_CLIENT_ID", f"fp-databricks-client-id-{team}"),
        "DATABRICKS_CLIENT_SECRET": secret(
            "DATABRICKS_CLIENT_SECRET", f"fp-databricks-client-secret-{team}"
        ),
    }


def landing_path(profile: PipelineProfile) -> str:
    catalog = setting("DATABRICKS_CATALOG")
    default = f"/Volumes/{catalog}/{profile.landing_path_suffix}"
    return setting(profile.landing_path_var, default)


def start_job(job_name: str) -> str:
    """Start one Container Apps job and wait for it."""
    from src.common.aca import azure_token, start_and_wait

    return start_and_wait(
        subscription=setting("AZURE_SUBSCRIPTION"),
        resource_group=setting("AZURE_RESOURCE_GROUP"),
        job_name=job_name,
        token=azure_token(),
        team=team_slug(),
    )


def ingest_mode(profile: PipelineProfile) -> str:
    """How ingest runs: local Python (`local`) or ACA job (`aca`)."""
    mode = setting("INGEST_MODE", profile.ingest_mode_default).strip().lower()
    if mode not in {"aca", "local"}:
        raise RuntimeError("INGEST_MODE must be 'aca' or 'local'.")
    return mode


def make_pipeline(profile: PipelineProfile):
    @dag(
        dag_id=profile.dag_id,
        description=profile.description,
        # Cron is evaluated in the start_date's timezone, so 09:00 stays 09:00
        # CET/CEST across the DST switch instead of drifting like a fixed UTC
        # offset would.
        start_date=pendulum.datetime(2026, 1, 1, tz="Europe/Amsterdam"),
        schedule=profile.schedule,
        catchup=False,
        # One run at a time. Airflow allows sixteen by default, and two runs would
        # build into the same dbt schema and both publish through the same
        # `fct_postings__staging` table, so whichever finished second would win and
        # the loser's rows would vanish. Triggering by hand while the scheduled run
        # is going is the normal way to meet this.
        max_active_runs=1,
        default_args=DEFAULT_ARGS,
        tags=list(profile.tags),
        is_paused_upon_creation=profile.schedule is None,
    )
    def pipeline():
        @task
        def ingest() -> str:
            """Fetch the source and land raw files.

            Mode `local`: run src.ingestion.pipeline in this Airflow worker.
            Mode `aca`: trigger the Container Apps ingest job and wait for it.
            """
            mode = ingest_mode(profile)
            if mode == "local":
                from src.ingestion import pipeline

                landed = pipeline.run()
                return f"local ingest landed {landed} records"

            job_name = setting(profile.aca_ingest_job_var, profile.aca_ingest_job_default)
            return start_job(job_name)

        @task
        def list_landing_files() -> int:
            """SPIKE: list every file under LANDING_PATH before dbt reads them.

            `read_files` does not print paths in dbt logs. This task runs the
            same path through `read_files(..., format => 'binaryFile')` so the
            Airflow log shows one line per file the staging model would open.
            Remove once the experiment is done.
            """
            from src.common.warehouse import Warehouse

            os.environ.update(databricks_environment(profile))
            path = landing_path(profile)
            safe = path.replace("'", "''")
            rows = Warehouse.from_env().run(
                "select path, length, modificationTime "
                f"from read_files('{safe}', format => 'binaryFile') "
                "order by path"
            )
            print(f"LANDING_PATH={path}")
            print(f"files_found={len(rows)}")
            for file_path, length, modified in rows:
                print(f"  file={file_path} bytes={length} modified={modified}")
            return len(rows)

        @task
        def dbt_build() -> str:
            """Build the models and run the tests."""
            import subprocess

            result = subprocess.run(
                dbt_command(),
                shell=True,
                check=False,
                env={**os.environ, **databricks_environment(profile)},
                text=True,
                capture_output=True,
                timeout=1800,
            )
            print(result.stdout[-8000:])
            if result.returncode != 0:
                print(result.stderr[-4000:])
                raise RuntimeError(f"dbt build exited {result.returncode}")

            summary = [line for line in result.stdout.splitlines() if "PASS=" in line]
            return summary[-1].strip() if summary else "dbt build finished"

        @task
        def publish_to_backend() -> int:
            """Copy allowlisted marts into the backend's database, atomically."""
            from src.publishing import sync

            os.environ.update(databricks_environment(profile))

            for name, default in (
                ("BACKEND_PG_HOST", ""),
                ("BACKEND_PG_PORT", "5432"),
                ("BACKEND_PG_DB", ""),
                ("BACKEND_PG_SSLMODE", "require"),
            ):
                value = setting(name, default)
                if value:
                    os.environ[name] = value

            os.environ["BACKEND_PG_USER"] = setting(
                profile.backend_pg_user_var, profile.backend_pg_user_default
            )
            os.environ["BACKEND_PG_PUBLISH_SCHEMA"] = setting(
                profile.backend_pg_publish_schema_var, profile.backend_pg_publish_schema_default
            )

            if not os.environ.get("BACKEND_PG_PASSWORD"):
                team = team_slug()
                secret_name = setting(
                    profile.backend_pg_secret_var, ""
                ) or profile.backend_pg_secret_fallback(team)
                os.environ["BACKEND_PG_PASSWORD"] = secret("BACKEND_PG_PASSWORD", secret_name)

            return sync.run(
                marts=[
                    ("fct_postings_enriched", "fct_postings"),
                    ("fct_postings_skills", "fct_postings_skills"),
                    ("fct_postings_cities", "fct_postings_cities"),
                    ("fct_postings_requirements", "fct_postings_requirements"),
                    ("fct_skill_popularity", "fct_skill_popularity"),
                ]
            )

        ingest() >> list_landing_files() >> dbt_build() >> publish_to_backend()

    return pipeline()


make_pipeline(PROD_PROFILE)
