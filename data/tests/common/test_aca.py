"""The container job poll loop.

This is where a "starts the job and reports green" regression would hide, and
it would hide well: the pipeline stays green and only the numbers go wrong.
"""

import pytest
from conftest import RecordingOpener

from src.common.aca import JobFailed, filter_application_log_lines, start_and_wait

STARTED = {"name": "job-ingest-abc123"}


def execution(status: str, name: str = "job-ingest-abc123") -> dict:
    return {"value": [{"name": name, "properties": {"status": status}}]}


def run(answers: list[dict], **kwargs) -> str:
    opener = RecordingOpener(answers)
    return start_and_wait(
        subscription="sub",
        resource_group="rg-hyf-fp-team-a",
        job_name="job-ingest",
        token="token",
        opener=opener,
        sleep=lambda _seconds: None,
        **kwargs,
    )


def test_waits_through_pending_and_running():
    """The job is not finished when it starts, and the task must not be either."""
    name = run(
        [
            STARTED,
            execution("Pending"),
            execution("Running"),
            execution("Succeeded"),
        ]
    )
    assert name == "job-ingest-abc123"


def test_failed_execution_raises():
    with pytest.raises(JobFailed, match="Failed"):
        run([STARTED, execution("Failed")])


def test_cancelled_execution_raises():
    """Cancelled is not success. Treating any non-Failed state as fine is the
    easy mistake, and it turns a killed job into a green pipeline."""
    with pytest.raises(JobFailed, match="Cancelled"):
        run([STARTED, execution("Cancelled")])


def test_a_job_that_never_finishes_times_out():
    with pytest.raises(TimeoutError):
        run([STARTED] + [execution("Running")] * 5, timeout_seconds=0)


def test_watches_its_own_execution_not_the_newest():
    """Two runs of the same job can overlap. Taking executions[0] would report
    the status of somebody else's run."""
    answers = [
        STARTED,
        {
            "value": [
                {"name": "job-ingest-other", "properties": {"status": "Failed"}},
                {"name": "job-ingest-abc123", "properties": {"status": "Succeeded"}},
            ]
        },
    ]
    assert run(answers) == "job-ingest-abc123"


def test_start_is_a_post_to_the_right_url():
    opener = RecordingOpener([STARTED, execution("Succeeded")])
    start_and_wait(
        subscription="sub",
        resource_group="rg",
        job_name="job-ingest",
        token="token",
        opener=opener,
        sleep=lambda _seconds: None,
    )
    assert opener.requests[0].get_method() == "POST"
    assert "/providers/Microsoft.App/jobs/job-ingest/start" in opener.urls[0]


def test_success_pulls_console_logs_when_team_is_set(monkeypatch):
    workspace = {"properties": {"customerId": "28f43cc9-8c87-4bad-9711-7d2cee32dddd"}}
    log_rows = {"tables": [{"rows": [["fetched 10 records"]]}]}
    opener = RecordingOpener([STARTED, workspace, execution("Succeeded"), log_rows])
    tokens = iter(["mgmt-token", "la-token"])
    monkeypatch.setattr("src.common.aca.azure_token", lambda scope=None: next(tokens))

    name = start_and_wait(
        subscription="sub",
        resource_group="rg-hyf-fp-team-a",
        job_name="job-ingest",
        token="mgmt-token",
        team="team-a",
        opener=opener,
        sleep=lambda _seconds: None,
    )

    assert name == "job-ingest-abc123"
    assert any("loganalytics.io" in url for url in opener.urls)


def test_filter_application_log_lines_drops_azure_sdk_noise():
    lines = [
        "2026-08-27 10:34:56,365 INFO src.ingestion.ingest Received 175 record(s)",
        "2026-08-27 10:34:56,374 INFO azure.identity._credentials.managed_identity noise",
        "    'Metadata': 'REDACTED'",
        "2026-08-27 10:34:57,474 INFO src.ingestion.storage landed 175 records",
        "2026-08-27 10:34:57,476 INFO pipeline Pipeline finished",
    ]
    assert filter_application_log_lines(lines) == [
        "2026-08-27 10:34:56,365 INFO src.ingestion.ingest Received 175 record(s)",
        "2026-08-27 10:34:57,474 INFO src.ingestion.storage landed 175 records",
        "2026-08-27 10:34:57,476 INFO pipeline Pipeline finished",
    ]


