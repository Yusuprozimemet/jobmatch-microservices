export type RegisterRequest = {
  name: string;
  email: string;
  password: string;
  acceptedTerms: boolean;
};

export type RegisterResponse = {
  id: string;
  email: string;
  name: string;
  message: string;
};

export type LoginRequest = {
  email: string;
  password: string;
};

export type ForgotPasswordRequest = {
  email: string;
};

export type ResetPasswordRequest = {
  token: string;
  newPassword: string;
};

export type LoginResponse = {
  email: string;
  name: string;
};

export type CurrentUserResponse = {
  id: string;
  email: string;
  name: string;
};

export type UpdateCurrentUserRequest = {
  name: string;
  email: string;
};

export type ProfileResponse = {
  userId: string;
  skills: string[];
  category: string | null;
  preferredCity: string | null;
  workMode: string | null;
  experienceLevel: string | null;
  employmentType: string | null;
  salaryPreference: number | null;
};

export type PageResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type UpdateProfileRequest = {
  skills: string[];
  category: string | null;
  preferredCity: string | null;
  workMode: string | null;
  experienceLevel: string | null;
  employmentType: string | null;
  salaryPreference: number | null;
};

type ProblemDetail = {
  title?: string;
  detail?: string;
  status?: number;
};

export class ApiError extends Error {
  status: number;
  detail?: string;

