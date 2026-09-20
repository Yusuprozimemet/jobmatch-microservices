import type { JobState } from "@/lib/api";

export const SAVED_JOB_STATUS_OPTIONS: Array<{
  value: JobState;
  label: string;
}> = [
  { value: "SAVED", label: "Not Applied Yet" },
  { value: "APPLIED", label: "Applied" },
  { value: "REJECTED", label: "Rejected" },
  { value: "ACCEPTED", label: "Accepted" },
  { value: "DECLINED", label: "Declined" },
];

export function getSavedJobStatusLabel(status: JobState) {
  return (
    SAVED_JOB_STATUS_OPTIONS.find((option) => option.value === status)?.label ??
    status
  );
}

export function isSavedJobStatus(status: string): status is JobState {
  return SAVED_JOB_STATUS_OPTIONS.some((option) => option.value === status);
}
