"use client";

import Link from "next/link";

type JobDetailsErrorProps = {
  reset: () => void;
};

export default function JobDetailsError({ reset }: JobDetailsErrorProps) {
  return (
    <main className="job-details-page">
      <div className="job-details-container job-details-state" role="alert">
        <p className="job-details-state-eyebrow">SOMETHING WENT WRONG</p>

        <h1 className="job-details-state-title">We could not load this job</h1>

        <p className="job-details-state-copy">
          The job details are temporarily unavailable. You can try again or
          return to the jobs page.
        </p>

        <div className="job-details-state-actions">
          <button className="job-details-apply" type="button" onClick={reset}>
            Try again
          </button>

          <Link className="job-details-state-link" href="/jobs">
            Back to jobs
          </Link>
        </div>
      </div>
    </main>
  );
}
