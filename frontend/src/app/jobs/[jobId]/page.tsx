import Link from "next/link";
import { notFound } from "next/navigation";
import JobDetailsMatchSection from "@/components/jobs/JobDetailsMatchSection";
import SavedJobBookmarkButton from "@/components/jobs/SavedJobBookmarkButton";
import type { JobDetailsResponse } from "@/lib/api";
import { formatEnumLabel, formatPostedDate } from "@/lib/formatters";
import { formatSavedCount } from "@/lib/job-popularity";
import { BackendRequestError, getJobDetailsServer } from "@/lib/jobs-server";

type JobDetailsPageProps = {
  params: Promise<{
    jobId: string;
  }>;
};

function formatCurrencyAmount(amount: number, currency: string | null): string {
  if (!currency) {
    return new Intl.NumberFormat("en").format(amount);
  }

  try {
    return new Intl.NumberFormat("en", {
      style: "currency",
      currency,
      maximumFractionDigits: 0,
    }).format(amount);
  } catch {
    return `${currency} ${new Intl.NumberFormat("en").format(amount)}`;
  }
}

function formatSalary(job: JobDetailsResponse): string {
  const salaryPeriod = formatEnumLabel(job.salaryPeriod)?.toLowerCase();
  const period = salaryPeriod ? ` per ${salaryPeriod}` : "";

  if (job.salaryMin !== null && job.salaryMax !== null) {
    return `${formatCurrencyAmount(
      job.salaryMin,
      job.salaryCurrency,
    )} - ${formatCurrencyAmount(job.salaryMax, job.salaryCurrency)}${period}`;
  }

  if (job.salaryMin !== null) {
    return `From ${formatCurrencyAmount(
      job.salaryMin,
      job.salaryCurrency,
    )}${period}`;
  }

  if (job.salaryMax !== null) {
    return `Up to ${formatCurrencyAmount(
      job.salaryMax,
      job.salaryCurrency,
    )}${period}`;
  }

  return "Not specified";
}

function getSafeApplicationUrl(sourceUrl: string | null): string | null {
  if (!sourceUrl) {
    return null;
  }

  try {
    const url = new URL(sourceUrl);

    if (url.protocol === "http:" || url.protocol === "https:") {
      return url.toString();
    }
  } catch {
    return null;
  }

  return null;
}

async function loadJobDetails(jobId: string): Promise<JobDetailsResponse> {
  try {
    return await getJobDetailsServer(jobId);
  } catch (error) {
    if (error instanceof BackendRequestError && error.status === 404) {
      notFound();
    }

    throw error;
  }
}

export default async function JobDetailsPage({ params }: JobDetailsPageProps) {
  const { jobId } = await params;
  const job = await loadJobDetails(jobId);
  const applicationUrl = getSafeApplicationUrl(job.sourceUrl);
  const savedCountLabel = formatSavedCount(job.savedCount);

  const skillOccurrences = new Map<string, number>();

  const visibleSkills = job.skills.map((skill) => {
    const occurrence = (skillOccurrences.get(skill) ?? 0) + 1;
    skillOccurrences.set(skill, occurrence);

    return {
      key: `${skill}-${occurrence}`,
      skill,
    };
  });

  return (
    <main className="job-details-page">
      <div className="job-details-container">
        <Link className="job-details-back" href="/jobs">
          Back to jobs
        </Link>

        <section className="job-details-hero">
          <div>
            <p className="job-details-eyebrow">
              {job.companyName ?? "Not specified"}
            </p>

            <h1>{job.title}</h1>

            <p className="job-details-location">
              {job.location ?? "Not specified"}
            </p>
          </div>

          <div className="job-details-actions">
            <SavedJobBookmarkButton postingId={job.postingId} variant="cta" />

            {applicationUrl ? (
              <a
                className="job-details-apply"
                href={applicationUrl}
                target="_blank"
                rel="noreferrer noopener"
              >
                Apply externally
              </a>
            ) : (
              <span className="job-details-apply is-disabled">
                Application link unavailable
              </span>
            )}
          </div>
        </section>

        <section className="job-details-meta" aria-label="Job information">
          <div>
            <span>Work mode</span>
            <strong>{formatEnumLabel(job.workMode) ?? "Not specified"}</strong>
          </div>

          <div>
            <span>Employment</span>
            <strong>
              {formatEnumLabel(job.employmentType) ?? "Not specified"}
            </strong>
          </div>

          <div>
            <span>Experience</span>
            <strong>
              {formatEnumLabel(job.experienceLevel) ?? "Not specified"}
            </strong>
          </div>

          <div>
            <span>Salary</span>
            <strong>{formatSalary(job)}</strong>
          </div>
        </section>

        <div className="job-details-layout">
          <article className="job-details-main">
            <section>
              <p className="job-details-section-label">ABOUT THE ROLE</p>
              <h2>Job description</h2>

              <p className="job-details-description">
                {job.description ?? "Not specified"}
              </p>
            </section>

            <section>
              <p className="job-details-section-label">SKILLS</p>
              <h2>What they are looking for</h2>

              {visibleSkills.length > 0 ? (
                <div className="job-details-skills">
                  {visibleSkills.map(({ key, skill }) => (
                    <span key={key}>{skill}</span>
                  ))}
                </div>
              ) : (
                <p>No skills specified.</p>
              )}
            </section>

            <section>
              <p className="job-details-section-label">REQUIREMENTS</p>
              <h2>Experience & education</h2>

              <dl className="job-details-requirements">
                <div>
                  <dt>Experience</dt>
                  <dd>
                    {formatEnumLabel(job.experienceLevel) ?? "Not specified"}
                  </dd>
                </div>

                <div>
                  <dt>Education</dt>
                  <dd>
                    {formatEnumLabel(job.educationLevel) ?? "Not specified"}
                  </dd>
                </div>
              </dl>
            </section>
          </article>

          <aside className="job-details-sidebar">
            <section>
              <p className="job-details-section-label">LISTING INFORMATION</p>

              <dl className="job-details-listing-info">
                <div>
                  <dt>Posted</dt>
                  <dd>
                    {formatPostedDate(job.postedDate, job.ageDays) ??
                      "Not specified"}
                  </dd>
                </div>

                <div>
                  <dt>Freshness</dt>
                  <dd>{formatEnumLabel(job.freshnessClass) ?? "Unknown"}</dd>
                </div>

                <div>
                  <dt>Source</dt>
                  <dd>{job.source ?? "Not specified"}</dd>
                </div>

                {savedCountLabel ? (
                  <div>
                    <dt>Popularity</dt>
                    <dd className="job-details-popularity">
                      {savedCountLabel}
                    </dd>
                  </div>
                ) : null}
              </dl>
            </section>

            <JobDetailsMatchSection postingId={job.postingId} />
          </aside>
        </div>
      </div>
    </main>
  );
}
