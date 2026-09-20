import Link from "next/link";
import type { JobState, SavedJobsStatsResponse } from "@/lib/api";
import { SAVED_JOB_STATUS_OPTIONS } from "@/lib/saved-job-status";

type JobStatusSummaryProps = {
  stats: SavedJobsStatsResponse;
  activeStatus?: JobState | null;
};

export default function JobStatusSummary({
  stats,
  activeStatus = null,
}: JobStatusSummaryProps) {
  const tracked = SAVED_JOB_STATUS_OPTIONS.reduce(
    (total, option) => total + (stats[option.value] ?? 0),
    0,
  );

  return (
    <aside className="job-status-summary">
      <div className="job-status-summary-header">
        <div>
          <p className="saved-eyebrow">OVERVIEW</p>
          <h2>
            <Link
              aria-current={activeStatus === null ? "page" : undefined}
              className="job-status-title-link"
              href="/saved"
            >
              Job Overview
            </Link>
          </h2>
        </div>

        <div className="job-status-total">
          <strong>{tracked}</strong>
          <span>Tracked</span>
        </div>
      </div>

      {tracked === 0 ? (
        <p className="job-status-empty">
          No tracked jobs yet. Save a job to start building your application
          pipeline.
        </p>
      ) : null}

      <nav aria-label="Saved job status filters">
        <ul className="job-status-grid">
          {SAVED_JOB_STATUS_OPTIONS.map((option) => (
            <li key={option.value}>
              <Link
                aria-current={
                  activeStatus === option.value ? "page" : undefined
                }
                className={`is-status-${option.value.toLowerCase()}`}
                href={`/saved/status/${encodeURIComponent(option.value)}`}
              >
                <span>{option.label}</span>
                <strong>{stats[option.value] ?? 0}</strong>
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  );
}
