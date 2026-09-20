export default function LoadingJobDetails() {
  return (
    <main className="job-details-page">
      <div className="job-details-container">
        <p className="job-details-state-eyebrow">LOADING</p>
        <h1 className="job-details-state-title">Loading job details...</h1>
        {/* biome-ignore lint/a11y/useSemanticElements: Loading copy should remain a normal text element with status semantics. */}
        <p className="job-details-state-copy" role="status">
          We are getting the latest information for this job.
        </p>
        <div className="job-details-loading-preview" aria-hidden="true">
          <div className="jobs-loading-line jobs-loading-line-short" />
          <div className="jobs-loading-line jobs-loading-line-long" />
          <div className="jobs-loading-line jobs-loading-line-medium" />
          <div className="jobs-loading-block" />
        </div>
      </div>
    </main>
  );
}
