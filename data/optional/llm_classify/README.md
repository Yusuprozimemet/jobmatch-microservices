# Local LLM classification (notebook)

Prompt experiments happen here on your laptop. The dbt model
(`dbt/models/marts/fct_title_discipline.py`) runs the same classification on
Databricks serverless when you are ready to productionize.

You cannot run the dbt Python model as local Python — Databricks always executes
it on serverless. The notebook queries Databricks with `DATABRICKS_TOKEN` and
calls LiteLLM from your machine via `llm_local.py`.

## Files

| File | Role |
|---|---|
| `llm_classify_dev.ipynb` | Edit SQL sample, `PROMPT`, `MODEL`, `BATCH_SIZE` |
| `llm_local.py` | `query`, `list_models`, `chat`, `classify` — auth, batching, costs |

## Two runtimes, one prompt

| | Notebook | dbt `fct_title_discipline` |
|---|---|---|
| Where it runs | Your laptop | Databricks serverless |
| LiteLLM key | Key Vault / `LITELLM_API_KEY` | `dbutils.secrets` → `litellm-api-key` |
| Titles in | Any SQL you write (sample) | Distinct titles from `ref("fct_postings")` |
| Batch size | `BATCH_SIZE = 20` (fast iteration) | `BATCH_SIZE = 200` (fewer API calls; very large batches risk truncated JSON) |
| Output column | `job_categories` (your choice) | `discipline` (fixed schema) |
| Shared contract | `PROMPT` text + categories in the prompt | `PROMPT` constant in the `.py` file |

The notebook cannot `import` into the dbt model: serverless does not ship your
`optional/` package. Copy the **`PROMPT`** string (and categories in the prompt)
into `fct_title_discipline.py` by hand when labels look good.

## VS Code and Jupyter

General notebook background: [Jupyter Notebooks (HYF)](https://app.notion.com/p/hackyourfuture/Jupyter-Notebooks-ad5e9e6586b2458dbc9d699f990057b3?source=copy_link).

**1. Install the notebook stack** (once per machine, from `data/`):

```bash
cd data
uv sync --extra notebook
```

This installs `ipykernel` and `pandas` into `data/.venv` — that venv is the
kernel the notebook must use so `from optional.llm_classify.llm_local import …`
works.

**2. VS Code extensions**

Install the **Python** extension and **Jupyter** extension (Microsoft) if VS Code
does not already offer to run `.ipynb` cells.

**3. Open the notebook**

Open `data/optional/llm_classify/llm_classify_dev.ipynb` in VS Code.

**4. Select the kernel**

Top-right of the notebook: **Select Kernel** → **Python Environments** → pick
`data/.venv` (path should end with `data/.venv/bin/python`).

If it is missing:

```bash
cd data
uv run python -m ipykernel install --user --name=hyf-final-project --display-name="HYF final project (data/.venv)"
```

Then **Select Kernel** → **Jupyter Kernel** → **HYF final project (data/.venv)**.

**5. Run cells**

Use ▶ on a cell or **Shift+Enter**. The first LiteLLM call after idle may take
~60–90s; see [Cold start and timeouts](#cold-start-and-timeouts) below.

## Develop locally (recommended flow)

**1. Notebook — iterate on labels**

```bash
cd data
uv sync --extra notebook
az login   # HYF tenant (default ~/.azure profile)
```

Open `optional/llm_classify/llm_classify_dev.ipynb` in VS Code and select the
`data/.venv` kernel (see above).

1. Run the SQL cell — sample from `stg_postings` (edit filters / `SAMPLE_N`).
2. Optional: `list_models()` — ids like `cheap`, `medium`, `strong`.
3. Edit `CATEGORIES`, `PROMPT` (keep `{numbered_items}`), `MODEL`, `BATCH_SIZE`; run:

   ```python
   df["job_categories"] = classify(
       titles=df["title"],
       prompt=PROMPT,
       model=MODEL,
       batch_size=BATCH_SIZE,
       categories=CATEGORIES,
   )
   ```

**2. Copy prompt into dbt**

Paste the notebook `PROMPT` into the `PROMPT` constant in
`dbt/models/marts/fct_title_discipline.py`. Keep `{numbered_items}` and the same
category list. You do not copy `llm_local.py` — the dbt file has its own thin
`chat()` for serverless.

**3. Unit tests** (no network, no key):

```bash
cd data
uv run pytest tests/dbt/test_fct_title_discipline.py -v
```

**4. Enable and run on Databricks**

Set `+enabled: true` under `fct_title_discipline` in `dbt/dbt_project.yml`
(see [`../python_model/README.md`](../python_model/README.md) for full config).

`fct_postings` must already exist in your dev schema. Then:

```bash
cd data
uv sync --extra dbt
cd dbt && uv run --project .. --env-file ../.env dbt run --select fct_title_discipline
```

First serverless run is ~2–3 minutes (cold start + LLM batches). Inspect:

```sql
select discipline, count(*) as n
from team_c.<your_dbt_schema>.fct_title_discipline
group by discipline
order by n desc
```

Set `+enabled: false` again if your team is not using this model yet.

## Credentials

| Runtime | Key source | Student action |
|---|---|---|
| Notebook on laptop | `az login` → Key Vault `litellm-key-team-c` (or `LITELLM_API_KEY`) | `data/.env` with Databricks settings |
| `dbt run` on laptop | Databricks `team_c` / `litellm-api-key` | Enable model in `dbt_project.yml` |
| Astro / Airflow VM | Same Databricks secret | Same |

## Cold start and timeouts

The LiteLLM gateway on Azure Container Apps may scale to zero. The first call
after idle can take ~60–90s (sometimes longer). Default HTTP timeout is **300s**
in both `llm_local.py` and the dbt model; override with `LITELLM_TIMEOUT_SECONDS`.

## Related

- [`../python_model/README.md`](../python_model/README.md) — enable model, join in SQL, limits
- `tests/dbt/test_fct_title_discipline.py` — parsing and batching tests for the dbt model
