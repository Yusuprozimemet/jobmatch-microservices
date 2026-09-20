import Link from "next/link";

const FEATURE_ITEMS = [
  "Search jobs by keyword",
  "Filter results by available job data",
  "Open detailed job pages",
  "Create and update a profile",
  "Save jobs for later",
  "Track saved job statuses",
  "View backend-provided match information",
];

const TEAM_GROUPS = [
  {
    area: "Frontend",
    description:
      "The frontend brings the JobMatch experience to life through the Next.js application, product screens, and user interactions.",
    members: [
      {
        name: "Hamed Razizadeh",
        role: "Frontend Developer",
        githubUrl: "https://github.com/HamedRazizadeh-hub",
      },
    ],
  },
  {
    area: "Backend",
    description:
      "The backend supports account, jobs, saved jobs, profile, and matching features through the application API.",
    members: [
      {
        name: "Monerh Al Sqyan",
        role: "Backend Developer",
        githubUrl: "https://github.com/Miuroro",
      },
      {
        name: "Yusup Rozimemet",
        role: "Backend Developer",
        githubUrl: "https://github.com/Yusuprozimemet",
      },
    ],
  },
  {
    area: "Data",
    description:
      "The data team works on the job data flow that helps JobMatch present useful listings and job information.",
    members: [
      {
        name: "Halyna Romanyshyn",
        role: "Data Team",
        githubUrl: "https://github.com/halyna1995",
      },
      {
        name: "Baraah Alshiaani",
        role: "Data Team",
        githubUrl: "https://github.com/thebaraah",
      },
      {
        name: "Mohamad Bader Almsaddi alzin",
        role: "Data Team",
        githubUrl: "https://github.com/noneeeed",
      },
    ],
  },
];

function TeamGroup({
  area,
  description,
  members,
}: (typeof TEAM_GROUPS)[number]) {
  return (
    <section className="team-group" aria-labelledby={`team-${area}`}>
      <div>
        <p className="info-section-label">{area}</p>
        <h3 id={`team-${area}`}>{area}</h3>
        <p>{description}</p>
      </div>

      <div className="team-member-list">
        {members.map((member) => (
          <article className="team-member-card" key={member.githubUrl}>
            <h4>{member.name}</h4>
            <p>{member.role}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

export default function AboutPage() {
  return (
    <div className="info-page">
      <section className="info-hero">
        <p className="info-eyebrow">ABOUT JOBMATCH</p>
        <h1>Find jobs that fit you, with more clarity.</h1>
        <p>
          JobMatch is a job discovery and matching product built to help job
          seekers spend less time sorting through listings and more time
          focusing on opportunities that are worth a closer look.
        </p>
      </section>

      <section className="info-section">
        <p className="info-section-label">WHY JOBMATCH?</p>
        <h2>Job search can be noisy, repetitive, and hard to prioritize.</h2>
        <p>
          Job seekers often have to compare many roles, skim incomplete job
          descriptions, check whether listings are still relevant, and remember
          which applications need follow-up. JobMatch helps turn that scattered
          process into a clearer workflow.
        </p>
      </section>

      <section className="info-section">
        <p className="info-section-label">HOW IT HELPS</p>
        <h2>Focus on more relevant opportunities.</h2>
        <p>
          Users can search and filter jobs, review details, save promising
          listings, and track each saved job as their application moves forward.
          When profile and job information is available, match information from
          the backend can help explain why a role may be relevant.
        </p>
        <p>
          Match information is decision-support only. It does not guarantee
          employment, interview selection, job suitability, or accuracy.
        </p>
      </section>

      <section className="info-section">
        <p className="info-section-label">MAIN FEATURES</p>
        <h2>What users can do</h2>
        <ul className="feature-list">
          {FEATURE_ITEMS.map((feature) => (
            <li key={feature}>{feature}</li>
          ))}
        </ul>
      </section>

      <section className="info-section">
        <p className="info-section-label">BUILT BY THE TEAM</p>
        <h2>The people behind JobMatch</h2>
        <div className="team-section">
          {TEAM_GROUPS.map((group) => (
            <TeamGroup key={group.area} {...group} />
          ))}
        </div>
      </section>

      <section className="info-section" id="contact">
        <p className="info-section-label">CONTACT</p>
        <h2>Contact the project team</h2>
        <p>
          For questions about the project, contact one of the team members
          listed below. You can also return to the product and continue
          exploring current job listings.
        </p>

        <div className="contact-list">
          {TEAM_GROUPS.flatMap((group) => group.members).map((member) => (
            <article className="contact-card" key={member.githubUrl}>
              <h3>{member.name}</h3>
              <a
                href={member.githubUrl}
                rel="noopener noreferrer"
                target="_blank"
              >
                GitHub profile
              </a>
            </article>
          ))}
        </div>

        <Link className="info-link" href="/jobs">
          Explore jobs
        </Link>
      </section>
    </div>
  );
}
