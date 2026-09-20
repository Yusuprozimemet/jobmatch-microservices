const DAY_IN_MS = 24 * 60 * 60 * 1000;

function getUtcCalendarDayTimestamp(date: Date): number {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
}

function parsePostedDate(value: string): Date | null {
  const date = new Date(`${value}T00:00:00`);

  if (Number.isNaN(date.getTime())) {
    return null;
  }

  return date;
}

export function formatEnumLabel(value: string | null): string | null {
  if (!value) {
    return null;
  }

  return value
    .toLowerCase()
    .replace(/[-_]+/g, " ")
    .trim()
    .replace(/\s+/g, " ")
    .replace(/^./, (character) => character.toUpperCase());
}

export function formatReadableDate(value: string): string {
  const date = parsePostedDate(value);

  if (!date) {
    return value;
  }

  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(date);
}

export function formatPostedDate(
  postedDate: string | null,
  ageDays: number | null,
): string | null {
  if (postedDate) {
    const date = parsePostedDate(postedDate);

    if (date) {
      const today = new Date();
      const daysAgo = Math.floor(
        (getUtcCalendarDayTimestamp(today) - getUtcCalendarDayTimestamp(date)) /
          DAY_IN_MS,
      );

      if (daysAgo === 0) {
        return "Posted today";
      }

      if (daysAgo === 1) {
        return "Posted 1 day ago";
      }

      if (daysAgo > 1) {
        return `Posted ${daysAgo} days ago`;
      }
    }

    return `Posted ${formatReadableDate(postedDate)}`;
  }

  if (ageDays === 0) {
    return "Posted today";
  }

  if (ageDays === 1) {
    return "Posted 1 day ago";
  }

  if (ageDays !== null) {
    return `Posted ${ageDays} days ago`;
  }

  return null;
}
