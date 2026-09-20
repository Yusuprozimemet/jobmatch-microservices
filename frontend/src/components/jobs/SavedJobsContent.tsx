"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import JobStatusSummary from "@/components/jobs/JobStatusSummary";
import { useAuth } from "@/context/AuthContext";
import {
  ApiError,
  deleteSavedJob,
  getSavedJobs,
  getSavedJobsStats,
  type JobState,
  type SavedJobResponse,
  type SavedJobsStatsResponse,
  updateSavedJobStatus,
} from "@/lib/api";
import { formatCategoryLabel } from "@/lib/category";
import {
  getSavedJobStatusLabel,
  SAVED_JOB_STATUS_OPTIONS,
} from "@/lib/saved-job-status";

const EMPTY_STATS: SavedJobsStatsResponse = {
  SAVED: 0,
  APPLIED: 0,
  REJECTED: 0,
  ACCEPTED: 0,
  DECLINED: 0,
};

const SAVED_JOBS_PAGE_SIZE = 20;

type SavedJobsContentProps = {
  status?: JobState | null;
};

export default function SavedJobsContent({
  status = null,
}: SavedJobsContentProps) {
  const router = useRouter();
  const { user, isLoading, clearUser } = useAuth();
  const [jobs, setJobs] = useState<SavedJobResponse[]>([]);
  const [stats, setStats] = useState<SavedJobsStatsResponse>(EMPTY_STATS);

  const [loadingJobs, setLoadingJobs] = useState(true);
  const [loadingStats, setLoadingStats] = useState(true);

  const [jobsError, setJobsError] = useState<string | null>(null);
  const [statsError, setStatsError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const [busyPostingId, setBusyPostingId] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);

  const selectedStatusLabel = status
    ? getSavedJobStatusLabel(status)
    : "Saved Jobs";

  const filteredJobs = useMemo(
    () => (status ? jobs.filter((job) => job.jobState === status) : jobs),
    [jobs, status],
  );
  const totalPages = Math.ceil(filteredJobs.length / SAVED_JOBS_PAGE_SIZE);
  const safeCurrentPage =
    totalPages > 0 ? Math.min(currentPage, totalPages - 1) : 0;
  const paginatedJobs = useMemo(() => {
    const startIndex = safeCurrentPage * SAVED_JOBS_PAGE_SIZE;

    return filteredJobs.slice(startIndex, startIndex + SAVED_JOBS_PAGE_SIZE);
  }, [filteredJobs, safeCurrentPage]);
  const pageItems = useMemo(
    () =>
      Array.from({ length: totalPages }, (_, pageNumber) => ({
        key: `saved-jobs-page-${pageNumber}`,
        page: pageNumber,
      })),
    [totalPages],
  );

  const handleExpiredSession = useCallback(() => {
    clearUser();
    router.replace("/login");
  }, [clearUser, router]);

  const loadStats = useCallback(async () => {
    if (!user) {
      return;
    }

    setLoadingStats(true);
    setStatsError(null);

    try {
      const response = await getSavedJobsStats();
      setStats(response);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        handleExpiredSession();
        return;
      }

      setStatsError("Could not load saved jobs statistics.");
    } finally {
      setLoadingStats(false);
    }
  }, [handleExpiredSession, user]);

  const loadJobs = useCallback(async () => {
    if (!user) {
      return;
    }

    setLoadingJobs(true);
    setJobsError(null);

    try {
      const response = await getSavedJobs();
      setJobs(response);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        handleExpiredSession();
        return;
      }

      setJobsError("Could not load your saved jobs.");
    } finally {
      setLoadingJobs(false);
    }
  }, [handleExpiredSession, user]);

  useEffect(() => {
    if (!isLoading && !user) {
      router.replace("/login");
      return;
    }
  }, [isLoading, user, router]);

  useEffect(() => {
    if (!user) {
      return;
    }

    void loadStats();
    void loadJobs();
  }, [loadJobs, loadStats, user]);

  useEffect(() => {
    if (totalPages === 0 && currentPage !== 0) {
      setCurrentPage(0);
      return;
    }

    if (totalPages > 0 && currentPage >= totalPages) {
      setCurrentPage(totalPages - 1);
    }
  }, [currentPage, totalPages]);

  async function handleStatusChange(postingId: string, newState: JobState) {
    if (!user) {
      return;
    }

    setActionError(null);
    setBusyPostingId(postingId);

    try {
      await updateSavedJobStatus(postingId, newState);

      setJobs((currentJobs) =>
        currentJobs.map((job) =>
          job.postingId === postingId ? { ...job, jobState: newState } : job,
        ),
      );

      await loadStats();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        handleExpiredSession();
        return;
      }

      setActionError("Could not update the job status.");
    } finally {
      setBusyPostingId(null);
    }
  }

  async function handleRemove(postingId: string) {
    if (!user) {
      return;
    }

    setActionError(null);
    setBusyPostingId(postingId);

    try {
      await deleteSavedJob(postingId);

      setJobs((currentJobs) =>
        currentJobs.filter((job) => job.postingId !== postingId),
      );

      await loadStats();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        handleExpiredSession();
        return;
      }

      setActionError("Could not remove the saved job.");
    } finally {
      setBusyPostingId(null);
    }
  }

  if (isLoading || !user) {
    return null;
  }

  return (
    <main className="saved-page">
      <div className="saved-container">
        <section className="saved-header">
          <p className="saved-eyebrow">YOUR JOB TRACKER</p>
          <h1>{selectedStatusLabel}</h1>
          <p className="saved-intro">
            Keep interesting opportunities in one place and track what happens
            after you decide to apply.
          </p>
        </section>

        <div className="saved-layout">
          <section className="saved-list-section">
            {actionError && (
              <p className="saved-action-error" role="alert">
                {actionError}
              </p>
            )}

            <div className="saved-list-heading">
              <div>
                <p className="saved-eyebrow">TRACKING</p>
                <h2>
                  {status ? `${selectedStatusLabel} jobs` : "Your tracked jobs"}
                </h2>
              </div>

              <p>
                {status && loadingJobs
                  ? "Loading jobs in this status..."
                  : status
                    ? `${filteredJobs.length} ${
                        filteredJobs.length === 1 ? "job" : "jobs"
                      } in this status.`
                    : "Update each job as your application moves through the process."}
              </p>
            </div>

            {loadingJobs ? (
              <div className="saved-state" aria-busy="true">
                {/* biome-ignore lint/a11y/useSemanticElements: Loading copy should remain a normal text element with status semantics. */}
                <p role="status">Loading saved jobs...</p>
                <div className="state-skeleton-list" aria-hidden="true">
                  <div className="state-skeleton-card">
                    <div className="jobs-loading-line jobs-loading-line-short" />
                    <div className="jobs-loading-line jobs-loading-line-long" />
                    <div className="jobs-loading-line jobs-loading-line-medium" />
                  </div>
                  <div className="state-skeleton-card">
                    <div className="jobs-loading-line jobs-loading-line-short" />
                    <div className="jobs-loading-line jobs-loading-line-long" />
                    <div className="jobs-loading-line jobs-loading-line-medium" />
                  </div>
                </div>
              </div>
            ) : jobsError ? (
              <div className="saved-state saved-state-error">
                <p role="alert">{jobsError}</p>
                <button type="button" onClick={() => void loadJobs()}>
                  Try again
                </button>
              </div>
            ) : jobs.length === 0 ? (
              <div className="saved-empty">
                <p className="saved-eyebrow">NO SAVED JOBS</p>
                <h3>Your list is empty for now.</h3>
                <p>Save jobs you want to revisit and they will appear here.</p>
                <Link className="state-action-link" href="/jobs">
                  Explore jobs
                </Link>
              </div>
            ) : filteredJobs.length === 0 ? (
              <div className="saved-empty">
                <p className="saved-eyebrow">NO JOBS</p>
                <h3>No jobs in this status yet.</h3>
                <p>
                  Choose another status in Job Overview or update a saved job
                  when your application moves forward.
                </p>
              </div>
            ) : (
              <>
                <ul className="saved-job-list">
                  {paginatedJobs.map((job) => {
                    const isBusy = busyPostingId === job.postingId;

                    return (
                      <li key={job.postingId}>
                        <article
                          className={`saved-job-card is-status-${job.jobState.toLowerCase()}`}
                        >
                          <div className="saved-job-main">
                            <div className="saved-job-topline">
                              <span>
                                {job.category
                                  ? formatCategoryLabel(job.category)
                                  : "Job opportunity"}
                              </span>

                              {job.freshnessClass && (
                                <span>{job.freshnessClass}</span>
                              )}
                            </div>

                            <h3>
                              <Link
                                href={`/jobs/${encodeURIComponent(
                                  job.postingId,
                                )}`}
                              >
                                {job.title ?? "Job title unavailable"}
                              </Link>
                            </h3>

                            <p className="saved-job-company">
                              {job.companyName ?? "Company unavailable"}
                              {job.location ? ` · ${job.location}` : ""}
                            </p>

                            <div className="saved-job-meta">
                              {job.workMode && <span>{job.workMode}</span>}
                              {job.employmentType && (
                                <span>{job.employmentType}</span>
                              )}
                              {job.postedDate && (
                                <span>Posted {job.postedDate}</span>
                              )}
                            </div>

                            {job.skills.length > 0 && (
                              <div className="saved-job-skills">
                                {job.skills.slice(0, 5).map((skill) => (
                                  <span key={`${job.postingId}-${skill}`}>
                                    {skill}
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>

                          <div className="saved-job-actions">
                            <label htmlFor={`status-${job.postingId}`}>
                              Application status
                            </label>

                            <select
                              id={`status-${job.postingId}`}
                              className={`is-status-${job.jobState.toLowerCase()}`}
                              value={job.jobState}
                              disabled={isBusy}
                              onChange={(event) =>
                                void handleStatusChange(
                                  job.postingId,
                                  event.target.value as JobState,
                                )
                              }
                            >
                              {SAVED_JOB_STATUS_OPTIONS.map((option) => (
                                <option key={option.value} value={option.value}>
                                  {option.label}
                                </option>
                              ))}
                            </select>

                            <button
                              type="button"
                              className="saved-remove-button"
                              disabled={isBusy}
                              onClick={() => void handleRemove(job.postingId)}
                            >
                              {isBusy ? "Working..." : "Remove"}
                            </button>
                          </div>
                        </article>
                      </li>
                    );
                  })}
                </ul>

                {totalPages > 1 ? (
                  <nav className="job-pagination" aria-label="Saved jobs pages">
                    <div className="job-pagination-edge">
                      {safeCurrentPage > 0 ? (
                        <button
                          type="button"
                          onClick={() =>
                            setCurrentPage((page) => Math.max(page - 1, 0))
                          }
                        >
                          Previous
                        </button>
                      ) : (
                        <span
                          className="job-pagination-disabled"
                          aria-disabled="true"
                        >
                          Previous
                        </span>
                      )}
                    </div>

                    <ol className="job-pagination-pages">
                      {pageItems.map(({ key, page }) => (
                        <li key={key}>
                          {page === safeCurrentPage ? (
                            <span
                              className="job-pagination-current"
                              aria-current="page"
                            >
                              {page + 1}
                            </span>
                          ) : (
                            <button
                              type="button"
                              aria-label={`Go to page ${page + 1}`}
                              onClick={() => setCurrentPage(page)}
                            >
                              {page + 1}
                            </button>
                          )}
                        </li>
                      ))}
                    </ol>

                    <div className="job-pagination-edge">
                      {safeCurrentPage < totalPages - 1 ? (
                        <button
                          type="button"
                          onClick={() =>
                            setCurrentPage((page) =>
                              Math.min(page + 1, totalPages - 1),
                            )
                          }
                        >
                          Next
                        </button>
                      ) : (
                        <span
                          className="job-pagination-disabled"
                          aria-disabled="true"
                        >
                          Next
                        </span>
                      )}
                    </div>
                  </nav>
                ) : null}
              </>
            )}
          </section>

          <section className="saved-overview-section">
            {loadingStats ? (
              <div className="saved-state" aria-busy="true">
                {/* biome-ignore lint/a11y/useSemanticElements: Loading copy should remain a normal text element with status semantics. */}
                <p role="status">Loading job overview...</p>
                <div className="state-skeleton-list" aria-hidden="true">
                  <div className="jobs-loading-line jobs-loading-line-medium" />
                  <div className="jobs-loading-line jobs-loading-line-long" />
                  <div className="jobs-loading-line jobs-loading-line-long" />
                </div>
              </div>
            ) : statsError ? (
              <div className="saved-state saved-state-error">
                <p role="alert">{statsError}</p>
                <button type="button" onClick={() => void loadStats()}>
                  Try again
                </button>
              </div>
            ) : (
              <JobStatusSummary stats={stats} activeStatus={status} />
            )}
          </section>
        </div>
      </div>
    </main>
  );
}
