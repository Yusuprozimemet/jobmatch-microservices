"use client";

export default function TermsPage() {
  return (
    <section className="legal-page">
      <div className="legal-container">
        <p className="legal-eyebrow">TERMS</p>

        <h1>Terms of Use</h1>

        <p className="legal-updated">
          Straightforward terms for using the JobMatch project experience.
        </p>

        <div className="legal-content">
          <section>
            <h2>Using JobMatch</h2>
            <p>
              JobMatch is a job discovery and matching product. It helps users
              search for jobs, review job information, save listings, track
              application statuses, and view match information where available.
            </p>
          </section>

          <section>
            <h2>External listings</h2>
            <p>
              Job listings can originate from external sources. JobMatch does
              not control those sources and cannot guarantee that every listing
              is complete, current, accurate, or still available.
            </p>
          </section>

          <section>
            <h2>External applications</h2>
            <p>
              Applying for a job may redirect you to an external website.
              JobMatch does not control external job websites, their content, or
              their application processes.
            </p>
          </section>

          <section>
            <h2>Match information</h2>
            <p>
              Match percentages and related information are intended to support
              decision-making. They do not guarantee hiring, interview
              selection, job suitability, availability, or accuracy.
            </p>
          </section>

          <section>
            <h2>User responsibility</h2>
            <p>
              Users are responsible for checking job details, application
              requirements, employer information, and external website terms
              before acting on a listing.
            </p>
          </section>

          <section>
            <h2>Project status</h2>
            <p>
              JobMatch is a project/demo product. It should not be understood as
              offering unsupported commercial, legal, or employment guarantees.
            </p>
          </section>
        </div>

        <button
          className="legal-back-link"
          type="button"
          onClick={() => window.close()}
        >
          Close and return to registration
        </button>
      </div>
    </section>
  );
}
