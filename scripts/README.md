# Script shortcuts

These scripts wrap the commands documented in [`data/docs/dev_flow.md`](../data/docs/dev_flow.md) and the service READMEs. That guide uses **team-c** as the worked example; if you are on another team, set your team's values in `data/.env` before running.

## One-time setup

Make scripts executable:

```bash
chmod +x scripts/*.sh
```

## Commands

- scripts/dev-up.sh
  - Starts db, backend, frontend via docker compose.
- scripts/dev-down.sh
  - Stops the compose stack.
- scripts/preflight.sh
  - Verifies Azure login, Databricks token, dbt connectivity, and storage visibility.
- scripts/data-path-a.sh
  - Runs ingestion, blob check, dbt build, then publish (manual path A).
- scripts/data-path-b.sh
  - Restarts local Astro for DAG-driven execution (path B setup).
- scripts/run-all.sh
  - Runs preflight and then the full Path A sequence.
- scripts/spec-drift.py
  - Measures the migration from git history: how far the specs drifted from `plan.md`, how far spec and code sit apart, and each day's status and next step. Writes the data for the migration dashboard; rerun after every merge, and by `.github/workflows/dashboard.yml` for GitHub Pages. `--build dashboard.html` writes the page in [`docs/dashboard/`](../docs/dashboard/) as one file with the data inlined. Needs git, the `gh` CLI and numpy.

## Typical flow

```bash
scripts/dev-up.sh
scripts/run-all.sh
```
