"use client";

export default function PrivacyPage() {
  return (
    <section className="legal-page">
      <div className="legal-container">
        <p className="legal-eyebrow">PRIVACY</p>

        <h1>Privacy Notice</h1>

        <p className="legal-updated">
          A simple project notice for how JobMatch uses information in the app.
        </p>

        <div className="legal-content">
          <section>
            <h2>Information used by JobMatch</h2>
            <p>
              JobMatch may use information you provide, such as account details,
              profile preferences, skills, saved jobs, and application tracking
              statuses, to support the product features.
            </p>
          </section>

          <section>
            <h2>How profile information helps</h2>
            <p>
              Profile information and skills can help the app personalize job
              discovery and show match information based on available profile
              and job data.
            </p>
          </section>

          <section>
            <h2>Accounts and authentication</h2>
            <p>
              Some features require an account and authenticated session, such
              as saving jobs, updating a profile, and viewing member-only
              information.
            </p>
          </section>

          <section>
            <h2>External job sources</h2>
            <p>
              Job listings may come from external data sources. If you follow an
              external application link, the destination website may have its
              own privacy practices.
            </p>
          </section>

          <section>
            <h2>Project limitations</h2>
            <p>
              JobMatch is a student/demo project. Users should not assume that
              it provides production-level legal, compliance, retention,
              deletion, or third-party contractual guarantees.
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
