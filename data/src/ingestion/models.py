"""Validation model for FreeHire job data."""

from datetime import datetime

from pydantic import BaseModel, Field


class Posting(BaseModel):
    """One job posting from the FreeHire API."""

    public_slug: str
    title: str
    company: str
    url: str

    location: str | None = None

    countries: list[str] = Field(default_factory=list)
    regions: list[str] = Field(default_factory=list)
    cities: list[str] = Field(default_factory=list)

    work_mode: str | None = None
    skills: list[str] = Field(default_factory=list)

    posted_at: datetime | None = None
    created_at: datetime
    updated_at: datetime | None = None
    last_seen_at: datetime | None = None
    closed_at: datetime | None = None
