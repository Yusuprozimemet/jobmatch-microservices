import "server-only";

import type {
  JobDetailsResponse,
  JobFiltersResponse,
  JobSearchParams,
  JobSearchResponse,
  PageResponse,
} from "@/lib/api";
import { BACKEND_API_URL } from "@/lib/config";

export class BackendRequestError extends Error {
  status: number;

  constructor(status: number, statusText: string) {
    super(`Backend request failed: ${status} ${statusText}`);
    this.name = "BackendRequestError";
    this.status = status;
  }
}

async function serverRequest<T>(path: string): Promise<T> {
  const response = await fetch(`${BACKEND_API_URL}${path}`, {
    cache: "no-store",
  });

  if (!response.ok) {
    throw new BackendRequestError(response.status, response.statusText);
  }

  return response.json() as Promise<T>;
}

export function getJobsServer(
  params: JobSearchParams = {},
): Promise<PageResponse<JobSearchResponse>> {
  const searchParams = new URLSearchParams();

  const query = params.q?.trim();
  const page =
    typeof params.page === "number" &&
    Number.isInteger(params.page) &&
    params.page >= 0
      ? params.page
      : 0;
  const size =
    typeof params.size === "number" &&
    Number.isInteger(params.size) &&
    params.size >= 1 &&
    params.size <= 100
      ? params.size
      : 20;

  if (query) {
    searchParams.set("q", query);
  }

  if (params.category) {
    searchParams.set("category", params.category);
  }

  if (params.workMode) {
    searchParams.set("workMode", params.workMode);
  }

  if (params.location) {
    searchParams.set("location", params.location);
  }

  searchParams.set("page", String(page));
  searchParams.set("size", String(size));

  const requestQuery = searchParams.toString();
  const path = requestQuery ? `/api/jobs?${requestQuery}` : "/api/jobs";

  return serverRequest<PageResponse<JobSearchResponse>>(path);
}

export function getJobFiltersServer(): Promise<JobFiltersResponse> {
  return serverRequest<JobFiltersResponse>("/api/jobs/filters");
}

export function getJobDetailsServer(
  postingId: string,
): Promise<JobDetailsResponse> {
  return serverRequest<JobDetailsResponse>(
    `/api/jobs/${encodeURIComponent(postingId)}`,
  );
}
