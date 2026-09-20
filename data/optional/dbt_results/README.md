# dbt_results

dbt writes an account of every model and test to `target/run_results.json`, which then
sits on the machine that ran it. Landing it in the warehouse turns "are the tests passing?"
into a question anyone can answer with SQL.

Nothing reads the table yet. The health page in [`../streamlit/`](../streamlit/README.md)
queries Postgres only; pointing a panel at `<catalog>.ops.dbt_test_runs` is work you
would add yourself.

## Files

| File | Role |
|---|---|
| `dbt_results.py` | Copy into `src/` when you wire this up |
| `test_dbt_results.py` | Run with `uv run pytest optional/dbt_results` |

## Wire into the DAG

Copy `dbt_results.py` into `src/`, then add this to the `dbt_build` task in the DAG,
after dbt has run and before the exit code is checked:

```python
from src.dbt_results import parse_run_results, publish_results
from src.common.warehouse import Warehouse

# dbt got these settings as subprocess environment, which does not change this
# process. Without this line Warehouse.from_env() cannot find DATABRICKS_HOST
# and the task fails after a dbt run that went fine.
os.environ.update(databricks_environment())

results = parse_run_results(f"{DBT_PROJECT_DIR}/target/run_results.json")
publish_results(Warehouse.from_env(), results)
```

Publish before deciding the task's fate, so a failing test is recorded rather than lost.
Nothing in it raises: dbt's exit code already decides whether the pipeline failed.

## Permissions

The table lives in the `ops` schema, which the scheduled run owns. Your own account can
read it and not write it, so this runs in Airflow, not from your machine. Trying it
locally gives you `PERMISSION_DENIED`, which is the split working rather than something
misconfigured.
