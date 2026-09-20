export function formatSavedCount(
  savedCount: number | undefined,
): string | null {
  if (
    typeof savedCount !== "number" ||
    !Number.isFinite(savedCount) ||
    savedCount <= 0
  ) {
    return null;
  }

  if (savedCount === 1) {
    return "1 person saved this job";
  }

  return `${savedCount} people saved this job`;
}
