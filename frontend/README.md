# JobMatch Frontend

JobMatch Frontend is the Next.js web app for searching jobs, viewing job details, managing a profile, reviewing matches, and tracking saved jobs. It depends on the JobMatch backend API, and job listings depend on data being loaded into the backend database.

## Getting started

Use Node.js `>=24.19.0`.

```bash
npm install
cp .env.example .env.local
```

Set the backend URL in `.env.local`:

```bash
BACKEND_API_URL=http://localhost:8080
```

Start the frontend:

```bash
npm run dev
```

Open http://localhost:3000.

## Tech stack

- Next.js 16 App Router
- React 19
- TypeScript
- Global CSS
- Biome

## Project structure

- `src/app`: routes, route-level loading/error/not-found files, global styles, and pages.
- `src/components`: reusable UI grouped by feature area.
- `src/context`: authentication context.
- `src/lib`: API helpers, shared types, formatting helpers, profile skill data, and saved-job status helpers.
- `src/proxy.ts`: rewrites frontend `/api/...` requests to the configured backend.

## Main routes

- `/`: home page and search entry point.
- `/jobs`: job search with filters, results, top-match preview, and pagination.
- `/jobs/[jobId]`: job detail page with metadata, skills, save action, external apply link, and match information.
- `/matches`: authenticated page for the full top matches list.
- `/saved`: authenticated saved jobs tracker.
- `/saved/status/[status]`: authenticated saved jobs tracker filtered by status.
- `/profile`: authenticated profile and account settings page.

Additional routes cover authentication and account flows such as login, registration, Google sign-in, terms acceptance, forgot password, and password reset. Static Terms, Privacy, and About pages are also available.

## Frontend architecture

Browser-side API helpers live in `src/lib/api.ts`. Server-rendered job pages use `src/lib/jobs-server.ts`. Client components handle authentication, filters, matches, save buttons, and saved-job status updates.

### Job search

Job search state is URL-based. The implemented query parameters are `q`, `category`, `workMode`, `location`, and `page`.

`/jobs` loads jobs on the server through `getJobsServer()`. It also loads filter options from the backend. The currently exposed filters are category, work mode, and location.

Pagination is zero-based in the URL and API. Visible page labels are one-based. Pagination links preserve the active search and filter parameters.

Job result cards link to `/jobs/[jobId]` for the full detail view.

### Profile and matching

The profile stores skills, category, preferred city, work mode, experience level, employment type, and salary preference. Skills use the static vocabulary in `src/lib/profile-skills.ts`; the UI enforces 5 to 20 skills.

Not every stored profile preference currently affects matching. Backend docs state that matching uses skills and preferred city.

Top matches are loaded from `GET /api/jobs/top-matches`.

`score`:

- overall 0-100 ranking score
- drives Top Matches ordering

`matchPercent`:

- exact skill-overlap percentage, over the **job's** skill count: how much of what the posting asks for the user already has
- pairs with `matchedCount` and `jobSkillCount`, not with `ofSkills` (the user's own count, shown for context only)
- the denominator has a floor of 5, so a posting listing one or two skills cannot read 100% off a single overlap

The backend also returns matched skills, matched counts, an optional label, an optional reason, and `aiScored`. The frontend displays backend-provided match data and does not calculate ranking.

### Saved Jobs

Jobs can be saved and removed through the saved-jobs API. Search results use bookmark controls, while the job detail page uses a labelled save action.

Supported saved-job states are `SAVED`, `APPLIED`, `REJECTED`, `ACCEPTED`, and `DECLINED`.

`/saved` shows tracked jobs and status counts. `/saved/status/[status]` validates the status route segment and filters the saved jobs view.

`savedCount` is the number of distinct users who saved a posting. It is a popularity signal and is separate from the current user's own saved state.

### Authentication

Authentication uses backend session cookies. The frontend checks the current user through `/api/users/me` with credentials included.

Implemented flows include login, registration, logout, Google sign-in entry, terms acceptance, forgot password, and password reset.

Protected pages redirect to `/login` when there is no active user. Authenticated API failures with `401` generally clear user state and return the user to login.

## API usage

Use `src/lib/api.ts` for browser-side API calls and shared frontend response types. Use `src/lib/jobs-server.ts` for server-side job search, filter, and detail requests.

The frontend should follow backend contracts instead of inventing frontend-only API behavior. For endpoint details, use the backend documentation rather than duplicating the API reference here.

## Styling and responsiveness

Styling is mostly in `src/app/globals.css`, with shared CSS variables for JobMatch colors, spacing, radii, and control sizes. Layouts use the existing warm JobMatch visual language and responsive CSS for desktop, tablet, and mobile widths.

## Validation

Run from `frontend/`:

```bash
npm run format
npm run lint
npm run build
npx tsc --noEmit
npx biome check
```

Run from the repository root:

```bash
git diff --check
```

## Known limitations

- Local real job data depends on backend and data pipeline availability.
- Not every stored profile preference currently affects matching.
- Saved Jobs fetches a capped list and applies status filtering in the frontend.

## Related documentation

- Root project README: [`../README.md`](../README.md)
- Backend README: [`../backend/README.md`](../backend/README.md)
- Backend API docs: [`../backend/docs/api.md`](../backend/docs/api.md)
- Data README: [`../data/README.md`](../data/README.md)
