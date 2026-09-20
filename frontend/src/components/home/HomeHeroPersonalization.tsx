"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useAuth } from "@/context/AuthContext";
import {
  ApiError,
  getSavedJobsStats,
  type SavedJobsStatsResponse,
} from "@/lib/api";

const EMPTY_STATS: Required<SavedJobsStatsResponse> = {
  SAVED: 0,
  APPLIED: 0,
  REJECTED: 0,
  ACCEPTED: 0,
  DECLINED: 0,
};

function getTrackedTotal(stats: SavedJobsStatsResponse) {
  return (
    (stats.SAVED ?? 0) +
    (stats.APPLIED ?? 0) +
    (stats.ACCEPTED ?? 0) +
    (stats.REJECTED ?? 0) +
    (stats.DECLINED ?? 0)
  );
}

function GuestHeroPanel() {
  return (
    <section className="hero-panel" aria-label="JobMatch product preview">
      <div className="preview-label">TODAY&apos;S MATCH</div>

      <div className="preview-score">86%</div>

      <p className="preview-title">Frontend Developer</p>
      <p className="preview-company">Amsterdam &middot; Hybrid</p>

      <div className="preview-divider" />

      <div className="preview-row">
        <span>Skills matched</span>
        <strong>7 of 9</strong>
      </div>

      <div className="preview-row">
        <span>Listing signal</span>
        <strong>Fresh</strong>
      </div>

      <p className="preview-note">
        You match most of the required frontend skills. Two skills are missing.
      </p>
    </section>
  );
}

function LoadingHeroPanel() {
  return (
    <section className="hero-panel" aria-label="Loading personalized home">
      <div className="preview-label">JOBMATCH</div>
      <p className="tracker-panel-state">Loading your home...</p>
    </section>
  );
}

function TrackerHeroPanel() {
  const { clearUser } = useAuth();
  const [stats, setStats] = useState<SavedJobsStatsResponse>(EMPTY_STATS);
  const [isLoadingStats, setIsLoadingStats] = useState(true);
  const [statsError, setStatsError] = useState("");

  useEffect(() => {
    let isActive = true;

    async function loadStats() {
      setIsLoadingStats(true);
      setStatsError("");

      try {
        const response = await getSavedJobsStats();

        if (isActive) {
          setStats(response);
        }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          clearUser();
          return;
        }

        if (isActive) {
          setStatsError("Could not load your tracker summary right now.");
        }
      } finally {
        if (isActive) {
          setIsLoadingStats(false);
        }
      }
    }

    void loadStats();

    return () => {
      isActive = false;
    };
  }, [clearUser]);

  const trackedTotal = getTrackedTotal(stats);

  return (
    <section className="hero-panel tracker-panel" aria-label="Job tracker">
      <div className="preview-label">YOUR JOB TRACKER</div>

      {isLoadingStats ? (
        <p className="tracker-panel-state">Loading your tracker...</p>
      ) : statsError ? (
        <p className="tracker-panel-state">{statsError}</p>
      ) : (
        <>
          <div className="tracker-total">
            <strong>{trackedTotal}</strong>
            <span>Tracked jobs</span>
          </div>

          <div className="preview-divider" />

          <dl className="tracker-stat-grid">
            <div>
              <dt>Not applied</dt>
              <dd>{stats.SAVED ?? 0}</dd>
            </div>
            <div>
              <dt>Applied</dt>
              <dd>{stats.APPLIED ?? 0}</dd>
            </div>
            <div>
              <dt>Accepted</dt>
              <dd>{stats.ACCEPTED ?? 0}</dd>
            </div>
            <div>
              <dt>Rejected</dt>
              <dd>{stats.REJECTED ?? 0}</dd>
            </div>
            <div>
              <dt>Declined</dt>
              <dd>{stats.DECLINED ?? 0}</dd>
            </div>
          </dl>
        </>
      )}

      <Link className="tracker-panel-link" href="/saved">
        Continue tracking
      </Link>
    </section>
  );
}

export function HomeHeroActions() {
  const { user, isLoading } = useAuth();
  const isAuthenticated = !isLoading && Boolean(user);

  return (
    <div className="hero-actions">
      <Link className="primary-link" href="/jobs">
        Explore jobs
      </Link>

      {!isLoading && (
        <Link
          className="secondary-link"
          href={isAuthenticated ? "/profile" : "/register"}
        >
          {isAuthenticated ? "Update your profile" : "Create your profile"}
        </Link>
      )}
    </div>
  );
}

export function HomeHeroPanel() {
  const { user, isLoading } = useAuth();
  const isAuthenticated = !isLoading && Boolean(user);

  if (isLoading) {
    return <LoadingHeroPanel />;
  }

  return isAuthenticated ? <TrackerHeroPanel /> : <GuestHeroPanel />;
}
