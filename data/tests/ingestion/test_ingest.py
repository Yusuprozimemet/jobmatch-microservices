"""Validation at the edge: what survives, what is rejected, what is counted."""

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from src.ingestion.ingest import parse_records
from src.ingestion.models import Posting

GOOD = {
    "public_slug": "data-engineer-acme",
    "title": "Data Engineer",
    "company": "Acme",
    "url": "https://example.com/jobs/123",
    "location": "Amsterdam",
    "countries": ["nl"],
    "regions": ["eu"],
    "cities": ["Amsterdam"],
    "work_mode": "hybrid",
    "skills": ["python", "sql"],
    "posted_at": "2026-08-18T09:15:11Z",
    "created_at": "2026-08-18T09:15:11Z",
    "updated_at": "2026-08-18T09:15:22Z",
    "last_seen_at": "2026-08-18T09:15:22Z",
    "closed_at": None,
}


def test_good_record_survives():
    """A valid record is accepted and parsed into a Posting."""
    parsed, rejected = parse_records([GOOD])
    assert rejected == 0
    assert parsed[0].public_slug == "data-engineer-acme"


def test_one_bad_record_does_not_lose_the_batch():
    """The whole point of counting rejections instead of raising."""
    parsed, rejected = parse_records([GOOD, {"public_slug": "missing-everything-else"}])
    assert len(parsed) == 1
    assert rejected == 1


def test_a_scalar_in_the_list_is_rejected_not_fatal():
    """A JSON list can hold a string. Calling .get on one would lose the batch."""
    parsed, rejected = parse_records([GOOD, "not-a-dict", 42])
    assert len(parsed) == 1
    assert rejected == 2


def test_created_at_is_parsed_as_datetime():
    """The Posting model converts ISO 8601 strings to datetime objects."""
    posting = Posting.model_validate(GOOD)

    assert posting.created_at.tzinfo is not None
    assert posting.created_at == datetime(2026, 8, 18, 9, 15, 11, tzinfo=UTC)


def test_missing_required_field_is_rejected():
    """The Posting model requires title, company, url, and public_slug."""
    with pytest.raises(ValidationError):
        Posting.model_validate({k: v for k, v in GOOD.items() if k != "title"})


def test_open_job_can_have_no_closed_at():
    """The API returns closed_at as null for open jobs."""
    posting = Posting.model_validate(GOOD)

    assert posting.closed_at is None


def test_optional_fields_can_be_empty_or_missing():
    """The API sometimes returns empty lists or omits optional fields entirely."""
    job = GOOD.copy()

    job["cities"] = []
    job["skills"] = []
    job.pop("work_mode")

    posting = Posting.model_validate(job)

    assert posting.cities == []
    assert posting.skills == []
    assert posting.work_mode is None


def test_duplicate_public_slug_is_rejected():
    """Two records with the same public_slug are not allowed."""
    duplicate = GOOD.copy()

    parsed, rejected = parse_records([GOOD, duplicate])

    assert len(parsed) == 1
    assert rejected == 1
