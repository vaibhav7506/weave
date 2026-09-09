# Phase 5: editor, history, comparison, export and measured load

Local implementation and verification are complete. Hosted deployment and a GitHub CI run remain pending. No public URL or cloud capacity result is claimed. Phase 6 implementation and local evidence are documented in [Phase 6 hardening](phase6-hardening.md).

## Editor and history

The toolbar and shape panel retain the native canvas workflow. A name field updates live cursor labels and the avatar list; guest colors are assigned per connection. Connection indicators distinguish connecting, syncing, synced, offline and reconnecting, while retaining the queued-operation count and explicit expired-link/rate-limit errors.

**History** opens a read-only projection with an operation slider and the selected operation's HLC wall-time label. The slider follows server acceptance order, not wall-clock sorting: clocks can be skewed, and an operation can arrive late. Position zero is the empty board. The view includes creations, field edits and deletions, including archived operations. Live synchronization continues on a separate projection; **Back to live** shows all intervening edits. Drawing, deletion and undo are disabled during playback. Exports use whichever state is being viewed.

`GET /api/v1/boards/{id}/history?after=0&limit=1000` requires the board's bearer token. The first response fixes `through` to the current committed sequence. Subsequent pages include the same `through`, so ongoing edits cannot change the result while it loads. Responses contain `through`, `nextSequence`, and ordered `{sequenceNumber, operation}` rows. Page size is bounded to 1–1,000. Invalid ranges return 400; unauthorized requests return 401. Active and archived rows are read in one PostgreSQL MVCC statement, so concurrent compaction does not introduce missing or duplicate rows.

History loads incrementally with progress and cancellation. It retains the fetched log in browser memory and caches at most eight nonempty projections, every 500 operations, to speed repeated scrubbing. Evicted positions can be reconstructed from earlier operations. This is not a server-rendered history endpoint or a claim of unlimited browser capacity.

## Live naive comparison

**Naive demo** switches the canvas to the Phase 3 GET/PUT snapshot transport. Local edits send whole snapshots; another browser on the same board in naive mode polls them every 750 ms. **Run concurrent edits** builds two replicas from the same starting version: one moves a rectangle and the other recolors it. The chosen arrival order determines which whole snapshot overwrites the other. The same button in CRDT mode submits independent create/move/color operations through the actual optimistic WebSocket path, where both changes survive.

The result is visible on the canvas, and a message identifies the lost or preserved edit. Both arrival orders are tested. Returning to CRDT restores the durable board. Demo changes never enter its operation log. The intentionally naive state is temporary and held by a single server process; it resets on restart. Use one server, or pin demo requests to one instance, when presenting this comparison. Real CRDT boards retain Phase 4's multi-instance support.

## Export

PNG and SVG exports include all visible elements, even outside the viewport, with a white background and content padding. They omit the grid, selection handles, cursor labels and UI. Text is XML-escaped in SVG, with line breaks retained. PNG reuses the canvas renderer at up to 2× resolution and reduces scale for very large content to stay within 8,192 pixels per axis and approximately 16 million pixels. SVG retains vector coordinates. A historical export gets its sequence in the filename.

## Reproduce the load test

Start the server, PostgreSQL and Redis, then run:

```powershell
cd client
npm run test:load
```

Optional environment variables: `WEAVE_LOAD_URL` (default `http://127.0.0.1:8080`), `WEAVE_LOAD_CLIENTS` (default `5,10,20,40`), `WEAVE_LOAD_ROUNDS` (3), and `WEAVE_LOAD_TICKS` (15). The test creates fresh boards and retains their logs; it does not alter existing boards. Run against a dedicated test deployment when measuring hosted performance.

The test uses Node's raw WebSocket clients and the TypeScript CRDT engine. Each editor creates its own rectangle, waits for every client to receive all creations, waits another 500 ms, then sends 15 field updates at five updates per second. Editors send in synchronized bursts, a deliberately demanding pattern. Timing starts immediately before WebSocket send and ends after a remote replica applies that operation, using a shared monotonic clock. Sender acknowledgments are excluded from broadcast percentiles. After the final send, the harness waits for every operation on every replica, compares their full register/tombstone state, and compares that state with PostgreSQL's snapshot. Optional absent metadata is normalized to null for Java/TypeScript comparison.

The complete run contains 3,375 measured field updates and 92,250 remote deliveries over 12 rounds. Each sample includes PostgreSQL commit and server fan-out. It excludes browser paint and internet latency. This is a short local load test, not a sustained endurance test, an independent-host benchmark, a maximum-client ceiling, or a free-hosting capacity claim.

## Recorded result

2026-09-08, Windows 11, AMD Ryzen 7 4800H (16 logical processors), approximately 15.4 GiB usable RAM; Java 21.0.1 / Spring Boot 3.5.16 with platform threads, PostgreSQL 18.1, Redis 3.0.504, Node 22.22.0. All processes ran on the same laptop over loopback. No build or other test suite ran concurrently with the final measurement. Normal desktop activity was not isolated.

| Editors | Offered updates/s | Median of three p95 latencies | p95 range across rounds | Median convergence after last send |
| ---: | ---: | ---: | ---: | ---: |
| 5 | 25 | 54.97 ms | 53.95–82.23 ms | 49.38 ms |
| 10 | 50 | 101.56 ms | 84.86–107.87 ms | 69.05 ms |
| 20 | 100 | 147.06 ms | 138.05–147.97 ms | 135.79 ms |
| 40 | 200 | 1,958.48 ms | 499.62–2,197.97 ms | 2,130.53 ms |

Every round converged to identical client and database state. At 40 editors the delivery backlog grows and results vary substantially. The evidence supports responsive delivery at 20 editors under this workload; it does not support an under-80-ms claim at 40 editors. Raw per-round p50/p95/p99, all-recipient latency, convergence times and machine details are in [phase5-load.json](phase5-load.json). Fresh runs write `client/test-results/phase5-load.json`; completed rounds are also saved incrementally to `phase5-load-progress.json`.

The first measurement exposed redundant database reads: each broadcast replayed the same suffix separately for every client. The final implementation delivers an already-committed row directly to clients at its immediate predecessor, and shares a replay among clients with the same cursor when gaps need repair. It still requires durable commit before acknowledgment and keeps periodic recovery and Redis notifications. Multi-instance, archived retry, compaction-race, and browser reconnect tests pass after this change.

## Verification and remaining gates

- Full Java suite: 18 tests pass, including the 1,200-case property suite, two servers behind a test load balancer, compaction races, authentication/rate limits and history pagination across compaction and new appends.
- Client suite: convergence, Java/TypeScript parity, history and SVG checks pass; the production build passes.
- Two-browser sync checks compare actual canvas PNG buffers after concurrent edits, offline work, reconnect and reload.
- Phase 5 browser checks cover names, frozen history during remote editing, return to live, downloads, both comparison arrival orders, two-browser naive state, durable-board isolation, and mobile horizontal overflow.
- Compose configuration and Render's official JSON schema validate. The packaged Java application serves the production frontend. Docker Compose now builds and starts successfully; all three browser suites pass against the packaged container on port 5188.
- CI includes both seed suites, browser checks, measured-load smoke, and a separate built-container acceptance job. There is no configured Git remote in this workspace, so no GitHub run has been started or claimed green.
- Public deployment requires an available hosting account and free-resource allowance. See [deployment.md](deployment.md). Do not advance to Phase 6 until the user explicitly requests it.
