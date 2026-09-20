import type { JobSearchResponse } from "@/lib/api";

export type JobSearchResult = {
  id: string;
  title: string;
  companyName: string;
  location: string | null;
  skills: string[];
  workMode: string | null;
  employmentType: string | null;
  postedDate: string | null;
  ageDays: number | null;
  source: string | null;
  freshness: string | null;
  savedCount?: number;
};

function formatReadableDate(value: string): string {
  const date = new Date(`${value}T00:00:00`);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(date);
}

function formatPostedDate(
  postedDate: string | null,
  ageDays: number | null,
): string | null {
  if (ageDays === 0) {
    return "Posted today";
  }

  if (ageDays === 1) {
    return "Posted 1 day ago";
  }

  if (ageDays !== null) {
    return `Posted ${ageDays} days ago`;
  }

  if (!postedDate) {
    return null;
  }

  return `Posted ${formatReadableDate(postedDate)}`;
}

export function mapJobSearchResponse(job: JobSearchResponse): JobSearchResult {
  return {
    id: job.postingId,
    title: job.title,
    companyName: job.companyName,
    location: job.location,
    skills: job.skills ?? [],
    workMode: job.workMode,
    employmentType: job.employmentType,
    postedDate: formatPostedDate(job.postedDate, job.ageDays),
    ageDays: job.ageDays,
    source: job.source,
    freshness: job.freshnessClass,
    savedCount: job.savedCount,
  };
}

export function sortJobsByFreshness(
  jobs: JobSearchResult[],
): JobSearchResult[] {
  return jobs
    .map((job, index) => ({ job, index }))
    .sort((left, right) => {
      const leftAge = left.job.ageDays;
      const rightAge = right.job.ageDays;

      if (leftAge === null && rightAge === null) {
        return left.index - right.index;
      }

      if (leftAge === null) {
        return 1;
      }

      if (rightAge === null) {
        return -1;
      }

      return leftAge - rightAge || left.index - right.index;
    })
    .map(({ job }) => job);
}
