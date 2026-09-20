"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, getTopMatches, type JobMatchResponse } from "@/lib/api";
import MatchSummary from "./MatchSummary";

type JobDetailsMatchSectionProps = {
  postingId: string;
};

type MatchState =
  | { status: "loading" }
  | { status: "ready"; match: JobMatchResponse | null }
  | { status: "unauthenticated" }
  | { status: "profile-incomplete" }
  | { status: "error" };

export default function JobDetailsMatchSection({
  postingId,
}: JobDetailsMatchSectionProps) {
  const [matchState, setMatchState] = useState<MatchState>({
    status: "loading",
  });

  useEffect(() => {
    let isActive = true;

    async function loadMatch() {
      try {
        const matches = await getTopMatches();
        const currentMatch =
          matches.find((match) => match.postingId === postingId) ?? null;

        if (isActive) {
          setMatchState({ status: "ready", match: currentMatch });
        }
      } catch (error) {
        if (!isActive) {
          return;
        }

        if (error instanceof ApiError && error.status === 401) {
          setMatchState({ status: "unauthenticated" });
          return;
        }

        if (error instanceof ApiError && error.status === 422) {
          setMatchState({ status: "profile-incomplete" });
          return;
        }

        setMatchState({ status: "error" });
      }
    }

    void loadMatch();

    return () => {
      isActive = false;
    };
  }, [postingId]);

  return (
    <section className="job-details-match-note">
      <p className="job-details-section-label">MATCH</p>
      <h2>Match information</h2>

      {matchState.status === "loading" ? (
        <p>Loading match information...</p>
      ) : null}

      {matchState.status === "unauthenticated" ? (
        <p>
          <Link href="/login">Sign in</Link> to see how this job matches your
          profile.
        </p>
      ) : null}

      {matchState.status === "profile-incomplete" ? (
        <p>
          Matching is unavailable until your profile has the required skills.{" "}
          <Link href="/profile">Complete your profile</Link> to compare this job
          with your skills.
        </p>
      ) : null}

      {matchState.status === "error" ? (
        <p>We could not load match information right now.</p>
      ) : null}

      {matchState.status === "ready" && matchState.match ? (
        <MatchSummary match={matchState.match} />
      ) : null}

      {matchState.status === "ready" && !matchState.match ? (
        <p>This job is not in your current top matches.</p>
      ) : null}
    </section>
  );
}
