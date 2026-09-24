# Personal data and privacy

What JobMatch stores about a person, where it lives, who else sees it, and what a user can do about
it. The GDPR machinery — consent before storage, export, erasure — is implemented; this document is
the map, and it is deliberately honest about the parts that are student-project-grade rather than
production-grade.

Related: [`auth.md`](auth.md) for how accounts work, [`api.md`](api.md) for the endpoints,
[`matching-profile.md`](matching-profile.md) for what the model is sent.

---

## Table of contents

- [1. Everything that is stored](#1-everything-that-is-stored)
- [2. What is not stored](#2-what-is-not-stored)
- [3. Consent comes first](#3-consent-comes-first)
- [4. Access: the data export](#4-access-the-data-export)
- [5. Erasure: deleting an account](#5-erasure-deleting-an-account)
- [6. Who else sees any of it](#6-who-else-sees-any-of-it)
- [7. Retention](#7-retention)
- [8. Personal data in the logs](#8-personal-data-in-the-logs)
- [9. The notices themselves](#9-the-notices-themselves)
- [10. What a real deployment would still need](#10-what-a-real-deployment-would-still-need)

---

# 1. Everything that is stored

All of it is in the module schemas (`identity`, `applications`, `matching`). The `analytics` schema holds job postings and nothing about users.

| Table | Fields | Why it exists |
| --- | --- | --- |
| `users` | `email`, `name`, `created_at`, `terms_accepted_at`, `oauth_provider`, `oauth_provider_id` | The account. Email is the login identity |
| `user_credentials` | `password_hash`, `updated_at` | BCrypt hash only. No row at all for Google-only accounts |
| `user_profiles` | `skills[]`, `category`, `preferred_city`, `work_mode`, `experience_level`, `employment_type`, `salary` | What the user is looking for, and what matching runs on |
| `password_reset_tokens` | `token`, `expiry_date`, `user_id` | A 15-minute single-use link |
| `refresh_tokens` | `token_hash`, `created_at`, `expires_at`, `revoked_at`, `user_id` | What keeps a browser signed in for 30 days. Only the SHA-256 of the token is stored |
| `pending_google_links` | `user_id`, `provider_id`, `code_hash`, `created_at`, `expires_at`, `claimed_at` | A Google identity whose email belongs to a password account, waiting up to 10 minutes for that account's password login. Only the SHA-256 of the claim code is stored |
| `saved_jobs` | `posting_id`, `job_state` | Which jobs the user kept, and how far each application got |
| `job_match_scores` | `skills_hash`, `posting_id`, `score`, `reason` | Cached model verdicts — **keyed on a hash of a skill set, not on a user** |

Two of those deserve a closer look.

**`user_profiles.salary` is a stated expectation, not an income.** It is what the user says they are
aiming for. Still sensitive enough to belong in the export and to go with the account on deletion,
which it does.

**`job_match_scores` has no user column and no foreign key.** The key is
`sha-256(sorted, lowercased skills) + posting_id + scorer_version`. Two users with identical skills
share a row; deleting an account leaves nothing behind here that points at anyone. That was the
design intent, and it is the reason erasure is a clean cascade with no leftovers to chase.

A one-line summary for the notice a real deployment would need: *identity (email, name), how you
signed in, what you are looking for (skills and preferences), and what you saved.*

---

# 2. What is not stored

Worth stating, because it is a short list and it makes the shape of the app clear:

- **No CV, no documents, no uploads.** Skills are picked from a list; there is no file storage
  anywhere in the stack.
- **No plaintext password, ever** — BCrypt on write, and the hash is never returned by any endpoint.
- **No analytics, no tracking pixels, no third-party scripts.** The frontend source contains no
  external URLs at all beyond the team's own GitHub profile links on the About page, so a visitor's
  browser talks to one origin and nothing else.
- **Two cookies**, `access_token` and `refresh_token`, strictly functional: they are the login.
  (Google sign-in also sets `google_auth_request` for the five minutes between leaving for Google
  and coming back, and `pending_google_link` when the email needs a password first.) Nothing is stored in
  `localStorage`.
- **No IP addresses, user agents or request logs of our own.** Whatever the hosting layer keeps is
  outside this codebase.
- **No search history.** Filters live in the URL and are never written down.
- **No email address in the `analytics` schema**, and no user data crosses into the data pipeline in
  any direction.

---

# 3. Consent comes first

The rule the schema enforces: **no personal data is stored until the person has agreed to the terms
and privacy notice.**

| Path | How the agreement is recorded |
| --- | --- |
| Registration | `acceptedTerms` must be `true` — `@NotNull` *and* `@AssertTrue`, so a missing field and an explicit `false` are both a 400 — and `terms_accepted_at` is stamped in the **same transaction** as the account and the password hash |
| Google sign-in | Google never shows our checkbox, so a first sign-in redirects to `/accept-terms` and `POST /api/users/me/accept-terms` records it |

The checkbox is enforced in the backend, not in the browser: calling the API directly cannot skip it.

The timestamp comes from the **database clock** (`now()`), and `acceptTerms` updates
`WHERE terms_accepted_at IS NULL`, so it is written once and never moves. `PUT /api/users/me`
carries the column through untouched. An agreement can therefore be neither erased nor forged by an
account edit, and a repeat call keeps the original moment.

A Google user is logged in while sitting on `/accept-terms` — the login exists, the agreement does
not yet. What they cannot do is save a profile, which is where the personal data actually starts.

---

# 4. Access: the data export

GDPR Art. 15 — the right to get a copy. On the profile page, *Your data export* downloads
`jobmatch-my-data.json`.

**It is assembled in the browser**, from three authenticated calls made in parallel:

| Call | Section |
| --- | --- |
| `GET /api/users/me` | `user` — every column held about the account except the password hash |
| `GET /api/profile` | `profile` — preferences and skills, an empty profile rather than a 404 if none was ever saved |
| `GET /api/saved-jobs` | `savedJobs` — saved postings with their tracking state |

Plus an `exportedAt` timestamp. If any call answers 401 the user is sent to the login page rather
than handed a partial file.

**Between them those three cover every table that holds anything personal**, which is why `/me`
returns fields the UI never displays — `createdAt`, `oauthProvider`, `oauthProviderId`,
`passwordUpdatedAt` all exist for this export. `password_reset_tokens`, `refresh_tokens` and
`pending_google_links` are excluded deliberately: a live token is a credential, and putting it in a
downloadable file would be worse than omitting it.
`job_match_scores` is excluded because it is not personal data — no row in it identifies anyone.

Two real weaknesses:

- **Nothing detects a new table.** The export is a hand-written object literal in
  [`profile/page.tsx`](../../frontend/src/app/profile/page.tsx). Whoever adds a table that holds
  personal data has to remember to extend it, and no test will fail if they do not. Moving the
  export behind one backend endpoint — where it could be assembled from the schema, or at least
  reviewed in one place — is on the roadmap for exactly this reason.
- **A copy of the data is not the whole right.** Art. 15 also requires telling the person the
  purposes, the retention periods and who the recipients are. That belongs in the privacy notice,
  and [section 9](#9-the-notices-themselves) is honest about what the current one says.

---

# 5. Erasure: deleting an account

GDPR Art. 17. `DELETE /api/users/me`, from the profile page behind a confirmation step.

The account is resolved from the access token, so a caller can only ever delete their own. One `DELETE`
on `users` removes everything, because every table that references it cascades:

```
users ─┬─ user_credentials        ON DELETE CASCADE
       ├─ user_profiles           ON DELETE CASCADE
       ├─ saved_jobs              ON DELETE CASCADE
       ├─ password_reset_tokens   ON DELETE CASCADE
       ├─ refresh_tokens          ON DELETE CASCADE
       └─ pending_google_links    ON DELETE CASCADE
```

Both token cookies are deleted in the same request — otherwise the browser would keep sending a
token for a row that is gone and `/me` would answer 404 instead of 401. An access token copied
elsewhere still verifies for up to 15 minutes, and finds no user.

**What survives, and whether it matters:**

| Survives | Personal? |
| --- | --- |
| `job_match_scores` rows the user's skill set produced | No — keyed on a hash of skills, shared with anyone who has the same set. Unreachable after 24h, and deleted by the purge within an hour of that |
| Application log lines naming the email address | **Yes** — see [section 8](#8-personal-data-in-the-logs) |
| Emails already delivered to the user's inbox | Out of our hands by definition |

Deletion is immediate and unrecoverable. There is no soft delete, no grace period, and no backup
restore path in this project.

---

# 6. Who else sees any of it

Three third parties are involved, and the honest summary is that **the privacy notice names none of
them**.

| Party | What reaches them | When |
| --- | --- | --- |
| **Google** (OAuth) | We receive their email, name and subject id; they learn that this app was signed into | Only if the user chooses Google sign-in |
| **Brevo** (SMTP) | The email address, and the reset link | Only on a password-reset request |
| **The LLM provider** (Gemini by default) | The user's **skill list**, plus job titles and job skills for up to 40 postings | Every uncached matches request |

What does **not** go to the model is the important half: no name, no email, no user id, no city, no
salary, no saved jobs. The prompt is a list of skills and a list of jobs, and postings are identified
by a truncated hash. A provider receiving that traffic sees an anonymous skill set — which is also
why the score cache can be keyed on the skill set rather than on the person.

The job source (FreeHire) is **inbound only**: postings come in through the pipeline, and nothing
about a user ever goes back out.

Following an *Apply externally* link hands the user to the employer's site, which is the one moment
the app deliberately gives up control — the terms page says so, and the link carries
`rel="noreferrer noopener"` so at least the referrer is not passed along.

---

# 7. Retention

| Data | Kept |
| --- | --- |
| Account, profile, saved jobs | Until the user deletes the account. **There is no inactivity policy** |
| `job_match_scores` | `LLM_SCORE_RETENTION_DAYS`, default and minimum 1 day. Enforced on the read, so nothing older is ever served; an hourly purge reclaims the space within an hour of a row expiring |
| Password reset tokens | 15 minutes of validity. Deleted on use, and when a newer link is requested |
| Refresh tokens | 30 days, or until logout, a password change or reset. A spent or expired row stays in `refresh_tokens` until the account is deleted; nothing sweeps it yet |
| Pending Google links | 10 minutes of validity. Deleted when the account parks a newer one or is deleted; a claimed or expired row otherwise stays, as refresh tokens do |

One gap: **an expired reset token that is never used and never superseded stays in the table
indefinitely.** Nothing sweeps `password_reset_tokens` on a schedule the way `job_match_scores` is
swept; a row is only removed when that user resets, requests another link, or deletes their account.
The token is useless after 15 minutes, but the row still ties a user id to a moment in time. A
scheduled delete of `expiry_date < now()` would close it — the `JobMatchScoreCleanup` component is
the pattern to copy.

---

# 8. Personal data in the logs

Five log statements write an email address at `INFO` level:

| Where | Line |
| --- | --- |
| `AuthenticationService` | concurrent registration race; password updated |
| `EmailService` | reset email sent; reset email failed |
| `OAuth2LoginSuccessHandler` | Google sign-in needs the password; still needs the terms; concurrent sign-up |

That is real personal data in a place the export does not cover and the deletion cascade cannot
reach. It is defensible for a student project — the logs are short-lived container output — and it
would not be defensible in production, where log retention is usually longer than anyone thinks.

The cheap fix is to log the user id instead, which parts of the same code already do
(`"Skipping password reset email for Google-only user ID: {}"`,
`"Created new account {} from Google sign-in"`). The pattern to follow is already there; it just is
not applied consistently.

Nothing logs a password, a token, or a `reason` from the model.

---

# 9. The notices themselves

Two static pages in the frontend — [`/terms`](../../frontend/src/app/terms/page.tsx) and
[`/privacy`](../../frontend/src/app/privacy/page.tsx) — linked from the registration checkbox and the
footer. They are served by Next, not by the API: the text changes with a deploy, not with a request,
so an endpoint would buy nothing.

What the privacy notice does say: which categories of information the app uses, that profile data
drives matching, that some features need an account, that listings come from external sources with
their own practices, and — plainly — that this is a student project and nobody should assume
production-grade compliance guarantees.

**What it does not say**, and would have to before this was anything but a student project:

- that Google, Brevo and an LLM provider receive data, and which data;
- that the user's skills are sent to a language model at all;
- any retention period;
- that the export and deletion rights exist, or where the buttons are — the app *implements* both
  and the notice mentions neither;
- who the controller is, or how to contact them about data (the About page has GitHub profiles, which
  is not the same thing).

The terms page covers the product side properly: what the app is for, that listings are external,
that applications happen elsewhere, that match information is decision support and guarantees
nothing, and that the project is a student build.

---

# 10. What a real deployment would still need

Not a to-do list for this project — a list of what separates it from something that could take real
users' data.

- **A privacy notice naming the processors**, the purposes and the retention periods, with the
  export and deletion rights spelled out.
- **A backend export endpoint**, so the copy is assembled where the schema is, not in a component.
- **Emails out of the logs.**
- **A sweep for expired reset tokens.**
- **A data processing agreement with each processor**, which is a paperwork exercise, not a code one
  — and the reason the notice cannot honestly name them yet.
- **Rate limiting on the auth endpoints**, because credential stuffing against a user table is a
  breach waiting to happen, and nothing currently answers 429.
- **A retention or inactivity policy.** Right now an abandoned account and its profile live forever.
