import Link from "next/link";

export default function JobNotFound() {
  return (
    <main className="job-details-page">
      <div className="job-details-container job-details-state">
        <p className="job-details-state-eyebrow">JOB NOT FOUND</p>

        <h1 className="job-details-state-title">
          This job is no longer available
        </h1>

        <p className="job-details-state-copy">
          The listing may have been removed, expired, or the link may be
          incorrect.
        </p>

        <Link className="job-details-apply" href="/jobs">
          Browse jobs
        </Link>
      </div>
    </main>
  );
}
