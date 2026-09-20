# LLM enrichment model for job titles.
# Enabled in this project and used as a fallback when the source category is missing.
# It runs incrementally to avoid reclassifying titles unnecessarily.
# The model calls an LLM and requires an API key.
# It does the same job as src/enrichment/enrich.py.
# See ../../../optional/python_model/README.md and optional/llm_classify/llm_classify_dev.ipynb.
"""Classify job titles with an LLM, as a dbt model rather than a container.

Enable it in `dbt_project.yml` and it becomes a normal node in the graph:
`dbt build` runs it in order, `ref()` works, and you can test its output.

Local iteration (prompt / labels) happens in `optional/llm_classify/llm_classify_dev.ipynb`.
When labels look good, copy the PROMPT back here.

Wherever `dbt build` runs (laptop CLI, Astro Mode 1/2, Airflow VM), this model
executes on Databricks serverless and reads the API key from the team secret
scope (`litellm-api-key`). Teachers provision that secret; students do not set it.

Cost controls (skip any and the bill grows fast):
  1. One row per distinct title, not per posting.
  2. Incremental — only titles not yet classified.
  3. Batched — BATCH_SIZE titles per API call.

Its tests are in `tests/dbt/test_fct_title_discipline.py`, not next to this file:
dbt reads every `.py` under `models/` as a model and refuses one that defines
no `model()`, so a test file here stops the whole project from parsing.
"""

import json
import urllib.error
import urllib.request
from collections.abc import Callable

# Synced with optional/llm_classify/llm_classify_dev.ipynb — copy prompt changes both ways.
CATEGORIES = (
    "data_engineering",
    "data_science",
    "data_analytics",
    "ai_engineering",
    "ml_ai",
    "software_engineering",
    "backend",
    "frontend",
    "fullstack",
    "mobile",
    "devops",
    "security",
    "qa",
    "architecture",
    "network_engineering",
    "hardware",
    "product",
    "project_management",
    "management",
    "business_analysis",
    "design",
    "sales",
    "marketing",
    "operations",
    "support",
    "solutions_engineering",
    "finance",
    "hr",
    "recruiting",
    "legal",
    "technical_writing",
    "developer_relations",
    "blockchain",
    "other",
)

PROMPT = (
    "Classify each job title into exactly one job discipline.\n"
    f"Allowed disciplines: {', '.join(CATEGORIES)}.\n"
    "Choose the most specific discipline that matches the job title.\n"
    "Use other only when the title does not provide enough information "
    "or does not fit any allowed discipline.\n"
    'Reply with JSON only, like {"0": "backend", "1": "data_engineering"}.\n'
    "Use the numbers below as keys.\n\n"
    "{numbered_items}"
)

ENDPOINT = (
    "https://app-litellm-team-d.blacksky-9263d113.westeurope.azurecontainerapps.io"
    "/v1/chat/completions"
)
MODEL = "cheap"
BATCH_SIZE = 200
HTTP_TIMEOUT = 300
SECRET_KEY_NAME = "litellm-api-key"


def _numbered_items(items: list[str]) -> str:
    """Format titles as numbered lines the model uses as JSON keys (0, 1, …)."""
    return "\n".join(f"{index}. {item}" for index, item in enumerate(items))


def _fill_prompt(items: list[str]) -> str:
    """Insert a batch of titles into PROMPT via the `{numbered_items}` placeholder."""
    return PROMPT.replace("{numbered_items}", _numbered_items(items))


def _parse_labels(content: str, count: int) -> list[str]:
    """Extract labels from one batched JSON answer; unknown values become `other`."""
    start, end = content.find("{"), content.rfind("}")
    if start == -1 or end == -1:
        raise ValueError(f"no JSON in the answer: {content[:120]!r}")
    answer = json.loads(content[start : end + 1])
    labels = []
    for index in range(count):
        label = str(answer.get(str(index), "other")).strip().lower()
        labels.append(label if label in CATEGORIES else "other")
    return labels


def chat(prompt: str, api_key: str, model: str) -> str:
    """Send one request to the team LiteLLM gateway; return the assistant message text."""
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(
            {"model": model, "temperature": 0, "messages": [{"role": "user", "content": prompt}]}
        ).encode(),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 429:
            raise RuntimeError(
                f"LiteLLM refused the request (429). Either the team's "
                f"$5/day budget is spent, or rpm/tpm limits were hit. "
                f"Gateway said: {error.read().decode()[:300]}"
            ) from error
        raise RuntimeError(f"LiteLLM returned {error.code}") from error
    return body["choices"][0]["message"]["content"]


def _classify_batch(
    batch: list[str],
    api_key: str,
    model: str,
    *,
    call: Callable[[str, str, str], str] = chat,
) -> dict[str, str]:
    """Classify one batch: build prompt, call LiteLLM, map titles to labels."""
    response = call(_fill_prompt(batch), api_key, model)
    labels = _parse_labels(response, len(batch))
    return dict(zip(batch, labels, strict=True))


def classify_titles(
    titles: list[str],
    api_key: str,
    model: str,
    *,
    call: Callable[[str, str, str], str] = chat,
) -> dict[str, str]:
    """Classify every title in batches of BATCH_SIZE; same flow as the dev notebook."""
    result: dict[str, str] = {}
    for start in range(0, len(titles), BATCH_SIZE):
        batch = titles[start : start + BATCH_SIZE]
        result.update(_classify_batch(batch, api_key, model, call=call))
    return result


def model(dbt, session):
    """Incremental table of distinct titles and their LLM-assigned discipline."""
    dbt.config(
        materialized="incremental",
        unique_key="title",
        submission_method="serverless_cluster",
    )

    postings = dbt.ref("int_postings").filter("source_category IS NULL").select("title").distinct()

    if dbt.is_incremental:
        seen = session.table(f"{dbt.this}").select("title")
        postings = postings.join(seen, on="title", how="left_anti")

    titles = [row["title"] for row in postings.collect() if row["title"]]
    if not titles:
        return session.createDataFrame([], "title string, discipline string")

    scope = dbt.config.get("secret_scope")
    if not scope:
        raise RuntimeError(
            "secret_scope is not set. Add `secret_scope: team_c` to this model's config."
        )
    api_key = dbutils.secrets.get(scope=scope, key=SECRET_KEY_NAME)  # noqa: F821

    classified = classify_titles(titles, api_key, dbt.config.get("llm_model") or MODEL)
    return session.createDataFrame(list(classified.items()), "title string, discipline string")
