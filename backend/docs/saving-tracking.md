# Saving and tracking jobs

The feature that turns a job board into a job *tracker*: keep a posting, then move it through the
stages of an application. It is also the smallest feature in the backend — one table with three
columns — and most of what is worth writing down is about what that smallness costs.

Code: [`savedjobs/`](../src/main/java/nl/hackyourfuture/project/backend/savedjobs) and, on the
frontend, [`SavedJobsContent`](../../frontend/src/components/jobs/SavedJobsContent.tsx) plus the two
bookmark buttons. Endpoint contracts are in [`api.md`](api.md).

---

## Table of contents

- [1. The whole data model](#1-the-whole-data-model)
- [2. The five states](#2-the-five-states)
- [3. Saving](#3-saving)
- [4. Listing, and the cross-schema join](#4-listing-and-the-cross-schema-join)
- [5. Moving between states](#5-moving-between-states)
- [6. Counting](#6-counting)
- [7. How the frontend uses it](#7-how-the-frontend-uses-it)
- [8. savedCount is a different number](#8-savedcount-is-a-different-number)
- [9. Known gaps](#9-known-gaps)

---

# 1. The whole data model

```sql
CREATE TABLE saved_jobs (
    user_id    UUID NOT NULL,
    posting_id TEXT NOT NULL,
    job_state  job_state NOT NULL DEFAULT 'SAVED',
    PRIMARY KEY (user_id, posting_id),
    CONSTRAINT fk_saved_jobs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

That is all of it. Three columns, a composite primary key that makes "saved twice" impossible, and a
cascade so deleting an account takes the list with it.

**Only an id is stored, never a copy of the posting.** That is the single decision this feature
rests on, and it buys three things:

- Saving works against an empty mart. It was the one job-related feature that could be built and
  demonstrated before the data pipeline published anything.
- A saved job always shows the *current* posting — a corrected salary or a closed status appears the
  next time the list is opened, rather than being frozen at the moment of saving.
- Nothing needs to be kept in sync, because there is nothing duplicated to drift.

What it costs is [section 4](#4-listing-and-the-cross-schema-join).

**There are no timestamps.** No `saved_at`, no `updated_at`. The table cannot answer "when did I save
this", "when did I apply", or "show me the oldest application still open" — and the list has no
natural order because of it. See [section 9](#9-known-gaps); this is the first column to add.

---

# 2. The five states

A Postgres enum, `job_state`, created in `V1`:

| Value | Label in the UI | Means |
| --- | --- | --- |
| `SAVED` | *Not Applied Yet* | The default. Kept for later, nothing sent |
| `APPLIED` | *Applied* | An application is out |
| `REJECTED` | *Rejected* | They said no |
| `ACCEPTED` | *Accepted* | They said yes |
| `DECLINED` | *Declined* | The user said no |

`REJECTED` and `DECLINED` are deliberately separate: who ended it is the whole difference, and a
tracker that collapses them cannot tell you anything useful about your own search.

The labels live in [`saved-job-status.ts`](../../frontend/src/lib/saved-job-status.ts), not in the
backend. The API speaks the enum values; the UI decides that `SAVED` reads better as
"Not Applied Yet".

**The enum constrains values, not transitions.** Any state can move to any other — `ACCEPTED` back to
`SAVED`, `REJECTED` to `APPLIED` — and nothing in the backend adds a workflow on top. That is a
choice, not an omission: a job search is not a state machine, people correct mistakes, and a rejected
application really can be reopened. If a flow is ever wanted, it has to be enforced in
`SavedJobService`, because the database will not do it.

---

# 3. Saving

`POST /api/saved-jobs` with `{ "postingId": "..." }`. The user comes from the session; the body
carries no user id.

Two checks, in this order:

1. `isJobSaved` → **409** if the user already has this posting. A duplicate is a conflict, not an
   idempotent no-op, because the frontend uses the distinction: a 409 tells it the button was stale
   and it re-reads the real state rather than assuming.
2. Insert with `job_state = 'SAVED'`.

**The posting id is not validated against the mart.** An id that is not published yet, or not any
more, is still the user's to keep — and checking would put a cross-schema read in the write path for
no benefit. Anything that reaches this endpoint came from a link the user clicked, and the id is a
32-character md5 the pipeline derives from source and job id.

The check-then-insert pair is not transactional, so two simultaneous saves of the same posting race.
The composite primary key catches it and `GlobalExceptionHandler` turns the resulting
`DuplicateKeyException` into a 409 — but that handler was written for registration, so the body says
*"An account with this email address already exists. Try logging in instead."* Right status, wrong
sentence. It takes one user double-clicking to see it, and the button is disabled while the request
is in flight, which is why nobody has.

---

# 4. Listing, and the cross-schema join

`GET /api/saved-jobs` returns the saved rows joined to their postings:

```sql
FROM saved_jobs sj
LEFT JOIN analytics.fct_postings p ON sj.posting_id = p.posting_id
WHERE sj.user_id = ?
```

**The `LEFT JOIN` is the point.** There is no foreign key across the schema boundary and there cannot
be one — the pipeline replaces `analytics.fct_postings` wholesale on every publish. So a posting the
next run drops leaves a saved row pointing at nothing:

| | `LEFT JOIN` (what we do) | `INNER JOIN` |
| --- | --- | --- |
| Orphaned row | Comes back with `postingId` and `jobState` set and every job field null | **Silently disappears from the user's own list** |

An inner join would delete rows from a list the user curated, without telling them, whenever the
upstream data moved. That is unacceptable in a tracker, so the null case is rendered instead:
`SavedJobsContent` falls back to *"Job title unavailable"* and *"Company unavailable"*, and the row
keeps its state and its remove button.

Two smaller things about this query:

- **`location` here is the mart's raw location text** (`Amsterdam, Netherlands`, `Noord-Holland`),
  not the resolved, title-cased city list that `/api/jobs` assembles from `fct_postings_cities`. The
  same posting therefore reads slightly differently in search results and in the saved list.
- **There is no `ORDER BY`.** Row order is whatever Postgres returns, which is stable enough in
  practice but is not a guarantee, and there is no column that *could* be ordered on meaningfully
  anyway — see the missing timestamps above.

---

# 5. Moving between states

`PATCH /api/saved-jobs/{postingId}` with `{ "newState": "APPLIED" }`.

The update is scoped to `WHERE user_id = ? AND posting_id = ?` and reports rows affected, so a
posting the caller has not saved is a **404** rather than a silent success. Same for
`DELETE /api/saved-jobs/{postingId}`, which answers **204** or **404** on the same rule.

An unknown state value never reaches the service: `UpdateJobStateRequest.newState` is the `JobState`
enum, so Jackson rejects anything else during binding and the caller gets a 400.

Neither endpoint returns the updated row. The frontend already knows what it asked for and patches
its own state optimistically, then re-reads the stats.

---

# 6. Counting

`GET /api/saved-jobs/stats` is a `GROUP BY job_state` over the user's rows.

**A state with no rows is absent from the map, not zero.** A user with three applications out and
nothing else gets:

```json
{ "APPLIED": 3 }
```

Every caller has to default missing keys to `0`. The frontend does this twice over — an `EMPTY_STATS`
constant seeded with all five keys, and `stats[option.value] ?? 0` at every read site — which is
belt and braces, but the API is the one making the caller do the work. Zero-filling server-side would
be a kinder contract and is a one-line change in `SavedJobRepository.getJobStats`.

The endpoint reads only `saved_jobs`, so it is the one part of this feature that is completely
independent of the pipeline.

---

# 7. How the frontend uses it

Three surfaces, one API.

| Surface | Component | What it does |
| --- | --- | --- |
| Search results | [`JobResultsWithBookmarks`](../../frontend/src/components/jobs/JobResultsWithBookmarks.tsx) → `SavedJobBookmarkButton` | One bookmark icon per result |
| Job detail | [`SaveJobButton`](../../frontend/src/components/jobs/SaveJobButton.tsx) | A labelled save / remove control |
| The tracker | [`SavedJobsContent`](../../frontend/src/components/jobs/SavedJobsContent.tsx) + `JobStatusSummary` | The list, the status dropdown per row, and the counts |

**Saved state is discovered by fetching the whole list.** There is no "is this one saved" endpoint,
so a button that needs to know calls `getSavedJobs()` and searches it by id. In the results list that
happens once — `JobResultsWithBookmarks` fetches the list, builds a `postingId → state` map, and
passes each button its `initialState`, so twenty results cost one request. A button rendered without
that prop falls back to fetching the list itself, which is what `SaveJobButton` does on the detail
page. It is one request for one answer, and it is the reason `isSaved` keeps coming up as a wanted
field on the job responses.

**The 409 is used as a signal, not an error.** If a save comes back 409 the button re-reads the list
to find out what state the posting is actually in, and falls back to `"UNKNOWN"` if even that fails.
So a stale button corrects itself instead of showing a failure the user cannot act on.

**A tracked job cannot be un-saved from the job page.** Once a posting is past `SAVED` — applied,
rejected, accepted, declined — `SaveJobButton` shows the state as a disabled label with the note
*"The saved status is not removable from this page."* Removing an application you have already sent
is a decision that belongs on the tracker, where the consequence is visible, not on a job page where
the button is one click from "save". The bookmark icon in search results does **not** apply this
rule, and will remove a tracked job. That inconsistency is a bug, not a design.

**Status pages filter in the browser.** `/saved/status/[status]` validates the segment against the
five known values (404 otherwise) and then renders the same `SavedJobsContent` with a `status` prop;
the component fetches the full list and filters it in memory. The API has no status parameter. At
tracker-sized lists that is the right trade — one cached list, instant switching between tabs — and
it stops being right the day someone has hundreds of saved jobs.

The empty states are distinguished on purpose: "your list is empty" when nothing is saved at all,
"no jobs in this status yet" when the filter is what emptied it.

---

# 8. `savedCount` is a different number

`JobSearchResponse` and `JobDetailResponse` both carry `savedCount`:

```sql
(SELECT COUNT(DISTINCT user_id) FROM saved_jobs WHERE posting_id = f.posting_id)
```

That is **how many people saved this posting**, across every account — a popularity signal on a
public endpoint, computed as a correlated subquery per row.

It is not "did I save this", it is not scoped to the caller, and it is available to callers with no
session at all. Two things follow: the frontend must not use it to decide bookmark state (it does
not), and it is an aggregate over other users' saved lists, so it should stay an aggregate — never
break it down, never expose who.

---

# 9. Known gaps

- **No timestamps.** `saved_jobs` cannot say when a job was saved or when it moved state, so the
  tracker cannot sort by recency, show "applied 3 weeks ago", or nudge anyone about a stale
  application. Adding `created_at` and `updated_at` is the highest-value change available here, and
  it is one migration.
- **No `ORDER BY` on the list.** Follows from the above: there is nothing meaningful to order on
  yet.
- **No status filter, and no pagination, on the API.** The client fetches everything and filters.
- **No "is this saved" endpoint.** Every bookmark button that is not handed its state fetches the
  whole list to answer one boolean. An `isSaved` / `savedStatus` field on the job responses would
  remove the round trip entirely.
- **`/stats` returns a sparse map** and makes every caller zero-fill it.
- **The bookmark icon ignores the tracked-state rule** the detail page enforces, so a job that is
  `APPLIED` can be removed with one click from search results.
- **Check-then-insert is not atomic**, and the 409 a genuine race produces carries the registration
  handler's message about email addresses.
- **`updateSavedJobStatus` and `deleteSavedJob` interpolate the posting id into the URL** without
  `encodeURIComponent`, unlike every link in the UI. Harmless while ids are md5 hex; brittle if the
  pipeline ever changes what a `posting_id` looks like.
- **[`frontend/src/lib/mocks/savedJobs.ts`](../../frontend/src/lib/mocks/savedJobs.ts) is dead
  code** — nothing imports it. It dates from before the endpoints existed and should go.
