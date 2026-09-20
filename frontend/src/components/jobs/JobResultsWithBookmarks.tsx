"use client";

import { useEffect, useState } from "react";
import JobResultItem from "@/components/jobs/JobResultItem";
import { ApiError, getSavedJobs, type JobState } from "@/lib/api";
import type { JobSearchResult } from "@/lib/jobs";

type SavedStateMap = Record<string, JobState | "UNKNOWN" | null>;

type JobResultsWithBookmarksProps = {
  jobs: JobSearchResult[];
};

export default function JobResultsWithBookmarks({
  jobs,
}: JobResultsWithBookmarksProps) {
  const [savedStates, setSavedStates] = useState<SavedStateMap>({});
  const [hasLoadedSavedStates, setHasLoadedSavedStates] = useState(false);

  useEffect(() => {
    let isMounted = true;

    async function loadSavedStates() {
      setHasLoadedSavedStates(false);

      try {
        const savedJobs = await getSavedJobs();
        const nextSavedStates = savedJobs.reduce<SavedStateMap>(
          (states, savedJob) => {
            states[savedJob.postingId] = savedJob.jobState;
            return states;
          },
          {},
        );

        if (isMounted) {
          setSavedStates(nextSavedStates);
        }
      } catch (error) {
        if (!(error instanceof ApiError && error.status === 401)) {
          setSavedStates({});
        }
      } finally {
        if (isMounted) {
          setHasLoadedSavedStates(true);
        }
      }
    }

    void loadSavedStates();

    return () => {
      isMounted = false;
    };
  }, []);

  function handleSavedStateChange(
    postingId: string,
    state: JobState | "UNKNOWN" | null,
  ) {
    setSavedStates((currentStates) => ({
      ...currentStates,
      [postingId]: state,
    }));
  }

  return (
    <div className="job-results-list">
      {jobs.map((job) => (
        <JobResultItem
          key={job.id}
          job={job}
          savedState={
            hasLoadedSavedStates ? (savedStates[job.id] ?? null) : null
          }
          isCheckingSavedState={!hasLoadedSavedStates}
          onSavedStateChange={handleSavedStateChange}
        />
      ))}
    </div>
  );
}
