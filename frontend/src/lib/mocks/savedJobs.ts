import type { SavedJob } from "@/lib/types";

export const mockSavedJobs: SavedJob[] = [
  {
    userId: "mock-user-1",
    postingId: "job-101",
    jobState: "SAVED",
  },
  {
    userId: "mock-user-1",
    postingId: "job-102",
    jobState: "SAVED",
  },
  {
    userId: "mock-user-1",
    postingId: "job-103",
    jobState: "APPLIED",
  },
  {
    userId: "mock-user-1",
    postingId: "job-104",
    jobState: "REJECTED",
  },
  {
    userId: "mock-user-1",
    postingId: "job-105",
    jobState: "ACCEPTED",
  },
  {
    userId: "mock-user-1",
    postingId: "job-106",
    jobState: "DECLINED",
  },
];
