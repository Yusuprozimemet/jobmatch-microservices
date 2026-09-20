export type JobState =
  | "SAVED"
  | "APPLIED"
  | "REJECTED"
  | "ACCEPTED"
  | "DECLINED";

export type SavedJob = {
  userId: string;
  postingId: string;
  jobState: JobState;
};