def test_filter_application_log_lines_keeps_traceback_after_error():
    """Mode 2/3/4 ingest failures print the cause as a Traceback after ERROR."""
    lines = [
        "<frozen runpy>:128: RuntimeWarning: ignored noise",
        "2026-08-31 07:00:28,525 ERROR pipeline Pipeline failed",
        "Traceback (most recent call last):",
        '  File "/app/src/ingestion/pipeline.py", line 175, in <module>',
        "    run(args.run_date, args.local)",
        '  File "/app/src/ingestion/ingest.py", line 107, in fetch_all_pages',
        '    raise ValueError("ADZUNA_APP_ID and ADZUNA_APP_KEY environment variables must be set.")',
        "ValueError: ADZUNA_APP_ID and ADZUNA_APP_KEY environment variables must be set.",
        "2026-08-31 07:00:29,001 INFO azure.core.pipeline.policies.http_logging_policy noise",
        "    'Metadata': 'REDACTED'",
    ]
    assert filter_application_log_lines(lines) == [
        "2026-08-31 07:00:28,525 ERROR pipeline Pipeline failed",
        "Traceback (most recent call last):",
        '  File "/app/src/ingestion/pipeline.py", line 175, in <module>',
        "    run(args.run_date, args.local)",
        '  File "/app/src/ingestion/ingest.py", line 107, in fetch_all_pages',
        '    raise ValueError("ADZUNA_APP_ID and ADZUNA_APP_KEY environment variables must be set.")',
        "ValueError: ADZUNA_APP_ID and ADZUNA_APP_KEY environment variables must be set.",
    ]


def test_filter_drops_metadata_immediately_after_exception():
    """Indented Azure leftovers after the exception line must not ride along."""
    lines = [
        "2026-08-31 07:00:28,525 ERROR pipeline Pipeline failed",
        "Traceback (most recent call last):",
        '  File "/app/src/ingestion/ingest.py", line 107, in fetch_all_pages',
        "    raise ValueError('boom')",
        "ValueError: boom",
        "    'Metadata': 'REDACTED'",
    ]
    assert filter_application_log_lines(lines) == [
        "2026-08-31 07:00:28,525 ERROR pipeline Pipeline failed",
        "Traceback (most recent call last):",
        '  File "/app/src/ingestion/ingest.py", line 107, in fetch_all_pages',
        "    raise ValueError('boom')",
        "ValueError: boom",
    ]


def test_filter_does_not_open_traceback_on_warning_or_info():
    warning_lines = [
        "2026-08-31 07:00:28,525 WARNING pipeline something odd",
        "Traceback (most recent call last):",
        "ValueError: should stay dropped",
    ]
    assert filter_application_log_lines(warning_lines) == [
        "2026-08-31 07:00:28,525 WARNING pipeline something odd",
    ]
    info_lines = [
        "2026-08-31 07:00:28,525 INFO pipeline still going",
        "Traceback (most recent call last):",
        "ValueError: should stay dropped",
    ]
    assert filter_application_log_lines(info_lines) == [
        "2026-08-31 07:00:28,525 INFO pipeline still going",
    ]


def test_filter_keeps_chained_exception_headers():
    lines = [
        "2026-08-31 07:00:28,525 ERROR pipeline Pipeline failed",
        "Traceback (most recent call last):",
        '  File "/app/a.py", line 1, in <module>',
        "    raise ValueError('inner')",
        "ValueError: inner",
        "The above exception was the direct cause of the following exception:",
        "Traceback (most recent call last):",
        '  File "/app/b.py", line 2, in <module>',
        "    raise RuntimeError('outer') from err",
        "RuntimeError: outer",
        "    'Metadata': 'REDACTED'",
    ]
    assert filter_application_log_lines(lines) == [
        "2026-08-31 07:00:28,525 ERROR pipeline Pipeline failed",
        "Traceback (most recent call last):",
        '  File "/app/a.py", line 1, in <module>',
        "    raise ValueError('inner')",
        "ValueError: inner",
        "The above exception was the direct cause of the following exception:",
        "Traceback (most recent call last):",
        '  File "/app/b.py", line 2, in <module>',
        "    raise RuntimeError('outer') from err",
        "RuntimeError: outer",
    ]
