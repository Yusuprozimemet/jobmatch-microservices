# Pipeline health (Streamlit)

A small operations dashboard for your team — not the product UI (that is the frontend
trainee's job). Answers: is the pipeline healthy? When did data last land? How many rows?

Reads the published mart in Postgres (`BACKEND_PG_*` from `data/.env`).

## Files

| File | Role |
|---|---|
| `app.py` | Streamlit page — freshness and row counts for `fct_postings` |

## Setup

```bash
cd data
uv sync --extra dashboard
uv run streamlit run optional/streamlit/app.py
```

Uses the same `.env` as the rest of the project. Point `BACKEND_PG_PUBLISH_SCHEMA` at
`analytics` (scheduled) or `analytics_dev` (your publishes) depending on what you want to inspect.

## Related optional work

[`../dbt_results/README.md`](../dbt_results/README.md) lands test outcomes in the warehouse.
Joining that table into this dashboard is an optional stretch goal.
