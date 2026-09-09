# Packaging and deployment

Status: the production jar and Docker Compose stack build successfully. PostgreSQL, Redis and the app are healthy. All three browser acceptance suites pass against the container on port 5188, including favicon delivery, QR decoding and editing from a phone-sized browser. Public deployment and a GitHub CI run remain pending.

## Docker Compose

From the repository root, with Docker Desktop's Linux engine running:

```powershell
./scripts/prepare-compose.ps1
docker compose up --build --wait --wait-timeout 180
```

The helper generates separate random database and signing secrets in ignored `.env`, preserving an existing file. Keep it private and retain it across restarts. On other operating systems, copy `.env.example` to `.env` and provide independent random secrets (at least 32 bytes each).

Open `http://127.0.0.1:5188`. This port is separate from the existing Vite preview on 5187. Compose uses its own PostgreSQL volume; it does not import local helper boards or reuse their invite tokens. PostgreSQL and Redis are internal to the Compose network with no host ports. Only the app is exposed on loopback. PostgreSQL persists in `weave_postgres-data`; Redis carries ephemeral Pub/Sub traffic. `docker compose down` stops services while preserving the database volume. Retain the volume and `.env` to retain board access.

The image builds the React frontend, embeds it in the Spring Boot jar, and runs Java 21 as an unprivileged user. Browser API and WebSocket URLs remain same-origin. A 768 MiB Compose memory limit and JVM container memory settings bound local container memory. `/api/v1/health` checks PostgreSQL and Redis and returns only `ok` or `unavailable`. The Docker health check and Compose dependencies wait for services to become ready.

After startup:

```powershell
cd client
$env:WEAVE_PREVIEW_URL='http://127.0.0.1:5188'
npm run test:e2e
npm run test:phase5
npm run test:phase6
```

## Production jar without Docker

```powershell
# Stop any process using the existing jar before repackaging on Windows.
./scripts/build.ps1
# Then set the database, Redis, signing secret and public origin environment values.
./scripts/start-server.ps1
```

`build.ps1` builds the client and runs Maven packaging/tests with the `frontend` profile. Use `-SkipTests` only when the current source has already passed tests. A stock Maven installation can use `npm run build --prefix client` followed by `mvn -f server/pom.xml package -Pfrontend`. The resulting `server/target/weave-server-0.2.0.jar` contains the frontend; Vite is unnecessary in deployment. Set `WEAVE_BIND=0.0.0.0` inside a hosted container and `WEAVE_ORIGINS` to exact HTTPS public origins. The local preview remains on 5187; direct localhost access on 8080 also requires including that origin explicitly.

## Prepared free Render demo

`render.yaml` declares a Docker web service, PostgreSQL 17 database and Redis-compatible Key Value service, all explicitly on the `free` plan, in Singapore. Automatic redeployment is off. The schema was validated against [Render's published schema](https://render.com/schema/render.yaml.json). The blueprint uses generated signing credentials, private datastore URLs, and closed external datastore access. The entrypoint converts the supplied Postgres URL to JDBC without printing credentials; database credentials are injected separately. Spring's `SPRING_DATA_REDIS_URL` accepts Render's private Redis connection URL. `RENDER_EXTERNAL_URL` supplies the allowed origin unless `WEAVE_ORIGINS` is explicitly set.

The pending deployment sequence is:

1. Select an accessible GitHub repository for this source and run its configured correctness/container CI jobs.
2. Connect that repository to an available Render account, import `render.yaml`, and confirm all three resources remain on the free plans with account allowance available.
3. Deploy, wait for `/api/v1/health`, then run all three browser suites against the actual HTTPS URL. Configure `WEAVE_ORIGINS` explicitly if using a custom domain.
4. Record the successful CI run and deployed URL here. Measure hosted latency separately before making cloud capacity claims.

This blueprint is a temporary demonstration path: [Render's free PostgreSQL databases expire after 30 days](https://render.com/docs/free). Free web services may sleep and are subject to monthly free-hour limits. Preserve a database export before expiry; switching to paid infrastructure requires an explicit budget decision. A free plan must never be silently upgraded to complete this task.

The Fly.io CLI is authenticated locally, but [Fly.io has no general free tier](https://fly.io/docs/about/cost-management/). A free allowance for the current account has not been verified, so no Fly resources were created. Oracle hosting would require an existing account with available Always Free capacity. Sites hosting runs Workers and cannot execute the specified Java/PostgreSQL backend; publishing only the static frontend there would leave Weave nonfunctional.

## Operational boundaries

Use TLS at the public ingress and preserve WebSocket upgrades and Origin. Keep the signing secret stable across instances and deployments. Persistent boards require PostgreSQL backups; the archive intentionally retains all history. The V3 migration is additive, but after GC has run, rollback must preserve the GC-aware replay and retired-ID checks. Do not roll back to a pre-V3 application against a database containing retired elements. Restore a pre-GC backup into a separate database if such a rollback is required. Keep the signing secret stable. Restore data into a separate database to verify backups before changing a live connection. Do not remove volumes as part of a routine restart.

This phase uses link-based editing permissions, not user accounts. A single server supports the intentionally temporary naive-demo transport; CRDT edits use Redis/PostgreSQL for multi-instance delivery. See the Phase 4 and Phase 5 documents for limits and measured behavior.
