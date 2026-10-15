# Packaging and deployment

Status: the production jar and Docker Compose stack build successfully. PostgreSQL, Redis and the app are healthy locally. All three browser acceptance suites pass against the container on port 5188, including favicon delivery, QR decoding and editing from a phone-sized browser. The public service is deployed at `https://weavevaibhav7506-whiteboard.onrender.com`.

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

## Render web service with Neon PostgreSQL

The live service runs one free Docker web service in Singapore and connects to a Neon PostgreSQL database over TLS. In Render, provide the Neon direct connection string only as the secret `DATABASE_CONNECTION_URI`. The container derives the JDBC URL, database user and password at startup without writing them to files or command arguments. `RENDER_EXTERNAL_URL` supplies the allowed origin unless `WEAVE_ORIGINS` is explicitly set.

For a new Neon database, its first deployment creates Weave's `weave` schema and migrations automatically. The previous Render free database cannot be migrated after expiry because free instances have no recovery capability.

The existing Key Value instance is private to Oregon and cannot be resolved by the Singapore web service. The public demo therefore sets `WEAVE_REDIS_ENABLED=false` instead of exposing a shared queue to the internet. It is a single-service deployment: durable PostgreSQL boards, history, signed QR invites, exports, and editing work; Redis-backed multi-instance relay is verified in local Docker/CI only.

The successful GitHub Actions run is `34326632061`. Hosted latency has not been measured, and no cloud capacity claim is made.

Neon's free plan has no fixed database expiration but has storage and monthly compute limits; it can change in the future. [Render's free PostgreSQL databases expire after 30 days](https://render.com/docs/free), so it is not suitable for this durable deployment. Render's free web service may still sleep and is subject to monthly free-hour limits. Preserve regular database exports; switching to paid infrastructure requires an explicit budget decision.

The Fly.io CLI is authenticated locally, but [Fly.io has no general free tier](https://fly.io/docs/about/cost-management/). A free allowance for the current account has not been verified, so no Fly resources were created. Oracle hosting would require an existing account with available Always Free capacity. Sites hosting runs Workers and cannot execute the specified Java/PostgreSQL backend; publishing only the static frontend there would leave Weave nonfunctional.

## Operational boundaries

Use TLS at the public ingress and preserve WebSocket upgrades and Origin. Keep the signing secret stable across instances and deployments. Persistent boards require PostgreSQL backups; the archive intentionally retains all history. The V3 migration is additive, but after GC has run, rollback must preserve the GC-aware replay and retired-ID checks. Do not roll back to a pre-V3 application against a database containing retired elements. Restore a pre-GC backup into a separate database if such a rollback is required. Keep the signing secret stable. Restore data into a separate database to verify backups before changing a live connection. Do not remove volumes as part of a routine restart.

This phase uses link-based editing permissions, not user accounts. A single server supports the intentionally temporary naive-demo transport; CRDT edits use Redis/PostgreSQL for multi-instance delivery. See the Phase 4 and Phase 5 documents for limits and measured behavior.
