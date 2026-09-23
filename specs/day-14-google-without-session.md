# Day 14 — Google sign-in without a session

**Phase:** 2 · **Depends on:** Day 13 · **Expected PRs:** 3

## Goal
The Google flow, including the account-linking case, works with no server-side session, and no
code in the backend reads or writes one.

## In scope
- **The pending link moves off the session**, into `identity.pending_google_links`:
  `db/identity/V2__pending_google_links.sql`, identity's second migration, with unqualified names.
  Columns: `id`, `user_id` → `users(id)` **`ON DELETE CASCADE`** (the account the email belongs
  to), `provider_id` (the Google subject), `code_hash` (unique), `created_at`, `expires_at` (10
  minutes), `claimed_at`. The code is 32 random bytes and only its SHA-256 is stored, as with
  Day 12's refresh tokens. The migration revokes every privilege other roles hold on the table,
  found in the catalogue, as `V1__refresh_tokens.sql` does: it holds emails' Google identities.
- **The code travels in a cookie, not the URL.** The success handler's taken branch saves a row
  and sets `pending_google_link` (`HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=600`) on the
  302; the redirect stays `/login?error=google_link_required`, which `AuthGoogleSignInIT` compares
  exactly. The password login that follows claims it in one statement (`UPDATE … SET claimed_at =
  now() WHERE code_hash = … AND user_id = <the account that just proved its password> AND
  claimed_at IS NULL AND expires_at > now() RETURNING provider_id`) and deletes the cookie when it
  claims. A login for another account claims nothing and leaves the link, as today. Neither
  `contract/` nor the frontend changes: the browser sends the cookie to `/api/auth/login` by itself.
- **Saving a link deletes the account's earlier ones,** claimed, expired or not, so one live link
  exists per account and nothing piles up. Deleting the account deletes its links (the cascade).
- **The authorization request moves off the session.** Spring's `oauth2Login` keeps it — `state`,
  the `nonce`, the redirect URI and the PKCE `code_verifier` (PKCE is on: the start redirect
  carries `code_challenge_method=S256`) — in `HttpSessionOAuth2AuthorizationRequestRepository`.
  Replace it with a cookie-backed `AuthorizationRequestRepository`:
  - cookie `google_auth_request`: `HttpOnly; SameSite=Lax; Path=/api/login/oauth2/code;
    Max-Age=300`. `Lax`, not `Strict`: Google's redirect back is a cross-site navigation, and a
    `Strict` cookie would not be sent on it;
  - its value is a JWS signed with Day 12's RSA key (`SigningKey`), over JSON of the fields needed
    to rebuild the request, with an `exp` 5 minutes out. Not Java serialization, and nothing is
    read from it before the signature and `exp` are verified;
  - the whole `Set-Cookie` header stays under 4096 bytes, the size browsers are required to keep;
  - the callback deletes the cookie once it has read it. A missing, tampered or expired cookie
    fails the sign-in the way a bad `state` does today: 302 to `/login?error=oauth`, not a 500.
- **`Secure` on the two new cookies follows `SESSION_COOKIE_SECURE`** (`server.servlet.session.
  cookie.secure`), which today protects the same state through `JSESSIONID`. Decided by the
  maintainer. The token cookies stay as Day 13 left them.
- **The failure handler creates no session:** `SimpleUrlAuthenticationFailureHandler` with
  `setAllowSessionCreation(false)`. By default it creates one to hold the exception.
- **`AuthenticationService.establishSession` is deleted.** It has had no caller since Day 13;
  Day 16 planned to delete it and no longer does. So is `PendingGoogleLink`'s session code.
- The docs these changes make false: `backend/docs/auth.md` (the pending link, `SESSION_COOKIE_SECURE`,
  the Google session in Known limitations), `backend/docs/configuration.md` (the same three), the
  Javadoc of `tokens/NoSessionIT` and the comment above `sessionManagement` in `SecurityConfig`.
- All eight `AuthGoogleSignInIT` tests keep their exact behaviour: already linked, new email, email
  taken and the login that claims it, a login for another account, an unverified email, a forged
  `state`.

## Out of scope
- Adding providers. Google only.
- `Secure` on `access_token` and `refresh_token` — Day 13's decision stands.
- Renaming `SESSION_COOKIE_SECURE`, which after this day governs no session cookie. The name is in
  every deployment's environment; changing it is its own change.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | `hold` test: a login for another account does not use up the parked link |
