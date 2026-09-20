"use client";

import { type KeyboardEvent, useEffect, useId, useMemo, useState } from "react";
import { formatCategoryLabel } from "@/lib/category";

type JobFiltersProps = {
  locations: string[];
  categories: string[];
  workModes: string[];
  searchQuery?: string;
  selectedLocation?: string;
  selectedCategory?: string;
  selectedWorkMode?: string;
};

export default function JobFilters({
  locations,
  categories,
  workModes,
  searchQuery = "",
  selectedLocation = "",
  selectedCategory = "",
  selectedWorkMode = "",
}: JobFiltersProps) {
  const locationInputId = useId();
  const locationListboxId = useId();
  const filterPanelBodyId = useId();
  const [locationInput, setLocationInput] = useState(selectedLocation);
  const [isLocationListOpen, setIsLocationListOpen] = useState(false);
  const [activeLocationIndex, setActiveLocationIndex] = useState(-1);
  const [isFilterPanelOpen, setIsFilterPanelOpen] = useState(false);
  const activeFilters = [
    formatCategoryLabel(selectedCategory),
    selectedWorkMode,
    selectedLocation,
  ].filter(Boolean);
  const clearFiltersHref = searchQuery
    ? `/jobs?${new URLSearchParams({ q: searchQuery }).toString()}`
    : "/jobs";

  const locationSuggestions = useMemo(() => {
    const seenLocations = new Set<string>();

    return locations
      .flatMap((location) =>
        location
          .split(";")
          .map((part) => part.trim())
          .map((part) => part.split(",")[0]?.trim() || part)
          .filter((part) => {
            if (!part) {
              return false;
            }

            const normalizedPart = part.toLowerCase();

            if (seenLocations.has(normalizedPart)) {
              return false;
            }

            seenLocations.add(normalizedPart);
            return true;
          }),
      )
      .sort((leftLocation, rightLocation) =>
        leftLocation.localeCompare(rightLocation, undefined, {
          sensitivity: "base",
        }),
      );
  }, [locations]);

  const visibleLocationSuggestions = useMemo(() => {
    const query = locationInput.trim().toLowerCase();

    if (!query) {
      return [];
    }

    const startsWithMatches: string[] = [];
    const containsMatches: string[] = [];

    for (const location of locationSuggestions) {
      const normalizedLocation = location.toLowerCase();

      if (normalizedLocation.startsWith(query)) {
        startsWithMatches.push(location);
      } else if (normalizedLocation.includes(query)) {
        containsMatches.push(location);
      }
    }

    return [...startsWithMatches, ...containsMatches].slice(0, 8);
  }, [locationInput, locationSuggestions]);

  useEffect(() => {
    setLocationInput(selectedLocation);
  }, [selectedLocation]);

  function selectLocation(location: string) {
    setLocationInput(location);
    setIsLocationListOpen(false);
    setActiveLocationIndex(-1);
  }

  function handleLocationKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (visibleLocationSuggestions.length === 0) {
        return;
      }

      setIsLocationListOpen(true);
      setActiveLocationIndex((currentIndex) =>
        Math.min(currentIndex + 1, visibleLocationSuggestions.length - 1),
      );
      return;
    }

    if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveLocationIndex((currentIndex) => Math.max(currentIndex - 1, 0));
      return;
    }

    if (event.key === "Escape") {
      setIsLocationListOpen(false);
      setActiveLocationIndex(-1);
      return;
    }

    if (
      event.key === "Enter" &&
      isLocationListOpen &&
      activeLocationIndex >= 0
    ) {
      event.preventDefault();
      selectLocation(visibleLocationSuggestions[activeLocationIndex]);
    }
  }

  return (
    <div className={`job-filter-panel${isFilterPanelOpen ? " is-open" : ""}`}>
      <button
        className="job-filter-panel-toggle"
        type="button"
        aria-controls={filterPanelBodyId}
        aria-expanded={isFilterPanelOpen}
        onClick={() => setIsFilterPanelOpen((isOpen) => !isOpen)}
      >
        <span>{isFilterPanelOpen ? "Hide filters" : "Show filters"}</span>
        <span>
          {activeFilters.length > 0
            ? `${activeFilters.length} active`
            : "No filters"}
        </span>
      </button>

      {activeFilters.length > 0 ? (
        <p className="job-filter-active-summary">{activeFilters.join(" / ")}</p>
      ) : null}

      <div className="job-filter-panel-body" id={filterPanelBodyId}>
        <form className="job-filters-form" action="/jobs">
          {searchQuery && <input type="hidden" name="q" value={searchQuery} />}

          <div className="job-filter-group">
            <label htmlFor="category">Category</label>

            <select
              id="category"
              name="category"
              defaultValue={selectedCategory}
            >
              <option value="">All categories</option>

              {categories.map((category) => (
                <option key={category} value={category}>
                  {formatCategoryLabel(category)}
                </option>
              ))}
            </select>
          </div>

          <div className="job-filter-group">
            <label htmlFor="work-mode">Work mode</label>

            <select
              id="work-mode"
              name="workMode"
              defaultValue={selectedWorkMode}
            >
              <option value="">Any work mode</option>

              {workModes.map((workMode) => (
                <option key={workMode} value={workMode}>
                  {workMode}
                </option>
              ))}
            </select>
          </div>

          <div className="job-filter-group">
            <label htmlFor={locationInputId}>Location</label>

            <div className="job-location-combobox">
              <input
                id={locationInputId}
                name="location"
                type="text"
                role="combobox"
                aria-autocomplete="list"
                aria-controls={locationListboxId}
                aria-expanded={isLocationListOpen}
                aria-activedescendant={
                  activeLocationIndex >= 0
                    ? `${locationListboxId}-${activeLocationIndex}`
                    : undefined
                }
                autoComplete="off"
                value={locationInput}
                onBlur={() => setIsLocationListOpen(false)}
                onChange={(event) => {
                  const nextLocationInput = event.target.value;

                  setLocationInput(nextLocationInput);
                  setIsLocationListOpen(Boolean(nextLocationInput.trim()));
                  setActiveLocationIndex(-1);
                }}
                onFocus={() =>
                  setIsLocationListOpen(Boolean(locationInput.trim()))
                }
                onKeyDown={handleLocationKeyDown}
                placeholder="Any location"
              />

              {isLocationListOpen && visibleLocationSuggestions.length > 0 ? (
                <div
                  className="job-location-suggestions"
                  id={locationListboxId}
                  role="listbox"
                >
                  {visibleLocationSuggestions.map((location, index) => (
                    <div
                      id={`${locationListboxId}-${index}`}
                      className="job-location-suggestion"
                      key={location}
                      role="option"
                      tabIndex={-1}
                      aria-selected={activeLocationIndex === index}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => selectLocation(location)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          selectLocation(location);
                        }
                      }}
                    >
                      {location}
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          </div>

          <div className="job-filter-actions">
            <button className="job-filters-submit" type="submit">
              Apply filters
            </button>

            {activeFilters.length > 0 ? (
              <a className="job-filters-clear" href={clearFiltersHref}>
                Clear filters
              </a>
            ) : null}
          </div>
        </form>
      </div>
    </div>
  );
}
