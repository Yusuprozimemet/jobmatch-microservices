// "use client" is needed here so matched job cards can reuse the existing
// bookmark state flow without changing the matching API.
"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import SavedJobBookmarkButton from "@/components/jobs/SavedJobBookmarkButton";
import {
  ApiError,
  getSavedJobs,
  type JobMatchResponse,
  type JobState,
} from "@/lib/api";
import MatchSummary from "./MatchSummary";

type MatchResultsListProps = {
  matches: JobMatchResponse[];
};

type SavedStateMap = Record<string, JobState | "UNKNOWN" | null>;

export default function MatchResultsList({ matches }: MatchResultsListProps) {
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
        if (isMounted && !(error instanceof ApiError && error.status === 401)) {
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
    <div className="top-matches-list">
      {matches.map((match) => (
        <article className="top-match-card" key={match.postingId}>
          <div className="top-match-main">
            <div className="top-match-heading">
              <div>
                <p className="top-match-company">{match.company}</p>
                <h3>
                  <Link href={`/jobs/${encodeURIComponent(match.postingId)}`}>
                    {match.title}
                  </Link>
                </h3>
                {match.category ? (
                  <p className="top-match-category">{match.category}</p>
                ) : null}
              </div>

              <SavedJobBookmarkButton
                postingId={match.postingId}
                initialState={
                  hasLoadedSavedStates
                    ? (savedStates[match.postingId] ?? null)
                    : null
                }
                isCheckingInitialState={!hasLoadedSavedStates}
                onStateChange={handleSavedStateChange}
              />
            </div>
          </div>

          <MatchSummary match={match} />
        </article>
      ))}
    </div>
  );
}