| B | | `V2__pending_google_links.sql`, the table's repository, claim and expiry, the `pending_google_link` cookie, the grants test; `PendingGoogleLink` off the session |
| A | | The cookie `AuthorizationRequestRepository`, the failure handler, deleting `establishSession`, the no-`JSESSIONID` test over every Google path, the grep, the docs |

**Order: 0, B, A.** A's no-`JSESSIONID` test can only go green once B has taken the pending link
off the session: with A alone, the taken branch still sets `JSESSIONID`. B is the one at risk under
the 400-line gate; if it splits, the estimate becomes 4.

## Acceptance criteria
- [ ] **hold** — All eight `AuthGoogleSignInIT` tests, and all 190 tests in the 23 `contract/`
      classes, pass, and nothing in `contract/` changes: the last command in **Verify** prints
      nothing. Broken on purpose in the spec-auditor's scratch copy of bcb860e:
      `PendingGoogleLink.claim` forced empty turned `theNextPasswordLoginClaimsTheParkedIdentity`
      red (`expected: "http://localhost:3000/" but was: "…/login?error=google_link_required"`), and
      the terms check replaced with `if (false)` turned `createsAnAccountWhenTheEmailIsFree` and
      `sendsALinkedAccountWithoutTermsToTheTermsScreen` red (`but was: "http://localhost:3000/"`).
- [ ] **hold** — A login for another account does not use up the parked link: park a link for
      account X, log in as Y (200), log in as X (200), and a Google sign-in afterwards lands on `/`
      (`tokens/GoogleLinkClaimIT`, Track 0). True today: `PendingGoogleLink.claim` leaves the
      attributes on an email mismatch. Broken on purpose in Track 0's PR.
- [ ] **new** — A pending link claims once: the repository returns the Google subject for the
      first claim and nothing for the second, and a second password login answers 200 and links
      nothing (`tokens/PendingGoogleLinksIT`). Red today: no table, no repository.
- [ ] **new** — A link older than 10 minutes claims nothing: with the row aged past `expires_at`
      through JDBC, the password login answers 200 and a Google sign-in afterwards still lands on
      `/login?error=google_link_required` (`tokens/PendingGoogleLinksIT`). Red today: no table.
- [ ] **new** — Saving a link deletes the account's earlier ones, and deleting the account deletes
      its links (`tokens/PendingGoogleLinksIT`). Red today: no table. Red again with the cascade
      taken off the foreign key.
- [ ] **new** — As `applications_user`, `matching_user` and `jobs_user`, `SELECT` on
      `identity.pending_google_links` is refused (`tokens/PendingGoogleLinkGrantsIT`, or a case
      added to `RefreshTokenGrantsIT`). Red today: no table. Red again with the migration's revoke
      removed.
- [ ] **new** — The taken branch's 302 sets `pending_google_link` with `HttpOnly; SameSite=Lax;
      Path=/api/auth; Max-Age=600`, and the login that claims it deletes it; with
      `SESSION_COOKIE_SECURE=true` it is `Secure` (`tokens/PendingGoogleLinksIT`). Asserted on the
      `Set-Cookie` headers. Red today: the 302 sets `JSESSIONID`.
- [ ] **new** — The start response sets `google_auth_request` with `HttpOnly; SameSite=Lax;
      Path=/api/login/oauth2/code; Max-Age=300`, its `Set-Cookie` header under 4096 bytes, and
      `Secure` when `SESSION_COOKIE_SECURE=true`; the callback's response deletes it; a callback
      with the cookie tampered (one byte of its signature changed) gets 302 to `/login?error=oauth`,
      not 500 (`tokens/AuthRequestCookieIT`). Red today: the start response sets `JSESSIONID`.
- [ ] **new** — No `Set-Cookie` names `JSESSIONID` on any step of five Google paths: already
      linked, new email, email taken plus the password login after it, unverified email, forged
      `state` (`tokens/GoogleNoSessionIT`, driving both steps itself, since `support/GoogleSignIn`
      returns only the callback). Red today: the spec-auditor saw `JSESSIONID` on every start
      response and on the linked, new, taken and forged-state callbacks.
- [ ] **new** — No backend code touches a session: `grep -rnE "HttpSession|getSession|changeSessionId"
      --include=*.java */src/main`, run from `backend/`, prints nothing. Red today: 9 lines in
      `AuthenticationService`, `OAuth2LoginSuccessHandler` and `PendingGoogleLink`.

## Verify
```bash
# The whole suite. Read the reports, not the exit code.
cd backend
rm -rf */target/surefire-reports
./mvnw clean verify
./mvnw -B checkstyle:check

