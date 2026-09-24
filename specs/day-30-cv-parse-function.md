# Day 30 — cv-parse function

**Phase:** 6 · **Depends on:** Day 29 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Uploading a CV fills in the profile skills, instead of the user typing them by hand.

## In scope
- `functions/cv-parse`: blob-triggered on the `uploads` container.
- Extract text, ask the LLM for skills, and canonicalise them. **There are two
  `canonicalise` methods today and they differ:** `ProfileService`'s collapses hyphens,
  `JobMatchService`'s only lowercases and trims, on purpose, because the mart's spellings are
  hyphenated. Suggestions become profile skills, so they take the profile's form; test that
  they then match through the matching path too.
- `PUT /internal/profiles/{userId}/skill-suggestions` on `identity-service`, with a service
  token. **Suggestions, stored apart from the profile** — writing `skills` directly would
  break the criterion that nothing is applied without the user's confirmation. Confirming
  copies them into the profile through the existing profile update.
- **The function runs outside the cluster**, so it reaches identity through the public
  ingress, not an internal address. Budget for this in config and network rules.
- Result surfaced to the user: suggested skills they confirm, never silently applied.
- Failures: retry twice, then dead-letter and notify. A bad PDF is not an outage.
- Trace context propagated from blob metadata, so the parse joins the upload's trace.

## Out of scope
- Parsing anything but PDF.
- Replacing manual skill entry. This suggests; the user confirms.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Function skeleton, trigger, deploy pipeline |
| B | | Text extraction + LLM prompt + canonicalisation |
| C | | Identity endpoint, frontend confirm step, failure handling |

## Acceptance criteria
- [ ] Uploading a CV produces suggested skills within 60 seconds.
- [ ] Suggested skills are canonicalised identically to profile skills (test both paths).
- [ ] Skills are never written without user confirmation.
- [ ] A corrupt PDF dead-letters with a message the user can understand.
- [ ] The function cannot call anything but the one identity endpoint.
- [ ] One trace covers upload → trigger → parse → skills written.

## Verify
```bash
# upload a real CV, confirm suggestions appear, accept them, check the profile
# upload a corrupt file, confirm the DLQ entry and the user-facing message
```

## Notes
- Canonicalisation is the subtle failure: skills that do not match the mart's form
  produce a profile that silently matches nothing.
- From Day 39: under "own key, own key set", a function calling identity with a service token
  needs a key of its own and a published key set that identity trusts.
