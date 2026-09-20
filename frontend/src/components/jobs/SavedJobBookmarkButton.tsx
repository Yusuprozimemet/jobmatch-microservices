"use client";

import { type MouseEvent, useEffect, useRef, useState } from "react";
import {
  ApiError,
  deleteSavedJob,
  getSavedJobs,
  type JobState,
  saveJob,
} from "@/lib/api";

type SavedJobBookmarkState = JobState | "UNKNOWN" | null;
type BookmarkAction = "checking" | "idle" | "saving" | "removing";

type SavedJobBookmarkButtonProps = {
  postingId: string;
  initialState?: SavedJobBookmarkState;
  isCheckingInitialState?: boolean;
  variant?: "icon" | "cta";
  onStateChange?: (postingId: string, state: SavedJobBookmarkState) => void;
};

async function getSavedJobState(
  postingId: string,
): Promise<SavedJobBookmarkState> {
  const savedJobs = await getSavedJobs();
  const savedJob = savedJobs.find((job) => job.postingId === postingId);

  return savedJob?.jobState ?? null;
}

export default function SavedJobBookmarkButton({
  postingId,
  initialState,
  isCheckingInitialState = false,
  variant = "icon",
  onStateChange,
}: SavedJobBookmarkButtonProps) {
  const shouldCheckInitialState = initialState === undefined;
  const activePostingIdRef = useRef(postingId);
  const [savedState, setSavedState] = useState<SavedJobBookmarkState>(
    initialState ?? null,
  );
  const [action, setAction] = useState<BookmarkAction>(
    shouldCheckInitialState ? "checking" : "idle",
  );
  const [error, setError] = useState<string | null>(null);

  activePostingIdRef.current = postingId;

  function isCurrentPosting(requestPostingId: string) {
    return activePostingIdRef.current === requestPostingId;
  }

  function updateState(nextState: SavedJobBookmarkState) {
    setSavedState(nextState);
    onStateChange?.(postingId, nextState);
  }

  useEffect(() => {
    if (!shouldCheckInitialState) {
      setSavedState(initialState);
      setAction("idle");
      setError(null);
      return;
    }

    let isMounted = true;

    async function loadSavedState() {
      setSavedState(null);
      setAction("checking");
      setError(null);

      try {
        const loadedState = await getSavedJobState(postingId);

        if (isMounted) {
          setSavedState(loadedState);
        }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return;
        }

        if (isMounted) {
          setError("Could not check saved status.");
        }
      } finally {
        if (isMounted) {
          setAction("idle");
        }
      }
    }

    void loadSavedState();

    return () => {
      isMounted = false;
    };
  }, [initialState, postingId, shouldCheckInitialState]);

  async function handleSave() {
    const requestPostingId = postingId;

    setAction("saving");
    setError(null);

    try {
      await saveJob(requestPostingId);

      if (isCurrentPosting(requestPostingId)) {
        updateState("SAVED");
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        try {
          const existingState = await getSavedJobState(requestPostingId);

          if (isCurrentPosting(requestPostingId)) {
            updateState(existingState ?? "UNKNOWN");
          }
        } catch {
          if (isCurrentPosting(requestPostingId)) {
            updateState("UNKNOWN");
          }
        }

        return;
      }

      if (error instanceof ApiError && error.status === 401) {
        if (isCurrentPosting(requestPostingId)) {
          setError("Please log in to save this job.");
        }

        return;
      }

      if (isCurrentPosting(requestPostingId)) {
        setError("Could not save this job. Please try again.");
      }
    } finally {
      if (isCurrentPosting(requestPostingId)) {
        setAction("idle");
      }
    }
  }

  async function handleRemove() {
    const requestPostingId = postingId;

    setAction("removing");
    setError(null);

    try {
      await deleteSavedJob(requestPostingId);

      if (isCurrentPosting(requestPostingId)) {
        updateState(null);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        if (isCurrentPosting(requestPostingId)) {
          setError("Please log in to update this job.");
        }

        return;
      }

      if (error instanceof ApiError && error.status === 404) {
        if (isCurrentPosting(requestPostingId)) {
          updateState(null);
        }

        return;
      }

      if (isCurrentPosting(requestPostingId)) {
        setError("Could not remove this job. Please try again.");
      }
    } finally {
      if (isCurrentPosting(requestPostingId)) {
        setAction("idle");
      }
    }
  }

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    event.stopPropagation();
    void (canRemove ? handleRemove() : handleSave());
  }

  const isBusy = action !== "idle" || isCheckingInitialState;
  const isSaved = savedState !== null;
  const canRemove = isSaved;
  const ariaLabel = isSaved ? "Remove from saved jobs" : "Save job";
  const buttonText =
    action === "checking" || isCheckingInitialState
      ? "Checking..."
      : action === "saving"
        ? "Saving..."
        : action === "removing"
          ? "Removing..."
          : isSaved
            ? "Saved"
            : "Save job";

  return (
    <div className={`saved-bookmark-control is-${variant}`}>
      <button
        type="button"
        className={`saved-bookmark-button is-${variant}${
          isSaved ? " is-saved" : ""
        }`}
        aria-label={ariaLabel}
        aria-pressed={isSaved}
        disabled={isBusy}
        onClick={handleClick}
      >
        <svg
          aria-hidden="true"
          focusable="false"
          viewBox="0 0 24 24"
          width="20"
          height="20"
        >
          <path d="M7 4.75C7 3.78 7.78 3 8.75 3h6.5C16.22 3 17 3.78 17 4.75V20l-5-3.2L7 20V4.75Z" />
        </svg>
        {variant === "cta" ? <span>{buttonText}</span> : null}
      </button>

      {error ? (
        <p className="saved-bookmark-error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
