"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, getTopMatches, type JobMatchResponse } from "@/lib/api";
import MatchResultsList from "./MatchResultsList";

type MatchState =
  | { status: "loading" }
  | { status: "ready"; matches: JobMatchResponse[] }
  | { status: "unauthenticated" }
  | { status: "profile-incomplete" }
  | { status: "error" };

export default function MatchesContent() {
  const [matchState, setMatchState] = useState<MatchState>({
    status: "loading",
  });

  useEffect(() => {
    let isActive = true;

    async function loadMatches() {
      try {
        const matches = await getTopMatches();

        if (isActive) {
          setMatchState({ status: "ready", matches });
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

    void loadMatches();

    return () => {
      isActive = false;
    };
  }, []);

  if (matchState.status === "loading") {
    return (
      <section className="matches-panel" aria-busy="true">
        {/* biome-ignore lint/a11y/useSemanticElements: Loading copy should remain a normal text element with status semantics. */}
        <p className="top-matches-copy" role="status">
          Loading your job matches...
        </p>
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
          <div className="state-skeleton-card">
            <div className="jobs-loading-line jobs-loading-line-short" />
            <div className="jobs-loading-line jobs-loading-line-long" />
            <div className="jobs-loading-line jobs-loading-line-medium" />
          </div>
        </div>
      </section>
    );
  }

  if (matchState.status === "unauthenticated") {
    return (
      <section className="matches-panel">
        <p className="top-matches-copy">
          Sign in to see jobs ranked against your profile skills.
        </p>
        <Link className="top-matches-link" href="/login">
          Sign in
        </Link>
      </section>
    );
  }

  if (matchState.status === "profile-incomplete") {
    return (
      <section className="matches-panel">
        <p className="top-matches-copy">
          Matching is unavailable until your profile has the required skills.
          Complete your profile so JobMatch can compare jobs with your skills.
        </p>
        <Link className="top-matches-link" href="/profile">
          Complete profile
        </Link>
      </section>
    );
  }

  if (matchState.status === "error") {
    return (
      <section className="matches-panel" role="alert">
        <p className="top-matches-copy">
          We could not load your matches right now. Please try again in a
          moment.
        </p>
      </section>
    );
  }

  if (matchState.matches.length === 0) {
    return (
      <section className="matches-panel">
        <p className="top-matches-copy">
          No matching jobs were found for your current profile. Update your
          skills or browse all jobs while new matches are calculated.
        </p>
        <Link className="top-matches-link" href="/profile">
          Update profile
        </Link>
        <Link
          className="top-matches-link top-matches-link-secondary"
          href="/jobs"
        >
          Browse jobs
        </Link>
      </section>
    );
  }

  return <MatchResultsList matches={matchState.matches} />;
}
