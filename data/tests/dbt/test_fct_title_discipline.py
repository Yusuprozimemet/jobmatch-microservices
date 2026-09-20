"""Tests for `dbt/models/marts/fct_title_discipline.py` (disabled by default).

These live under `tests/dbt/` rather than next to the model because dbt reads
every `.py` under `models/` as a model and refuses one that defines no `model()`.
"""

import email.message
import io
import json
import urllib.error
from pathlib import Path

import pytest
from fct_title_discipline import (
    BATCH_SIZE,
    CATEGORIES,
    PROMPT,
    _parse_labels,
    chat,
    classify_titles,
)

NOTEBOOK = (
    Path(__file__).resolve().parents[2] / "optional" / "llm_classify" / "llm_classify_dev.ipynb"
)


def _prompt_from_notebook() -> str:
    for cell in json.loads(NOTEBOOK.read_text())["cells"]:
        if cell["cell_type"] != "code":
            continue
        source = "".join(cell["source"])
        if "PROMPT = (" not in source:
            continue
        namespace: dict = {}
        exec(source.split('df["job_categories"]')[0], namespace)  # noqa: S102
        return namespace["PROMPT"]
    raise AssertionError("classify cell with PROMPT not found in notebook")


def test_prompt_matches_the_notebook():
    assert PROMPT == _prompt_from_notebook()
    assert "{numbered_items}" in PROMPT
    assert ", ".join(CATEGORIES) in PROMPT


def test_fill_prompt_includes_numbered_titles():
    from fct_title_discipline import _fill_prompt

    prompt = _fill_prompt(["Backend Engineer"])
    assert "0. Backend Engineer" in prompt
    assert ", ".join(CATEGORIES) in prompt


def test_unknown_labels_become_other():
    assert _parse_labels(json.dumps({"0": "machine-learning"}), 1) == ["other"]


def test_missing_keys_default_to_other():
    assert _parse_labels(json.dumps({"0": "backend"}), 2) == ["backend", "other"]


def test_json_wrapped_in_prose_is_still_read():
    content = 'Sure!\n```json\n{"0": "data_engineering"}\n```'
    assert _parse_labels(content, 1) == ["data_engineering"]


def test_an_answer_with_no_json_raises():
    with pytest.raises(ValueError, match="no JSON"):
        _parse_labels("Sure, here are the labels: backend, data", 1)


def test_classify_titles_batches():
    titles = [f"Engineer {i}" for i in range(BATCH_SIZE * 2 + 1)]
    calls = []

    def fake_chat(prompt, api_key, model):
        calls.append(prompt)
        count = sum(1 for line in prompt.splitlines() if line[:1].isdigit())
        return json.dumps({str(i): "other" for i in range(count)})

    classify_titles(titles, "key", "cheap", call=fake_chat)
    assert len(calls) == 3


def test_rate_limit_message(monkeypatch):
    upstream = b'{"error":{"message":"Budget has been exceeded: current cost=5.01"}}'

    def refuse(*_args, **_kwargs):
        raise urllib.error.HTTPError(
            url="", code=429, msg="", hdrs=email.message.Message(), fp=io.BytesIO(upstream)
        )

    monkeypatch.setattr("urllib.request.urlopen", refuse)
    with pytest.raises(RuntimeError, match="429"):
        chat("classify these", "not-a-real-key", "cheap")