# No session anywhere in main code: prints nothing.
grep -rnE "HttpSession|getSession|changeSessionId" --include=*.java */src/main

# The hold: prints nothing. BASE = bcb860e, the main commit Day 14 started from.
cd ..
git diff --stat "$BASE" HEAD -- backend/app/src/test/java/nl/hackyourfuture/project/backend/contract
```

## Notes
- **Every Google path spans two requests** (the start and the callback), and each put a
  `JSESSIONID` on the browser for it; the email-taken branch spans three, with the password login.
  That third request is the one the pending-link cookie exists for.
- **The harness cannot check what breaks a real sign-in.** `ApiClient` ignores `Path`, `SameSite`
  and a cookie's size, so a wrong path or `SameSite=Strict` left `AuthGoogleSignInIT` green in the
  spec-auditor's scratch copy while it would break every sign-in in a browser. The header
  criteria are what check them; a Google sign-in in a real browser after Track A is advisable.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec.**
  - **The grep criterion alone would have passed with the session still there.** The OAuth2
    authorization request lives in the session through a framework class, so our code can be
    free of `HttpSession` while every Google sign-in still creates one. The behavioural
    criterion on `Set-Cookie` is what proves it, and the repository swap is now in scope.
  - `docs/hiearchy-backend.md` does not exist; Day 02 found this. The terms step is
    `backend/docs/auth.md` §7.
  - The verify command had Day 08's defect: `-Dtest` with no `-pl` runs nothing.
- **Spec corrected on Day 13, before that day's work.** `OAuth2LoginSuccessHandler` issuing the
  token cookies moved to Day 13: under Day 13's `STATELESS`, Spring stops reading the security
  context `establishSession` writes into the session, and three `AuthGoogleSignInIT` tests got
  401 after sign-in in the spec-auditor's scratch build. What stays here is taking the
  authorization request and the pending link off the session, and the terms redirect.
- **Spec corrected on Day 14, before the work.** The spec-auditor, in a fresh context, ran every
  check on `main` at bcb860e and tried the repository swap in a scratch copy:
  - **No criterion was tagged `new` or `hold`.** Two were already true (the Google tests, the
    brand-new email's terms screen) and are now the first `hold`.
  - **Track A's work did not exist.** Nothing reads a session attribute for terms, today or in the
    monolith: the success handler decides the redirect from the database row. The item was written
    from `plan.md` without the code. Dropped. The largest item, the authorization request, had no
    track; it is now Track A.
  - **"The redirect carries the code" would have broken `contract/`.** `AuthGoogleSignInIT`
    compares the link-required redirect exactly, and posts only email and password to login; so
    does the frontend. A cookie on the 302 reaches the login unasked.
  - **The `HttpSession` grep could not print "clean"** while `establishSession` survived until
    Day 16, and it missed `getSession(` and `changeSessionId`. Decided by the maintainer:
    `establishSession` is deleted here. The grep is widened.
  - **The failure handler creates a session** of its own, on the unverified-email and
    forged-state paths. Now in scope.
  - **Nothing checked the new cookie's attributes, its signature or its size.** In the scratch
    copy, `Path=/nowhere`, `SameSite=Strict` and an unsigned, Java-serialized cookie (2,929 bytes
    before any signature) all left `AuthGoogleSignInIT` green. Decided by the maintainer: signed
    with Day 12's RSA key; `Secure` follows `SESSION_COOKIE_SECURE`.
  - **"Works once" and "cleaned up" could not be seen over HTTP**, and a query filter does not
    clean anything up. Now a repository-level claim test, and deletion on save and by cascade.
  - **The new table had no grants**; it holds Google identities, as `refresh_tokens` holds token
    hashes. Now revoked, with a criterion.
  - **A login for another account must not use up the link,** and no contract test can tell: now a
    Track 0 `hold`.
  - Verify ran 8 tests; it now runs the suite. *Estimate 2 → 3,* for Track 0.
- **Deployment changes.** None to configure: identity's Flyway applies `V2` on start, and
  `SESSION_COOKIE_SECURE` keeps its meaning for the Google flow's cookies.
