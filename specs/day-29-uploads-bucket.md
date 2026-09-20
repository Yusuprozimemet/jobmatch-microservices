# Day 29 — Uploads bucket and direct browser upload

**Phase:** 6 · **Depends on:** Day 28 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
A user can upload a CV straight to private storage. The file never passes through a service.

## In scope
- New **private** `uploads` container. Separate from the pipeline's `prod`/`dev` landing
  zone — different data, different retention, different access rules. Keep the permission
  boundary documented in `data/src/ingestion/storage.py` intact.
- `POST /api/profile/cv/upload-url` in `identity-service`: returns a short-lived
  write-only SAS URL (presigned URL) scoped to one blob path, `{userId}/{uuid}.pdf`.
- Frontend uploads directly with `PUT`, then polls for the parse result.
- Server-side limits: content type `application/pdf`, size cap, SAS expiry under 5 minutes.
- `user.deleted` consumer deletes the user's prefix (the Day 27 row left open).
- Retention policy on the container.

## Out of scope
- Parsing — Day 30.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Container, access policy, retention, IaC placeholder |
| B | | SAS issuing endpoint + limits |
| C | | Frontend upload flow + `user.deleted` consumer |

## Acceptance criteria
- [ ] The container is not publicly readable (test with an unauthenticated GET).
- [ ] A SAS URL cannot write outside its own blob path.
- [ ] An expired SAS URL is rejected.
- [ ] A non-PDF or oversized upload is rejected.
- [ ] Deleting a user removes every blob under their prefix.
- [ ] No CV bytes ever pass through a service (check the request logs).

## Verify
```bash
curl -s -o /dev/null -w '%{http_code}' "https://<account>/uploads/<path>"   # 404/403
# request a SAS, PUT a PDF, confirm it lands; wait past expiry and retry
```

## Notes
- Scope the SAS to one blob, not the container. A container-wide token lets any user
  read every CV.
