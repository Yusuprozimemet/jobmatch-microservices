"use client";

type JobsErrorProps = {
  reset: () => void;
};

export default function JobsError({ reset }: JobsErrorProps) {
  return (
    <main className="jobs-page">
      <div className="jobs-container">
        <section className="jobs-error" role="alert">
          <p className="jobs-section-label">SOMETHING WENT WRONG</p>

          <h1>We couldn&apos;t load the jobs.</h1>

          <p>
            There was a problem loading job opportunities. Please try again.
          </p>

          <button type="button" onClick={reset}>
            Try again
          </button>
        </section>
      </div>
    </main>
  );
}
