import Link from "next/link";

type JobPaginationProps = {
  currentPage: number;
  totalPages: number;
  searchParams: {
    q?: string;
    category?: string;
    workMode?: string;
    location?: string;
  };
};

type PageItem = number | "start-ellipsis" | "end-ellipsis";

function getPageItems(currentPage: number, totalPages: number): PageItem[] {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, index) => index);
  }

  const lastPage = totalPages - 1;

  if (currentPage <= 3) {
    return [0, 1, 2, 3, 4, "end-ellipsis", lastPage];
  }

  if (currentPage >= lastPage - 3) {
    return [
      0,
      "start-ellipsis",
      lastPage - 4,
      lastPage - 3,
      lastPage - 2,
      lastPage - 1,
      lastPage,
    ];
  }

  return [
    0,
    "start-ellipsis",
    currentPage - 2,
    currentPage - 1,
    currentPage,
    currentPage + 1,
    currentPage + 2,
    "end-ellipsis",
    lastPage,
  ];
}

function buildPageHref(
  page: number,
  searchParams: JobPaginationProps["searchParams"],
) {
  const params = new URLSearchParams();

  if (searchParams.q) {
    params.set("q", searchParams.q);
  }

  if (searchParams.category) {
    params.set("category", searchParams.category);
  }

  if (searchParams.workMode) {
    params.set("workMode", searchParams.workMode);
  }

  if (searchParams.location) {
    params.set("location", searchParams.location);
  }

  if (page > 0) {
    params.set("page", String(page));
  }

  const query = params.toString();

  return query ? `/jobs?${query}` : "/jobs";
}

export default function JobPagination({
  currentPage,
  totalPages,
  searchParams,
}: JobPaginationProps) {
  if (totalPages <= 1) {
    return null;
  }

  const safeCurrentPage = Math.min(Math.max(currentPage, 0), totalPages - 1);
  const pageItems = getPageItems(safeCurrentPage, totalPages);
  const previousPage = safeCurrentPage - 1;
  const nextPage = safeCurrentPage + 1;

  return (
    <nav className="job-pagination" aria-label="Job results pages">
      <div className="job-pagination-edge">
        {safeCurrentPage > 0 ? (
          <Link href={buildPageHref(previousPage, searchParams)}>Previous</Link>
        ) : (
          <span className="job-pagination-disabled" aria-disabled="true">
            Previous
          </span>
        )}
      </div>

      <ol className="job-pagination-pages">
        {pageItems.map((item) => {
          if (item === "start-ellipsis" || item === "end-ellipsis") {
            return (
              <li
                key={item}
                className="job-pagination-ellipsis"
                aria-hidden="true"
              >
                ...
              </li>
            );
          }

          const isCurrentPage = item === safeCurrentPage;
          const label = String(item + 1);

          return (
            <li key={item}>
              {isCurrentPage ? (
                <span className="job-pagination-current" aria-current="page">
                  {label}
                </span>
              ) : (
                <Link
                  href={buildPageHref(item, searchParams)}
                  aria-label={`Go to page ${label}`}
                >
                  {label}
                </Link>
              )}
            </li>
          );
        })}
      </ol>

      <div className="job-pagination-edge">
        {safeCurrentPage < totalPages - 1 ? (
          <Link href={buildPageHref(nextPage, searchParams)}>Next</Link>
        ) : (
          <span className="job-pagination-disabled" aria-disabled="true">
            Next
          </span>
        )}
      </div>
    </nav>
  );
}
