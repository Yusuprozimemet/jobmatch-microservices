"""Tests for optional/llm_classify/llm_local.py (no network)."""

import json
from unittest.mock import patch

import pandas as pd
import pytest

from optional.llm_classify.llm_local import _parse_batch_response, classify

CATEGORIES = ("backend", "frontend", "data", "devops", "other")


def test_parse_batch_response_clamps_to_categories():
    content = json.dumps({"0": "backend", "1": "machine-learning"})
    assert _parse_batch_response(content, 2, CATEGORIES) == ["backend", "other"]


def test_parse_batch_response_without_categories_returns_raw_labels():
    content = json.dumps({"0": "machine-learning"})
    assert _parse_batch_response(content, 1) == ["machine-learning"]


def test_an_answer_with_no_json_raises():
    with pytest.raises(ValueError, match="no JSON"):
        _parse_batch_response("no json here", 1)


def test_classify_requires_numbered_items_placeholder():
    with pytest.raises(ValueError, match="numbered_items"):
        classify(pd.Series(["Engineer"]), prompt="classify this", categories=CATEGORIES)


def test_classify_clamps_labels():
    titles = pd.Series(["Backend Engineer", "Data Analyst"])

    def fake_chat(prompt, *, model=None):
        return {"content": json.dumps({"0": "backend", "1": "ml"})}

    with patch("optional.llm_classify.llm_local.chat", fake_chat):
        result = classify(
            titles,
            prompt="Categories here\n{numbered_items}",
            categories=CATEGORIES,
            batch_size=10,
        )

    assert result.tolist() == ["backend", "other"]
