import Link from "next/link";
import SavedJobBookmarkButton from "@/components/jobs/SavedJobBookmarkButton";
import type { JobState } from "@/lib/api";
import { formatSavedCount } from "@/lib/job-popularity";
import type { JobSearchResult } from "@/lib/jobs";

type JobResultItemProps = {
  job: JobSearchResult;
  savedState?: JobState | "UNKNOWN" | null;
  isCheckingSavedState?: boolean;
  onSavedStateChange?: (
    postingId: string,
    state: JobState | "UNKNOWN" | null,
  ) => void;
};

export default function JobResultItem({
  job,
  savedState,
  isCheckingSavedState = false,
  onSavedStateChange,
}: JobResultItemProps) {
  const skillOccurrences = new Map<string, number>();
  const visibleSkills = job.skills.slice(0, 5).map((skill) => {
    const occurrence = (skillOccurrences.get(skill) ?? 0) + 1;
    skillOccurrences.set(skill, occurrence);

    return {
      key: `${skill}-${occurrence}`,
      skill,
    };
  });
  const savedCountLabel = formatSavedCount(job.savedCount);

  return (
    <article className="job-result-item">
      <div className="job-result-main">
        <div className="job-result-heading">
          <div>
            <h3>
              <Link href={`/jobs/${encodeURIComponent(job.id)}`}>
                {job.title}
              </Link>
            </h3>

            <p className="job-result-byline">
              <span>{job.companyName}</span>
              <span>{job.location ?? "Location not specified"}</span>
            </p>
          </div>
        </div>

        <div className="job-result-meta">
          {job.workMode && <span>{job.workMode}</span>}

          {job.employmentType && <span>{job.employmentType}</span>}

          {job.postedDate && <span>{job.postedDate}</span>}

          {job.source && <span>{job.source}</span>}

          {savedCountLabel && (
            <span className="job-result-popularity">{savedCountLabel}</span>
          )}
        </div>

        {visibleSkills.length > 0 && (
          <div className="job-result-skills">
            {visibleSkills.map(({ key, skill }) => (
              <span key={key}>{skill}</span>
            ))}
          </div>
        )}
      </div>

      <div className="job-result-action-row">
        {job.freshness && (
          <span className="job-result-freshness">{job.freshness}</span>
        )}

        <SavedJobBookmarkButton
          postingId={job.id}
          initialState={savedState}
          isCheckingInitialState={isCheckingSavedState}
          onStateChange={onSavedStateChange}
        />
      </div>

      <Link
        className="job-result-link"
        href={`/jobs/${encodeURIComponent(job.id)}`}
      >
        View job →
      </Link>
    </article>
  );
}
