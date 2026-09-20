"""Local LiteLLM helpers for optional/llm_classify/llm_classify_dev.ipynb.

Infrastructure only: Databricks reads, LiteLLM auth, cost printing.
Students edit the prompt, model, and batch size in the notebook; batching lives here.
"""

from __future__ import annotations

import json
import os
import subprocess
import urllib.error
import urllib.request
from collections.abc import Callable
from functools import lru_cache, wraps
from pathlib import Path
from typing import ParamSpec

try:
    import pandas as pd
except ImportError as error:
    raise ImportError(
        "pandas is required for llm_classify: run `uv sync --extra notebook` from data/"
    ) from error

from dotenv import load_dotenv

from src.common.warehouse import Warehouse

P = ParamSpec("P")

ENDPOINT = (
    "https://app-litellm-team-d.blacksky-9263d113.westeurope.azurecontainerapps.io"
    "/v1/chat/completions"
)
DEFAULT_MODEL = "cheap"
# Synced with fct_title_discipline.py HTTP_TIMEOUT — ACA cold starts can take minutes.
DEFAULT_HTTP_TIMEOUT = 300


def _http_timeout() -> int:
    return int(os.getenv("LITELLM_TIMEOUT_SECONDS", str(DEFAULT_HTTP_TIMEOUT)))


def print_chat_cost(func: Callable[P, dict]) -> Callable[P, dict]:
    """Print token usage and cost after every chat call."""

    @wraps(func)
    def wrapper(*args: P.args, **kwargs: P.kwargs) -> dict:
        result = func(*args, **kwargs)
        model = result.get("model", "?")
        tokens = result.get("prompt_tokens", 0) + result.get("completion_tokens", 0)
        cost = result.get("cost_usd")
        if cost is not None:
            print(f"[{model}] {tokens:,} tokens, ${cost:.6f}")
        else:
            print(f"[{model}] {tokens:,} tokens, cost not returned by gateway")
        return result

    return wrapper


@lru_cache(maxsize=1)
def _ensure_env() -> None:
    data_dir = Path(__file__).resolve().parent.parent.parent
    load_dotenv(data_dir / ".env")


def _default_model(model: str | None) -> str:
    return model or os.getenv("LITELLM_MODEL", DEFAULT_MODEL)


def _team_letter() -> str:
    if letter := os.getenv("TEAM_LETTER"):
        return letter
    catalog = os.getenv("DATABRICKS_CATALOG", "")
    if catalog.startswith("team_") and len(catalog) > len("team_"):
        return catalog.split("_", 1)[1]
    return "d"


def _litellm_key() -> str:
    if key := os.getenv("LITELLM_API_KEY"):
        return key
    vault = os.getenv("KV_VAULT", "kv-hyf-data")
    team = _team_letter()
    # Use the default Azure CLI profile (~/.azure). Teachers with a custom
    # profile can export AZURE_CONFIG_DIR before running the notebook.
    return subprocess.check_output(
        [
            "az",
            "keyvault",
            "secret",
            "show",
            "--vault-name",
            vault,
            "--name",
            f"litellm-key-team-{team}",
            "--query",
            "value",
            "-o",
            "tsv",
        ],
        text=True,
    ).strip()


def _models_url() -> str:
    return ENDPOINT.replace("/v1/chat/completions", "/v1/models")


def list_models() -> list[str]:
    """Model ids this gateway accepts (aliases and real deployment names)."""
    _ensure_env()
    req = urllib.request.Request(
        _models_url(),
        headers={"Authorization": f"Bearer {_litellm_key()}"},
    )
    with urllib.request.urlopen(req, timeout=_http_timeout()) as resp:
        body = json.load(resp)
    return sorted(model["id"] for model in body.get("data", []))


def _request_chat(prompt: str, api_key: str, model: str) -> dict:
    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(
            {"model": model, "temperature": 0, "messages": [{"role": "user", "content": prompt}]}
        ).encode(),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=_http_timeout()) as resp:
            body = json.load(resp)
            usage = body.get("usage") or {}
            cost_header = resp.headers.get("x-litellm-response-cost")
            return {
                "content": body["choices"][0]["message"]["content"],
                "prompt_tokens": usage.get("prompt_tokens", 0),
                "completion_tokens": usage.get("completion_tokens", 0),
                "cost_usd": float(cost_header) if cost_header else None,
            }
    except urllib.error.HTTPError as error:
        if error.code == 429:
            raise RuntimeError(
                f"LiteLLM refused the request (429). Either the team's "
                f"$5/day budget is spent, or rpm/tpm limits were hit. "
                f"Gateway said: {error.read().decode()[:300]}"
            ) from error
        raise RuntimeError(f"LiteLLM returned {error.code}") from error


@print_chat_cost
def chat(prompt: str, *, model: str | None = None) -> dict:
    """Call LiteLLM. Use any id from list_models() — aliases (cheap) or real names."""
    _ensure_env()
    model_name = _default_model(model)
    return {
        **_request_chat(prompt, _litellm_key(), model_name),
        "model": model_name,
    }


def query(sql: str) -> pd.DataFrame:
    """Run any SQL statement against Databricks and return the result as a dataframe."""
    _ensure_env()
    columns, rows = Warehouse.from_env().query(sql)
    names = [name for name, _type in columns]
    print(f"loaded {len(rows)} rows")
    return pd.DataFrame(rows, columns=names)


def _parse_batch_response(
    content: str,
    size: int,
    categories: tuple[str, ...] | None = None,
) -> list[str]:
    start, end = content.find("{"), content.rfind("}")
    if start == -1 or end == -1:
        raise ValueError(f"no JSON in the answer: {content[:120]!r}")
    answer = json.loads(content[start : end + 1])
    labels = [str(answer.get(str(index), "other")).strip().lower() for index in range(size)]
    if categories is None:
        return labels
    return [label if label in categories else "other" for label in labels]


def classify(
    titles: pd.Series,
    *,
    prompt: str,
    model: str | None = None,
    batch_size: int = 20,
    categories: tuple[str, ...] | None = None,
) -> pd.Series:
    """Classify a series in batches. Prompt must include `{numbered_items}`."""
    if "{numbered_items}" not in prompt:
        raise ValueError("prompt must contain a {numbered_items} placeholder")

    items = titles.tolist()
    labels: list[str] = []
    model_name = _default_model(model)

    for start in range(0, len(items), batch_size):
        batch = items[start : start + batch_size]
        numbered = "\n".join(f"{i}. {item}" for i, item in enumerate(batch))
        reply = chat(prompt.replace("{numbered_items}", numbered), model=model_name)
        labels.extend(_parse_batch_response(reply["content"], len(batch), categories))

    return pd.Series(labels, index=titles.index, name=titles.name)
