import Link from "next/link";
import JobFilters from "@/components/jobs/JobFilters";
import JobPagination from "@/components/jobs/JobPagination";
import JobResultsWithBookmarks from "@/components/jobs/JobResultsWithBookmarks";
import TopMatchesSection from "@/components/jobs/TopMatchesSection";
import { mapJobSearchResponse, sortJobsByFreshness } from "@/lib/jobs";
import { getJobFiltersServer, getJobsServer } from "@/lib/jobs-server";

type SearchParamValue = string | string[] | undefined;

type JobsPageProps = {
  searchParams: Promise<{
    q?: SearchParamValue;
    category?: SearchParamValue;
    workMode?: SearchParamValue;
    location?: SearchParamValue;
    page?: SearchParamValue;
  }>;
};

function getSingleSearchParam(value: SearchParamValue): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function parsePageParam(value: SearchParamValue): number {
  const pageValue = getSingleSearchParam(value);

  if (!pageValue) {
    return 0;
  }

  const parsedPage = Number(pageValue);

  if (!Number.isInteger(parsedPage) || parsedPage < 0) {
    return 0;
  }

  return parsedPage;
}

export default async function JobsPage({ searchParams }: JobsPageProps) {
  const params = await searchParams;
  const q = getSingleSearchParam(params.q);
  const category = getSingleSearchParam(params.category);
  const workMode = getSingleSearchParam(params.workMode);
  const location = getSingleSearchParam(params.location);
  const page = parsePageParam(params.page);

  const searchQuery = q?.trim() ?? "";

  const [filters, jobResponse] = await Promise.all([
    getJobFiltersServer(),
    getJobsServer({
      q: searchQuery,
      category,
      workMode,
      location,
      page,
      size: 20,
    }),
  ]);

  let jobs = jobResponse.content.map(mapJobSearchResponse);

  jobs = sortJobsByFreshness(jobs);

  const safeTotalPages = Math.max(jobResponse.totalPages, 0);
  const isPageInRange =
    safeTotalPages > 0 &&
    jobResponse.page >= 0 &&
    jobResponse.page < safeTotalPages;
  const currentPage = isPageInRange ? jobResponse.page : 0;
  const totalJobs = jobResponse.totalElements;

  return (
    <main className="jobs-page">
      <div className="jobs-container">
        <header className="jobs-header">
          <p className="jobs-eyebrow">EXPLORE OPPORTUNITIES</p>

          <div className="jobs-heading-row">
            <div>
              <h1>Find jobs</h1>

              <p>
                Search fresh opportunities and focus on roles that are worth
                your time.
              </p>
            </div>
          </div>

          <form className="jobs-search" action="/jobs">
            {category && (
              <input type="hidden" name="category" value={category} />
            )}

            {workMode && (
              <input type="hidden" name="workMode" value={workMode} />
            )}

            {location && (
              <input type="hidden" name="location" value={location} />
            )}

            <label className="sr-only" htmlFor="jobs-search-input">
              Search by job title, keyword, or skill
            </label>

            <input
              id="jobs-search-input"
              name="q"
              type="search"
              defaultValue={searchQuery}
              placeholder="Job title, keyword, or skill"
            />

            <button type="submit">Search</button>
          </form>
        </header>

        <section className="jobs-layout">
          <aside className="jobs-filters">
            <div className="jobs-filters-header">
              <p className="jobs-section-label">FILTERS</p>
              <h2>Refine results</h2>
            </div>

            <JobFilters
              locations={filters.locations}
              categories={filters.categories}
              workModes={filters.workModes}
              searchQuery={searchQuery}
              selectedLocation={location}
              selectedCategory={category}
              selectedWorkMode={workMode}
            />
          </aside>

          <section className="jobs-results" aria-live="polite">
            <TopMatchesSection />

            <div className="jobs-results-header">
              <div>
                <p className="jobs-section-label">RESULTS</p>

                <h2>
                  {searchQuery
                    ? `Jobs matching “${searchQuery}”`
                    : "Explore available jobs"}
                </h2>
              </div>

              <p className="jobs-results-count">
                {totalJobs} {totalJobs === 1 ? "job" : "jobs"}
              </p>
            </div>

            {jobs.length > 0 ? (
              <JobResultsWithBookmarks jobs={jobs} />
            ) : (
              <div className="jobs-empty">
                <h3>No jobs found</h3>

                <p>
                  Try a different job title, keyword, or skill to broaden your
                  search.
                </p>

                <Link className="state-action-link" href="/jobs">
                  Clear search and filters
                </Link>
              </div>
            )}

            {isPageInRange && (
              <JobPagination
                currentPage={currentPage}
                totalPages={safeTotalPages}
                searchParams={{
                  q: searchQuery,
                  category,
                  workMode,
                  location,
                }}
              />
            )}
          </section>
        </section>
      </div>
    </main>
  );
}
