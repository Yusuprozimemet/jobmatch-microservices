# dbt Python model: `fct_title_discipline`

`dbt/models/marts/fct_postings_enriched.py` classifies titles with a dictionary.
This model does the same job with an LLM. Both are dbt Python models on
Databricks serverless — the difference is the rules, not the plumbing.

The model file lives in the dbt project (not in this folder) because dbt only
runs Python from `models/`. This README documents how to enable and operate it.

For prompt experiments before touching dbt, use
[`../llm_classify/llm_classify_dev.ipynb`](../llm_classify/README.md).

## What it does

- One row per **distinct title**, not per posting — thousands of postings collapse
  to hundreds of titles.
- **Incremental** — only new titles are classified after the first backfill.
- **Batched** — 200 titles per LiteLLM request (see `BATCH_SIZE` in the model).

Drop any of those three and you pay per posting per day instead of per distinct title.

## Credentials

Teachers provision `litellm-api-key` in Databricks secret scope `team_c`. Students
do not paste keys into the repo or `.env` for the dbt path.

| Runtime | Key source |
|---|---|
| `dbt build` (laptop, Astro, Airflow VM) | Databricks `team_c` / `litellm-api-key` |
| Local notebook (optional) | `az login` → Key Vault `litellm-key-team-c` |

Gateway models: `cheap`, `medium`, `strong` (override with `+llm_model:` in
`dbt_project.yml`).

## Switching it on

**1. Enable in `dbt_project.yml`:**

```yaml
models:
  final_project:
    marts:
      fct_title_discipline:
        +enabled: true
        +secret_scope: team_c
        +llm_model: cheap   # cheap | medium | strong
```

**2. Run tests** (no network, no key):

```bash
cd data
uv run pytest tests/dbt/test_fct_title_discipline.py
```

**3. Build once by hand** (~2–3 min serverless cold start + LLM):

```bash
cd data
uv sync --extra dbt
cd dbt && uv run --project .. --env-file ../.env dbt run --select fct_title_discipline
```

`fct_postings` must already exist in your dev schema. Set `+enabled: true` in
`dbt_project.yml` first — disabled models are skipped even when selected.

Then inspect (replace schema with your `DBT_SCHEMA`):

```sql
select discipline, count(*) as n
from team_c.<your_dbt_schema>.fct_title_discipline
group by discipline
```

If everything is `other`, check the Databricks run log before changing the prompt.

**4. Join in SQL** (same pattern as the dictionary model):

```sql
select
    p.*
    , coalesce(d.discipline, 'other') as discipline
from {{ ref('fct_postings') }} as p
left join {{ ref('fct_title_discipline') }} as d
    on d.title = p.title
```

Use `coalesce` so postings without a classification row still appear as `other`.

## Copying prompt changes from the notebook

When labels look good in `llm_classify_dev.ipynb`, copy the **`PROMPT`** string
into `dbt/models/marts/fct_title_discipline.py` by hand. Do not import from
`optional/` inside `models/` — dbt treats every `.py` there as a model.

## Limits and operations

- **Team virtual key** — 10 rpm / 20k tpm / $5 per day on the LiteLLM gateway.
  Batching at 200 titles keeps you under rate limits for normal pipeline volumes.
- **First backfill** — run `dbt build --select fct_title_discipline` yourself once.
  The scheduled task retries on failure; because the model is incremental, retries
  re-pay for batches that did not commit.
- **Non-determinism** — `temperature: 0` and a closed discipline list limit drift.
  Tests use `accepted_values` on the output; pin `+llm_model` rather than changing
  the file when you want stability.

## Related files

| Path | Role |
|---|---|
| `dbt/models/marts/fct_title_discipline.py` | Model implementation |
| `dbt/models/marts/_fct_title_discipline.yml` | Schema tests (`accepted_values` on `discipline`) |
| `tests/dbt/test_fct_title_discipline.py` | Unit tests (parsing, batching) |
| `tests/optional/test_llm_local.py` | Unit tests for `llm_local.classify` |
| `optional/llm_classify/` | Local notebook iteration |
