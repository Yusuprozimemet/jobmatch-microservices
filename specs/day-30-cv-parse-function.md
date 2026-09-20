# Day 30 — cv-parse function

**Phase:** 6 · **Depends on:** Day 29 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Uploading a CV fills in the profile skills, instead of the user typing them by hand.

## In scope
- `functions/cv-parse`: blob-triggered on the `uploads` container.
- Extract text, ask the LLM for skills, map them to the same canonical form
  `JobMatchService.canonicalise` uses — mismatched casing silently breaks matching.
- `PUT /internal/profiles/{userId}/skills` on `identity-service`, with a service token.
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
