# Configuration

Every setting the three services read, where it comes from, and what breaks without it. One page, so
that "why does it work on my machine" has somewhere to be answered.

The rule everywhere: **each value reads an environment variable and falls back to a
local-development default**, so a fresh clone runs with nothing configured, and every environment
past that one is a list of overrides rather than an edited file.

---

## Table of contents

- [1. Four env files, and who reads them](#1-four-env-files-and-who-reads-them)
- [2. The local stack](#2-the-local-stack)
- [3. Backend](#3-backend)
- [4. Frontend](#4-frontend)
- [5. Data pipeline](#5-data-pipeline)
- [6. The database: schemas and roles](#6-the-database-schemas-and-roles)
- [7. What degrades without which key](#7-what-degrades-without-which-key)
- [8. Production checklist](#8-production-checklist)
- [9. Gotchas](#9-gotchas)

---

# 1. Four env files, and who reads them

| File | Read by | Committed? |
| --- | --- | --- |
| [`.env`](../../.env.example) | `docker-compose.yml`, for `${...}` substitution — the Postgres container's credentials, and Google's if you put them there | No. `.env.example` is |
| [`backend/.env`](../.env.example) | Loaded by hand for `./mvnw spring-boot:run`. **Not** read by compose: the backend service has no `env_file` | No |
| [`frontend/.env.local`](../../frontend/.env.example) | Next, in local development | No |
| [`data/.env`](../../data/.env.example) | The pipeline scripts and `astro dev start` | No |

**Spring Boot does not read `.env` by itself.** Running the backend outside Docker means loading it
yourself — `set -a; source .env; set +a`, an IDE plugin, or `--env-file` — or setting the variables
in the run configuration.

Under compose, the backend gets exactly what its `environment:` block names and nothing else. It
does not name `GOOGLE_CLIENT_ID` or the mail settings, so the compose stack runs with Google
sign-in off (its routes are 404) and no reset emails — see
[section 7](#7-what-degrades-without-which-key). Run the backend by hand with `backend/.env` for
those. The model's key is matching-service's alone since Day 21: compose passes `LLM_API_KEY` from
the root `.env` to it and to nothing else, and without it matching ranks by skill overlap.

---

# 2. The local stack

```mermaid
flowchart LR
    B(["browser :3000"]) --> FE["frontend<br/>Next standalone server"]
    FE -->|"/api/* rewritten to<br/>BACKEND_API_URL"| GW["api-gateway<br/>:8080 on the host"]
    GW --> BE["backend<br/>Spring Boot, no published port"]
    GW -->|"/api/jobs/**"| JS["job-service<br/>no published port"]
    GW -->|"/api/jobs/top-matches"| MS["matching-service<br/>no published port"]
    JS <-->|"/internal/**,<br/>service tokens"| BE
    MS -->|"/internal/**,<br/>service tokens"| BE
    MS -->|"/internal/**,<br/>service tokens"| JS
    MS -.->|"LLM_API_KEY"| LLM["language model<br/>optional"]
    BE --> DB[("postgres :5432<br/>module + analytics schemas")]
    JS -->|"as jobs_user"| DB
    MS -->|"as matching_user"| DB
    PIPE["pipeline<br/>profile: data, run-once"] -.->|"publishes marts"| DB

    classDef s fill:#e8eef7,stroke:#4a6080
    class FE,GW,BE,JS,MS s
```

```bash
cp .env.example .env
scripts/dev-up.sh          # docker compose up -d db backend api-gateway frontend
```

| Service | Port | Notes |
| --- | --- | --- |
| `db` | 5432 | `postgres:18.4-alpine`, volume `db-data`, `pg_isready` healthcheck |
| `jwt-key` | — | Runs and exits. Writes the token signing key into the `jwt-keys` volume on the first start, then keeps it until `down -v` |
| `backend` | — | Built from `./backend`. Listens on 8080 inside the network only (Day 16). Waits for the database to be healthy and for `jwt-key` to finish |
| `job-service` | — | Built from `./services/job-service` (Day 17). Job search and the postings routes. Listens on 8080 inside the network only; healthcheck on `/actuator/health/readiness` (management port 9090). Needs its image built first when the harness runs it: `docker build -t jobmatch-job-service:harness services/job-service` |
| `matching-service` | — | Built from `./services/matching-service` (Day 21). Top matches, and the only service with the model's key. Listens on 8080 inside the network only; healthcheck on `/actuator/health/readiness` (management port 9090). Its harness image: `docker build -t jobmatch-matching-service:harness services/matching-service` |
| `api-gateway` | 8080 | Built from `./services/api-gateway`. Listens on 8081, published on 8080. Healthcheck on `/actuator/health/readiness` (management port 9090, inside the network). `depends_on: backend` |
| `frontend` | 3000 | Built from `./frontend`. Waits for the gateway to be healthy (`condition: service_healthy`), so `up --wait` returns once the gateway is ready |
| `pipeline` | — | Under the `data` profile, so `up` never starts it. It runs and exits: `docker compose run --rm pipeline` |

The browser only ever talks to port 3000. Next rewrites `/api/*` to `BACKEND_API_URL`
([`proxy.ts`](../../frontend/src/proxy.ts)), the gateway, which is what keeps the auth cookies on one
origin; the gateway refuses every cross-origin preflight. [`architecture.md`](architecture.md) draws
the path before and after the gateway.

The gateway's settings, all with defaults that suit compose:

| Variable | Default | |
| --- | --- | --- |
| `BACKEND_URL` | `http://localhost:8080` — `http://backend:8080` in compose | Where it forwards, and where it fetches `/.well-known/jwks.json` |
| `JOB_SERVICE_URL` | `BACKEND_URL`'s value — `http://job-service:8080` in compose | Where job search goes: `/api/jobs`, `/api/jobs/filters`, `/api/jobs/{postingId}`. Not `top-matches`, which is matching's. The backend no longer serves these (Day 17), so outside compose it must be set |
| `MATCHING_SERVICE_URL` | `BACKEND_URL`'s value — `http://matching-service:8080` in compose | Where `/api/jobs/top-matches` goes. The backend no longer serves it (Day 21), so outside compose it must be set |
| `RATE_LIMIT_AUTH_PER_MINUTE` | `10` | Login, register and the two password-reset steps, per client |
| `GATEWAY_TRUSTED_PROXIES` | empty | A regex of proxy addresses whose `X-Forwarded-For` is believed. Leave it empty behind the frontend, which passes a client's own header through; set it only for a proxy that overwrites it |
| `GATEWAY_CONNECT_TIMEOUT` / `GATEWAY_READ_TIMEOUT` | `5s` / `30s` | Past them the gateway answers 502 / 504 |
| `TRACING_EXPORT_ENABLED`, `OTEL_TRACES_ENDPOINT` | `false`, local Tempo | As the backend's |

job-service's settings (Day 17), as compose sets them:

| Variable | Default | |
| --- | --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `5432`, `jobs_db` | Its own database, holding the analytics mart (Day 20); compose passes `JOBS_DB_NAME` |
| `DB_JOBS_USER` / `DB_JOBS_PASSWORD` | `jobs_user` / `password` | The read-only role ([§6](#6-the-database-schemas-and-roles)); compose passes `JOBS_DB_PASSWORD` |
| `SERVICE_JWT_PRIVATE_KEY_FILE` | none | Its own key for service tokens, issuer `jobmatch-job-service`. **Required**; compose's `jwt-key` service writes it |
| `BACKEND_KEY_SET_URL` | empty | The monolith's service key set, so `jobmatch-backend` may call its `/internal/**` routes. Compose: `http://backend:8080/.well-known/service-jwks.json` |
| `APP_INTERNAL_TRUSTEDISSUERS_0_NAME` / `..._0_KEYSETURL` | none | Further trusted issuers, as the backend's ([auth.md](auth.md#service-tokens-and-internal)) |
| `INTERNAL_APPLICATIONS_URL` | empty | Where it asks for saved counts (`/internal/saved-counts`): the backend, `http://backend:8080` in compose. Empty would mean itself, which has no such route |
| `MANAGEMENT_PORT` | `9090` | Actuator: health and `/actuator/prometheus`, inside the network only |
| `TRACING_EXPORT_ENABLED`, `OTEL_TRACES_ENDPOINT`, `TRACING_PROBABILITY` | `false`, local Tempo, `1.0` | As the backend's |

matching-service's settings (Day 21), in its
[`application.yaml`](../../services/matching-service/src/main/resources/application.yaml):

| Variable | Default | |
| --- | --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `5432`, `project_db` | The `matching` schema is still in the monolith's database, until Day 23 |
| `DB_MATCHING_USER` / `DB_MATCHING_PASSWORD` | `matching_user` / `password` | Its own login; its Flyway applies `db/matching` as it. Compose passes `MATCHING_DB_PASSWORD` |
| `SERVICE_JWT_PRIVATE_KEY_FILE` | none | Its own key for service tokens, issuer `jobmatch-matching-service`. **Required**; compose's `jwt-key` service writes it. The backend and job-service trust it |
| `INTERNAL_IDENTITY_URL` | none | Where it asks whether a user exists (`/internal/users/{id}`) and for their profile (`/internal/profiles/{userId}`): the backend, `http://backend:8080` in compose. **Required**: it does not start without it |
| `INTERNAL_JOBS_URL` | none | Where it asks for the shortlist (`/internal/postings/shortlist`): job-service, `http://job-service:8080` in compose. **Required** |
| `IDENTITY_JWKS_URL` | `INTERNAL_IDENTITY_URL` + `/.well-known/jwks.json` | The user key set it checks tokens against |
| `MANAGEMENT_PORT` | `9090` | Actuator: health and `/actuator/prometheus`, inside the network only |
| `TRACING_EXPORT_ENABLED`, `OTEL_TRACES_ENDPOINT`, `TRACING_PROBABILITY` | `false`, local Tempo, `1.0` | As the backend's |

And the model, which only it calls. With no key it still answers, by skill overlap
([`matching-profile.md`](matching-profile.md)):

| Variable | Default | |
| --- | --- | --- |
| `LLM_API_KEY` | empty | Empty disables model scoring; matching still ranks by skill overlap |
| `LLM_BASE_URL` | Gemini's OpenAI-compatible endpoint | Any chat-completions API |
| `LLM_MODEL` | `gemini-flash-lite-latest` | Part of the cache key |
| `LLM_TIMEOUT_SECONDS` | `13` | Read timeout; connect is fixed at 5s. 13 keeps the worst case, three internal calls, the score reads and writes and the model, at 28.5 s, under the gateway's 30 s read |
| `LLM_REASONING_EFFORT` | `low` | Empty omits the field for providers that reject it |
| `LLM_SCORE_RETENTION_DAYS` | `1` | Clamped to a minimum of 1 |
| `SCORES_DYNAMODB_ENDPOINT` | empty | The score store (Day 22); compose sets the emulator |
| `SCORES_CREATE_TABLE` | `false` | `true` in compose: the service creates the table at startup |

At most ten scoring calls run at once (a bulkhead, Day 21); an eleventh request is answered by
skill overlap at once rather than waiting.

**The gateway's healthcheck makes `up --wait` wait until it is ready.** The backend has actuator on
its management port but compose has no healthcheck for it. The gateway's check is the one compose
waits on, and the frontend starts only after it. It does not close the window for a request sent
straight to the published 8080 before the gateway is ready.

---

# 3. Backend

All of it in [`application.yaml`](../src/main/resources/application.yaml).

## Database

| Variable | Default | |
| --- | --- | --- |
| `DB_HOST` | `localhost` | `db` under compose |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `project_db` | The mart is not here: it is in `jobs_db`, job-service's (Day 20) |
| `DB_USER` | `admin` | The owner of the migrations; only Flyway logs in as it. `app_user` in a production-like setup |
| `DB_PASSWORD` | `password` | |
| `DB_IDENTITY_USER`, `DB_APPLICATIONS_USER` | `identity_user`, `applications_user` | Each module's own login, with its own schema as the search path (Day 11). Jobs and matching are their services' logins now (Days 17 and 21) |
| `DB_IDENTITY_PASSWORD`, `DB_APPLICATIONS_PASSWORD` | `password` | Their passwords |

There is no schema setting since Day 11: the owner's Flyway migrates `app` (V1–V14), and each
module's Flyway migrates its own schema as its own login.

## Application

| Variable | Default | |
| --- | --- | --- |
| `APP_BASE_URL` | `http://localhost:3000` | The public address. Every OAuth redirect and the password-reset link are built from it. **No trailing slash** |
| `INTERNAL_JOBS_URL` | empty | Where saved jobs sends its internal calls for postings (`/internal/postings/**`, Day 19). Empty means this process, `http://localhost:<the server's port>`, read on the first call, which no longer serves them (Day 17): set it to job-service, `http://job-service:8080` in compose |
| `SESSION_COOKIE_SECURE` | `false` | Despite the name, no session cookie since Day 14: `Secure` on the two token cookies and the Google flow's two, `google_auth_request` and `pending_google_link`. Must be `true` on HTTPS |
| `JWT_PRIVATE_KEY_FILE` | none | The RSA key tokens are signed with: a PEM, PKCS#8 file of at least 2048 bits. **Required**, in every profile; the backend never makes one. Compose sets it to the key its `jwt-key` service writes; outside compose, `scripts/jwt-key.sh backend/.jwt/private.pem` |
| `SERVICE_JWT_PRIVATE_KEY_FILE` | none | The monolith's key for service tokens (Day 39): a PEM, PKCS#8 file of at least 2048 bits. **Required**, separate from the user key; the backend never makes one. Compose sets it to the key its `jwt-key` service writes; outside compose, `scripts/jwt-key.sh backend/.jwt/service.pem` |
| `SPRING_PROFILES_ACTIVE` | none | `dev` or `prod`. The Docker image sets `SPRING_PROFILES_DEFAULT=prod` |

## Google sign-in

| Variable | Default | |
| --- | --- | --- |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | empty | Empty means the OAuth routes do not exist at all |
| `GOOGLE_REDIRECT_URI` | `${APP_BASE_URL}/api/login/oauth2/code/google` | Must match the Google Console entry exactly |
| `OAUTH2_SUCCESS_REDIRECT` / `OAUTH2_TERMS_REDIRECT` / `OAUTH2_LINK_REDIRECT` / `OAUTH2_FAILURE_REDIRECT` | paths under `APP_BASE_URL` | See [`auth.md`](auth.md) |

## Email

| Variable | Default | |
| --- | --- | --- |
| `MAIL_HOST` / `MAIL_PORT` | `smtp-relay.brevo.com` / `587` | |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | empty | Empty logs a warning at startup; reset emails then fail silently |
| `MAIL_FROM` | `jobmatch.team2026@gmail.com` | |

The model's settings (`LLM_*`) left with matching on Day 21: they are matching-service's, in
[section 2](#2-the-local-stack).

Any Spring property can be set the same way: upper-case it and replace `.` with `_`, so
`server.port` becomes `SERVER_PORT`.

## The profiles

`application-dev.yaml` and `application-prod.yaml` layer on top when the matching profile is active.
Both currently set only a logging level — with one exception that matters:

**`application-prod.yaml` redeclares the datasource with no fallbacks.** `${DB_HOST}` rather than
`${DB_HOST:localhost}`. Under the `prod` profile — which is what the Docker image runs — an unset
`DB_*` variable is a startup failure instead of a quiet connection attempt against localhost. That is
deliberate: in production, defaulting to a local database is a worse outcome than not starting.

No profile is active unless you ask for one:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

# 4. Frontend

One variable.

| Variable | Default | |
| --- | --- | --- |
| `BACKEND_API_URL` | `http://localhost:8080` — `http://api-gateway:8081` in the image | Where the proxy and the server components send `/api` traffic: the gateway |

It is read at **runtime**, not baked in at build: [`config.ts`](../../frontend/src/lib/config.ts) is
a plain `process.env` read, and the Dockerfile sets a default that compose overrides. So the same
image works in any environment.

There is no `NEXT_PUBLIC_` variable anywhere, which is the point — the backend URL is a server-side
detail, and the browser only ever knows about its own origin.

The image is a two-stage build producing Next's `standalone` output. `HOSTNAME=0.0.0.0` is set
explicitly, because the standalone server binds `$HOSTNAME` and would otherwise pick up the
container name and listen on the wrong interface. Node 24 is required (`engines` in
`package.json`).

---

# 5. Data pipeline

The pipeline has its own, much longer configuration, documented where it belongs:
[`data/.env.example`](../../data/.env.example) and [`data/README.md`](../../data/README.md). The
groups, so you know what you are looking at:

| Group | Examples | |
| --- | --- | --- |
| Source | `SOURCE_API_URL` | The FreeHire endpoint. No key needed |
| Landing zone | `STORAGE_ACCOUNT`, `LANDING_CONTAINER`, `LANDING_PREFIX`, `LANDING_PATH` | `dev` is yours, `prod` is the scheduled run's |
| Databricks | `DATABRICKS_HOST`, `DATABRICKS_CATALOG`, `DATABRICKS_HTTP_PATH`, `DBT_SCHEMA`, `DATABRICKS_TOKEN` | The token is personal, not the team's |
| Backend database | `BACKEND_PG_*`, `BACKEND_PG_PUBLISH_SCHEMA` | Where marts are published |
| Azure (optional) | `ACA_INGEST_JOB`, `ACR_NAME`, `AZURE_*` | Only for running the real DAG locally |

`scripts/common.sh` asserts eleven of these are non-empty before any script runs, which is why the
helper scripts fail with a named variable rather than a stack trace.

The one that matters to the backend team is **`BACKEND_PG_PUBLISH_SCHEMA`** — see
[gotchas](#9-gotchas).

---

# 6. The database: schemas and roles

[`scripts/db-setup.py`](../../scripts/db-setup.py) creates the production-like arrangement: two
databases, six schemas, and one login role per owner.

| Database | Schema | Owner role | Written by | Read by |
| --- | --- | --- | --- | --- |
| `project_db` | `app` | `app_user` | the backend's migrations, V1–V14 | everyone, read-only |
| `project_db` | `identity`, `applications`, `matching` | `identity_user`, `applications_user`, `matching_user` | that backend module, as its own login (Day 11) | its owner only (Day 38) |
| `jobs_db` | `analytics` | `analytics_user` | the scheduled pipeline | `jobs_user` (job-service) and `analytics_dev_user`, read-only |
| `jobs_db` | `analytics_dev` | `analytics_dev_user` | trainees, by hand | `jobs_user` and `analytics_user`, read-only |

Each role has full access to what it owns and read-only access to the others in the same database,
for existing and future objects, except the module schemas: no other login reads them, so a module
that leaves the process takes a login that reads only its own data. A database set up before Day 38
had those grants; each module's own migration revokes them. Only `jobs_user` and the two analytics
roles may connect to `jobs_db` (Day 20); a `project_db` set up before then keeps its analytics
schemas until [the runbook](../../docs/runbooks/jobs-db.md) drops them. **The separation is
enforced by grants, not by agreement** — that is the whole point, and it is what makes "the backend
cannot corrupt the marts" a fact rather than a promise.

The third schema exists so a trainee building a mart never needs the credential that owns
production. The script is idempotent, so a failed run can be repeated.

The plain single-container setup in [`backend/README.md`](../README.md) skips all of this: one
`admin` superuser, one schema. Fine for development, wrong for anything shared.

---

# 7. What degrades without which key

The app is built so a fresh clone with no secrets still runs end to end. Every optional key removes a
feature rather than breaking the app.

| Missing | What happens | Where you find out |
| --- | --- | --- |
| `LLM_API_KEY` | Matches rank by skill overlap only; every row has `aiScored: false` | matching-service's startup: *"LLM_API_KEY is not set: /api/jobs/top-matches will rank by skill overlap only."* |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Reset emails never arrive; `forgot-password` still answers 200, by design | Startup: *"Mail service warning..."* |
| `GOOGLE_CLIENT_ID` | The Google button 404s — the routes are not registered | Startup: *"Google sign-in disabled..."* |
| An empty `analytics` schema | Jobs list is empty, filters are empty, matches are `[]`. No errors | Only by looking |
| `SESSION_COOKIE_SECURE=true` on plain HTTP | The browser silently drops the Google flow's cookies; Google sign-in fails | Nowhere — this one is invisible |
| `DB_*` under the `prod` profile | The app does not start | Immediately |
| `JWT_PRIVATE_KEY_FILE`, in any profile | The app does not start. It is not optional: a key made at startup would sign everyone out on each restart | Startup: *"JWT_PRIVATE_KEY_FILE is not set..."*, or names the file and what is wrong with it |
| `SERVICE_JWT_PRIVATE_KEY_FILE`, in any profile | The app does not start. It is not optional: the service key is never generated | Startup: *"SERVICE_JWT_PRIVATE_KEY_FILE is not set..."*, or names the file and what is wrong with it |

The first three all announce themselves at startup, which is deliberate: a feature that is off should
say so once, loudly, rather than fail per request.

---

# 8. Production checklist

What must be set beyond the defaults, in one place:

- [ ] `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER=app_user`, `DB_PASSWORD`, and each module's login:
      `DB_IDENTITY_USER`, `DB_APPLICATIONS_USER` with their `_PASSWORD`s — the `prod` profile has no
      fallbacks, and the backend does not start without them. job-service's `DB_JOBS_*` and
      matching-service's `DB_MATCHING_*` are set on those services
- [ ] `JWT_PRIVATE_KEY_FILE`, pointing at a key from the secret store — the backend does not start
      without it. Replacing the key later signs every user out
- [ ] `SERVICE_JWT_PRIVATE_KEY_FILE`, pointing at the service key from the secret store — the
      backend does not start without it
- [ ] `APP_BASE_URL=https://c55c.hyf.dev`, no trailing slash
- [ ] `SESSION_COOKIE_SECURE=true`
- [ ] `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, with the redirect URI registered in the Google
      Console for that exact host
- [ ] `MAIL_USERNAME` / `MAIL_PASSWORD`, or accept that password reset does not work
- [ ] `LLM_API_KEY` on matching-service, or accept overlap-only matching; with
      `INTERNAL_IDENTITY_URL` and `INTERNAL_JOBS_URL`, without which it does not start
- [ ] `BACKEND_API_URL` on the frontend, pointing at the gateway's internal address, and
      `BACKEND_URL` on the gateway at the backend's. Only the gateway gets the public ingress
- [ ] `GATEWAY_TRUSTED_PROXIES`, only if the ingress overwrites `X-Forwarded-For`; otherwise every
      user shares the ingress's one rate-limit bucket
- [ ] The pipeline publishing to `analytics`, not `analytics_dev`

Never in a `docker run` command: use `--env-file` with a gitignored file, or the host's secret
manager. And never commit any of it — `.env` is gitignored in all four places, `.env.example` is what
belongs in the repository.

---

# 9. Gotchas

**The `analytics` schema name is hard-coded in SQL.** `JobRepository` and `JobMatchRepository` write
`FROM analytics.fct_postings` literally — there is no `ANALYTICS_SCHEMA` variable, whatever older
notes may say. So a trainee publishing to `analytics_dev` while running the backend locally sees an
empty job list and no error, because the backend is reading a schema nobody wrote to. Either publish
to `analytics` locally, or change the SQL; there is no setting for it.

**`localhost` inside a container is the container.** To reach a database on the host from a
container, use `host.docker.internal`. Under compose, use the service name — `db`.

**A trailing slash on `APP_BASE_URL`** produces `https://host//api/login/oauth2/code/google`, which
does not match what Google has registered, and the failure appears at Google rather than in your
logs.

**Compose never reads `backend/.env`.** Setting a variable there and wondering why the compose
backend ignores it is the usual version of this: only the `environment:` block reaches it.

**`data/.env` is optional to compose** (`required: false`) on purpose: without
it, older Compose versions read every service's `env_file` while loading the project — even for an
inactive profile — and `docker compose up -d db` failed on a clean clone before anyone had written
`data/.env`.

**Two kinds of migration, two logins.** `DB_USER` migrates `app` (V1–V14, in
`app/src/main/resources/db/migration`); each module's Flyway migrates its own schema as that module's
login (`<module>/src/main/resources/db/<module>`, baselined at 0). A login that does not own the
schema its Flyway points at fails startup with `permission denied for schema`.

**Restarting the backend signs no one out** since Day 13: the login is a token the browser holds,
not a session in the container's memory. Since Day 14 Google sign-in keeps none either: its state is
in the browser's cookies and `identity.pending_google_links`, so two backend instances need no
sticky sessions.