  constructor(status: number, message: string, detail?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}

// One refresh at a time. The refresh token works once, so a second refresh sent with the same
// cookie would be refused and sign the user out: concurrent 401s wait for the same one.
let refreshing: Promise<boolean> | null = null;

function refreshTokens(): Promise<boolean> {
  refreshing ??= fetch("/api/auth/refresh", {
    method: "POST",
    credentials: "include",
  })
    .then(
      (response) => response.ok,
      () => false,
    )
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

// The access token lives 15 minutes, so a 401 may only mean it expired: refresh once and
// retry once. Not for /api/auth/ itself, where a 401 is a wrong password or a spent token.
async function fetchWithRefresh(
  path: string,
  init: RequestInit,
): Promise<Response> {
  const response = await fetch(path, init);
  if (response.status !== 401 || path.startsWith("/api/auth/")) {
    return response;
  }
  return (await refreshTokens()) ? fetch(path, init) : response;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetchWithRefresh(path, {
    ...options,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!response.ok) {
    const contentType = response.headers.get("content-type");

    if (contentType?.includes("application/json")) {
      const problem = (await response.json()) as ProblemDetail;

      throw new ApiError(
        response.status,
        problem.title ?? `Request failed with status ${response.status}`,
        problem.detail,
      );
    }

    throw new ApiError(
      response.status,
      `Request failed with status ${response.status}`,
    );
  }

  const contentType = response.headers.get("content-type");

  if (!contentType?.includes("application/json")) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

export function registerUser(
  payload: RegisterRequest,
): Promise<RegisterResponse> {
  return request<RegisterResponse>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function loginUser(payload: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function forgotPassword(payload: ForgotPasswordRequest): Promise<void> {
  return request<void>("/api/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function resetPassword(payload: ResetPasswordRequest): Promise<void> {
  return request<void>("/api/auth/reset-password", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function logoutUser(): Promise<void> {
  return request<void>("/api/auth/logout", {
    method: "POST",
  });
}

export async function getCurrentUser(): Promise<CurrentUserResponse | null> {
  const response = await fetchWithRefresh("/api/users/me", {
    credentials: "include",
  });

  if (response.status === 401) {
    return null;
  }

  if (!response.ok) {
    throw new ApiError(
      response.status,
      `Request failed with status ${response.status}`,
    );
  }

  return response.json() as Promise<CurrentUserResponse>;
}

export function deleteCurrentUser(): Promise<void> {
  return request<void>("/api/users/me", {
    method: "DELETE",
  });
}

export function acceptTerms(): Promise<CurrentUserResponse> {
  return request<CurrentUserResponse>("/api/users/me/accept-terms", {
    method: "POST",
  });
}

export function getProfile(): Promise<ProfileResponse> {
  return request<ProfileResponse>("/api/profile");
}

export function updateProfile(
  payload: UpdateProfileRequest,
): Promise<ProfileResponse> {
  return request<ProfileResponse>("/api/profile", {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function updateCurrentUser(
  payload: UpdateCurrentUserRequest,
): Promise<CurrentUserResponse> {
  return request<CurrentUserResponse>("/api/users/me", {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}
export type JobState =
  | "SAVED"
  | "APPLIED"
  | "REJECTED"
  | "ACCEPTED"
  | "DECLINED";

export type SavedJobResponse = {
  postingId: string;
  jobState: JobState;
  title: string | null;
  companyName: string | null;
  location: string | null;
  workMode: string | null;
  isRemote: boolean | null;
  skills: string[];
  employmentType: string | null;
  postedDate: string | null;
  source: string | null;
  category: string | null;
  freshnessClass: string | null;
  ageDays: number | null;
};

export type SavedJobsStatsResponse = {
  SAVED?: number;
  APPLIED?: number;
  REJECTED?: number;
  ACCEPTED?: number;
  DECLINED?: number;
};

export function saveJob(postingId: string): Promise<void> {
  return request<void>("/api/saved-jobs", {
    method: "POST",
    body: JSON.stringify({ postingId }),
  });
}

export function updateSavedJobStatus(
  postingId: string,
  status: JobState,
): Promise<void> {
  return request<void>(`/api/saved-jobs/${postingId}`, {
    method: "PATCH",
    body: JSON.stringify({ newState: status }),
  });
}

export function deleteSavedJob(postingId: string): Promise<void> {
  return request<void>(`/api/saved-jobs/${postingId}`, {
    method: "DELETE",
  });
}

export function getSavedJobsStats(): Promise<SavedJobsStatsResponse> {
  return request<SavedJobsStatsResponse>("/api/saved-jobs/stats");
}

export async function getSavedJobs(): Promise<SavedJobResponse[]> {
  const response = await request<PageResponse<SavedJobResponse>>(
    "/api/saved-jobs?size=100",
  );

  return response.content;
}

export type JobSearchResponse = {
  postingId: string;
  title: string;
  companyName: string;
  location: string | null;
  workMode: string | null;
  isRemote: boolean | null;
  skills: string[];
  employmentType: string | null;
  postedDate: string | null;
  source: string | null;
  category: string | null;
  freshnessClass: string | null;
  ageDays: number | null;
  savedCount?: number;
};

export type JobFiltersResponse = {
  locations: string[];
  categories: string[];
  workModes: string[];
  experienceLevels: string[];
  employmentTypes: string[];
};

export type JobDetailsResponse = JobSearchResponse & {
  description: string | null;
  experienceLevel: string | null;
  educationLevel: string | null;
  salaryMin: number | null;
  salaryMax: number | null;
  salaryCurrency: string | null;
  salaryPeriod: string | null;
  sourceUrl: string | null;
  status: string | null;
};

export type JobMatchResponse = {
  postingId: string;
  title: string;
  company: string;
  category: string | null;
  matchedSkills: string[];
  matchedCount: number;
  ofSkills: number;
  jobSkillCount: number;
  matchScore: number;
  matchPercent: number;
  label: string | null;
  score: number;
  reason: string | null;
  aiScored: boolean;
};

export type JobSearchParams = {
  q?: string;
  category?: string;
  workMode?: string;
  location?: string;
  page?: number;
  size?: number;
};

export function getJobFilters(): Promise<JobFiltersResponse> {
  return request<JobFiltersResponse>("/api/jobs/filters");
}

export function getTopMatches(): Promise<JobMatchResponse[]> {
  return request<JobMatchResponse[]>("/api/jobs/top-matches");
}
