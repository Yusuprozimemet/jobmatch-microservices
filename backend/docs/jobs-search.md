# Job search and discovery

The part of JobMatch a logged-out visitor sees: a list of postings, three filters, a keyword box, and
a detail page with a way out to the employer. It is also the only feature that reads the data
pipeline's output directly, so most of its complexity is really the shape of the mart showing
through.

Backend: [`jobs/`](../src/main/java/nl/hackyourfuture/project/backend/jobs).
Frontend: [`app/jobs/`](../../frontend/src/app/jobs) and
[`components/jobs/`](../../frontend/src/components/jobs). Endpoint contracts are in
[`api.md`](api.md).

---

## Table of contents

- [1. Where a job comes from](#1-where-a-job-comes-from)
- [2. Searching](#2-searching)
- [3. The location problem](#3-the-location-problem)
- [4. Freshness](#4-freshness)
- [5. The detail page](#5-the-detail-page)
- [6. How the pages are rendered](#6-how-the-pages-are-rendered)
- [7. Known gaps](#7-known-gaps)

---

# 1. Where a job comes from

Everything on these screens is read from three published tables. The backend never writes them, and
never joins across to `app` except to count saves.

| Table | Read for |
| --- | --- |
| `analytics.fct_postings` | One row per posting: title, company, work mode, salary, description, status, freshness, source URL |
| `analytics.fct_postings_cities` | The resolved city list — the location shown, the location filter, the dropdown |
| `analytics.fct_postings_skills` | The skills shown on a card and on the detail page |

**Skills exist twice in the mart, and search and matching read different copies.** `fct_postings`
carries a JSON array column; `fct_postings_skills` is a bridge table with one row per posting and
skill. Job display reads the bridge table and aggregates it back into a list with
`json_agg(DISTINCT s.skill)`; the matcher reads the JSON column and explodes it with
`jsonb_array_elements_text`. Same data, two shapes, each chosen for its query — the display wants a
list to render, the matcher wants an array to intersect.

The `MartSkills` helper parses that aggregated JSON on the way back into Java, and falls back to
splitting on commas if a posting's value is not valid JSON. It takes a `String` deliberately: if the
mart ever changes that column's type, call sites break at compile time rather than silently
returning nothing.

A posting's id is an md5 the pipeline derives from source and source job id, so it is stable across
publishes as long as the source keeps the same job id.

---

# 2. Searching

`GET /api/jobs` with four optional parameters, all treated as absent when blank.

| Parameter | Matched as |
| --- | --- |
| `q` | `ILIKE '%q%'` against **title, company name, city, or skill** |
| `category` | exact equality on `fct_postings.category` |
| `workMode` | exact equality on `fct_postings.work_mode` |
| `location` | exact city equality against `fct_postings_cities` — see [section 3](#3-the-location-problem) |

Two things about `q` are worth knowing before anyone reports them as bugs:

- **The description is not searched.** A posting that mentions Kubernetes only in its body text will
  not be found by `kubernetes` unless the pipeline also extracted it as a skill.
- **There is no relevance ranking.** Every match is equal; the order is purely recency. A search for
  `java` puts yesterday's incidental mention above last week's exact title match.

The result is ordered `posted_date DESC NULLS LAST, posting_id` and capped at **200 rows**. The
tie-breaker exists so the cut-off is stable between two identical requests rather than drifting with
whatever order Postgres felt like. The cap is silent: there is no total count and no next page, so a
search matching 900 postings looks exactly like one matching 200.

`savedCount` on each row counts distinct users who saved that posting — a popularity signal, not
"did I save this". See [`saving-tracking.md`](saving-tracking.md).

## The filter values

`GET /api/jobs/filters` returns five lists, all derived from what is actually published, so the
dropdowns can only offer values that can match something:

| List | Source |
| --- | --- |
| `locations` | distinct cities from `fct_postings_cities`, title-cased |
| `categories`, `workModes`, `experienceLevels`, `employmentTypes` | distinct non-null values from `fct_postings` |

The job filter UI uses three of them — location, category, work mode. `experienceLevels` and
`employmentTypes` are consumed by the **profile form**, which fills its dropdowns from the same
endpoint. One vocabulary, two screens.

The filters are a plain `<form action="/jobs">` with `GET`, so a filtered search is a real URL that
can be bookmarked, shared and re-rendered on the server. The keyword is carried along as a hidden
field so changing a filter does not silently drop it.

---

# 3. The location problem

This is the messiest part of the feature, and all of it is downstream of one fact: **the source's
location field is free text.** `Amsterdam`, `Amsterdam, Netherlands`, `Noord-Holland`,
`NL - Hybrid`, `Remote in Europe` are all values that appear.

The pipeline resolves what it can into `fct_postings_cities`, one row per posting and city, and the
backend uses **only that table** for anything a user can act on. Aggregating the raw `location`
column instead produced 877 near-duplicate free-text options, each matching only its own subset of
postings.

Two rules follow, and both are in `JobRepository`:

**Equality, not substring.** The value comes from the dropdown, so it is already a whole city name.
Matching `%Ede%` instead also returned Enschede, Medemblik, Nederweert — and Sweden.

**A hard-coded exclusion list.** `fct_postings_cities` carries values that are not cities: countries
(`netherlands` is second only to Amsterdam by volume), provinces (`noord-brabant`), and "remote"
written into the city field. `NON_CITY_LOCATIONS` filters them out of the dropdown, the filter, and
the location displayed on a card — all three, because a city filter is a city filter however the
query string was assembled.

The list keeps provinces that double as city names (Utrecht, Groningen) and city-states (Singapore),
and it is **derived from the data by hand**: when the pipeline starts publishing a new country name
into that column, someone has to notice and add it. The code says as much — *"the proper fix is
upstream, in the city column itself."* Treat the list as a symptom, not a solution.

What the user sees as `location` is therefore the posting's cities, `initcap`-ed and joined with
commas, and an empty string when every value was excluded — which the UI renders as *"Location not
specified"*. The saved-jobs list is the exception: it shows the mart's **raw** location text, so the
same posting can read differently in two places.

One leftover: [`JobFilters`](../../frontend/src/components/jobs/JobFilters.tsx) still splits each
option on `;` and takes the part before the first comma before showing it. The API has returned
single city names since the switch to `fct_postings_cities`, so that split does nothing today. It is
harmless but misleading — it implies the values are still composite.

---

# 4. Freshness

The product promise on the home page is *"spend less time on outdated listings"*, and this is the
machinery behind it.

The source publishes a `reality` block per posting, which the pipeline carries through to the mart:

| Mart column | What it says | Exposed by the API? |
| --- | --- | --- |
| `freshness_class` | The source's own verdict, e.g. fresh / stale | **Yes**, as `freshnessClass` |
| `age_days` | Days since it was first posted | **Yes**, as `ageDays` |
| `repost_count` | How many times this job has been posted again | No |
| `fake_freshness` | The source flagging a listing that *looks* new and is not | No |

**Only half of the signal reaches the user.** `repostCount` and `fakeFreshness` are published, sit in
`fct_postings`, and are simply not selected by `JobRepository`. Surfacing them is the cheapest
available improvement to the feature that the whole app is pitched on — a "reposted 4 times" badge
needs one column in a `SELECT` and one line in a DTO.

**Open and fresh are different questions.** A posting can have `closed_at = null` — so `status` is
`'open'` — while its freshness class is stale; the source data documentation calls this out
explicitly. The UI shows the class as a badge next to the save button and lets the reader decide,
which is the right call: filtering stale listings out would hide jobs that really are still open.

## Sorted twice, on two different keys

Worth understanding before touching either half:

1. **The backend** orders by `posted_date DESC` and cuts to 200.
2. **The page** then re-sorts what it received by `ageDays` ascending
   ([`sortJobsByFreshness`](../../frontend/src/lib/jobs.ts)), nulls last, original order as the
   tie-break so the sort is stable.

These usually agree, and they are not the same key: `posted_date` is when the posting says it was
published, `age_days` is the source's count of how old the job really is — which is exactly the gap
`fake_freshness` exists to describe. So a reposted job can carry a recent `posted_date`, survive the
200-row cut on that basis, and then be pushed down the page by its true age. The cut and the display
order are decided by two different columns.

## Two date formatters

There are two `formatPostedDate` implementations with different precedence:

| Where | Prefers |
| --- | --- |
| [`lib/jobs.ts`](../../frontend/src/lib/jobs.ts), used by the search list | `ageDays` first, then the date |
| [`lib/formatters.ts`](../../frontend/src/lib/formatters.ts), used by the detail page | the date first — recomputing the day count in the **browser's** calendar — and `ageDays` only as a fallback |

So the same posting can read "Posted 3 days ago" in the list and "Posted 5 days ago" on its own
page, because one number is the source's and the other is arithmetic on the posted date. They should
be one function.

---

# 5. The detail page

`GET /api/jobs/{postingId}` returns everything the card has plus `description`, `experienceLevel`,
`educationLevel`, the four salary fields, `sourceUrl` and `status`.

**Closed postings are served.** A saved job that closes must still open, and `status` tells the
reader what happened rather than the page 404ing on them.

The page is a server component. A 404 from the backend is turned into Next's `notFound()` —
rendering [`not-found.tsx`](../../frontend/src/app/jobs/[jobId]/not-found.tsx) — and anything else is
re-thrown to the error boundary, so "this job does not exist" and "the backend is down" do not look
the same to the user.

**Salary is assembled, not stored as a string.** `formatSalary` handles min-only, max-only, both, or
neither, and formats through `Intl.NumberFormat` with the posting's currency; an unrecognised
currency code falls back to `EUR 45000` rather than throwing.

**The apply link is validated before it is rendered.** `getSafeApplicationUrl` parses `sourceUrl` and
returns it only if it is `http:` or `https:`; anything else — a `javascript:` URL, a malformed
string — becomes *"Application link unavailable"*. This is the one place the app renders a URL that
came from an external source through the pipeline, and it is treated as untrusted. The link also
carries `rel="noreferrer noopener"`.

Below the description sits [`JobDetailsMatchSection`](../../frontend/src/components/jobs/JobDetailsMatchSection.tsx),
which shows the user's match for this posting — but only if it is in their top 25, because it works
by fetching `top-matches` and searching the result by id. See
[`matching-profile.md`](matching-profile.md).

---

# 6. How the pages are rendered

The split is deliberate: **anything a search engine or a logged-out visitor should see is rendered on
the server; anything that depends on who you are is client-side.**

| Piece | Where it runs |
| --- | --- |
| Search results, filters, job details | Server components, fetched in [`jobs-server.ts`](../../frontend/src/lib/jobs-server.ts) with `cache: "no-store"` |
| Bookmark buttons, match section | Client components, fetched with the session cookie |

`jobs-server.ts` is marked `server-only` and talks to `BACKEND_API_URL` directly, bypassing the
browser proxy — it is already inside the network. It sends no cookies, which is why nothing
user-specific can leak into a server-rendered page by accident.

The jobs page fetches filters and results **in parallel** with `Promise.all`, so the slower of the
two sets the page's latency rather than their sum.

Each route has its boundaries: [`loading.tsx`](../../frontend/src/app/jobs/loading.tsx) renders a
skeleton in the real page layout rather than a spinner, so nothing jumps when the data lands, and
[`error.tsx`](../../frontend/src/app/jobs/error.tsx) offers a `reset()` retry instead of a dead end.

Saved state for a whole page of results is fetched **once**:
[`JobResultsWithBookmarks`](../../frontend/src/components/jobs/JobResultsWithBookmarks.tsx) calls
`GET /api/saved-jobs`, builds a `postingId → state` map, and hands each button its initial state. A
list of twenty results costs one request, not twenty.

---

# 7. Known gaps

- **No pagination and no result count.** 200 rows, silently. A user cannot tell whether they are
  seeing everything, and there is no way to reach row 201.
- **Search includes closed postings.** `GET /api/jobs` does not filter on `status`, while matching
  requires `status = 'open'` — so a job you can find in search can be one that can never appear in
  your matches.
- **`repostCount` and `fakeFreshness` are published but never exposed**, which is the clearest
  available win for the app's core pitch.
- **`q` is a substring match over four columns** with no relevance ranking and no description
  search.
- **The cut and the display order use different columns** — see [above](#sorted-twice-on-two-different-keys).
- **Two date formatters** that can disagree by a day or more on the same posting.
- **`NON_CITY_LOCATIONS` is maintained by hand** and drifts silently as the source data changes.
- **The location dropdown is unbounded** — `getCityOptions` returns every distinct city in the mart,
  with no limit and no popularity ordering.
- **The location value is title-cased for display and then round-tripped as a filter value.** It
  works because the comparison is case-insensitive, but display formatting and a query key should
  not be the same string.
- **No sort control.** The user cannot choose relevance, salary or recency; the page decides.
