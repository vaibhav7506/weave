# Weave



A whiteboard with a hand-built CRDT engine. Moving a shape and changing its color update independent registers, so both edits survive concurrent delivery. The six-phase implementation includes collaborative editing, history, exports, adversarial clock checks, causal deletion collection, network-chaos tests, QR invites and reproducible benchmarks: a pure Java 21 core, a TypeScript mirror, a React + Canvas editor, persistent multi-user synchronization over raw WebSockets, and a runnable naive-sync comparison backed by randomized convergence checks.



## Run locally



Requires Java 21, Node.js 22.12+, PostgreSQL, and Redis. On this Windows workspace PostgreSQL 18 is installed; the helper below creates a **separate instance** inside `.tools/postgres-data`, listening only on `127.0.0.1:55432`. It does not use or modify the existing PostgreSQL service. This development instance uses loopback trust authentication; use a password-protected database for any shared deployment.



```powershell



# From the repository root:



./scripts/start-db.ps1

./scripts/start-redis.ps1



./scripts/maven.ps1 verify



./scripts/start-server.ps1



# In a second terminal:



cd client



npm ci



npm run dev



```



`maven.ps1` downloads Maven 3.9.16 from Maven Central, verifies its SHA-512 checksum, and keeps Maven and its dependencies in the ignored `.tools` directory. A normal Maven installation also works: `mvn -f server/pom.xml verify`. On Windows, stop the running server before repackaging its jar with `verify` or `package`. The server helper creates a random signing secret in `.tools/token-secret` and reuses it on subsequent starts. Keep that file to preserve existing board links.



Open [the local app](http://127.0.0.1:5187/). It creates a board and updates the address with its invite link. Use **Invite to board** to generate a signed QR code and copy a fresh invite link. Open or scan it in another browser to edit the same board; a physical phone requires a reachable deployed URL. Opening the root URL creates a separate board. Drawing controls from Phase 1 remain: rectangle, ellipse, pen, text, select/move/resize, color/stroke, Delete, and Ctrl/Cmd+Z to remove the latest creation. Text has a fixed font size and a resizable selection box.



Committed edits survive refresh and server restarts. Disconnected tabs continue editing; the status shows queued operations until PostgreSQL has acknowledged them. Unacknowledged edits survive reload in per-tab session storage, but do not close an unsynced tab. The application must load while online before offline editing is available. Presence names can be edited and are temporary to the connection. Links expire after seven days.



For another PostgreSQL installation, set `DATABASE_URL` (a JDBC URL), `DATABASE_USER`, and `DATABASE_PASSWORD`. Set `WEAVE_TOKEN_SECRET` to a stable random secret of at least 32 bytes if launching Java directly. `PORT`, `WEAVE_BIND`, and `WEAVE_ORIGINS` configure the server; defaults are loopback port 8080 and Vite's local origins. Adapt `client/vite.config.ts` if the server port changes. See [environment examples](server/.env.example); Spring reads process environment variables, not this file automatically.



To stop only the helper's database:



```powershell



& 'C:/Program Files/PostgreSQL/18/bin/pg_ctl.exe' -D .tools/postgres-data -m fast -w stop



```



## Verify



```powershell



# Start the isolated database first.



./scripts/maven.ps1 verify



# Optional dependency-free Java core runner:



./scripts/test-java.ps1



cd client



npm test



npm run build



# With both the server and Vite running:



npx playwright install chromium



npm run test:e2e



```



The Maven suite contains 23 tests, including Phase 1's 2,141 core assertions, real PostgreSQL/HTTP/WebSocket integration, adversarial clocks, causal deletion collection, network chaos and the isolated naive endpoint. The default integration URL uses a separate `weave_phase2_test` schema; `WEAVE_TEST_DATABASE_URL` can target another database (include `currentSchema=weave_phase2_test`). Test boards use fresh UUIDs and tests do not delete existing boards. Phase 3 adds 1,200 generated Java cases with 3-6 replicas and an independent expected-state oracle, and expands the TypeScript delivery suite to 1,200 cases. Twenty-four Java-generated traces are also checked against TypeScript, in both forward and reversed order. The client also tests hydration of snapshot registers and tombstones.



The two-browser acceptance suite compares the actual Canvas PNG pixel buffers after initial broadcast, concurrent move/color edits, offline drawing on both sides, reconnect, snapshot reload, and a post-reload edit. It also checks the persisted position/color values and absence of browser runtime errors. Ephemeral cursors and local selections are cleared before comparing the shared content. Screenshots and the run summary are written to ignored `client/test-results/`.



These checks are evidence for the implemented protocol, not an exhaustive formal proof. They include the Phase 3 naive-vs-CRDT comparison. Detailed message formats and guarantees are in [the Phase 2 protocol](docs/phase2-protocol.md).



## Phase 3 comparison and correctness



```powershell

# Runnable demo; Java 21 only, no database needed:

./scripts/compare.ps1



# Full Java tests, including 1,200 generated cases:

./scripts/maven.ps1 test

cd client

npm test

npm run test:parity

```



| Arrival order | Naive whole-board replacement | CRDT per-field merge |

| --- | --- | --- |

| Move, then recolor | Move is lost | Both changes survive |

| Recolor, then move | Color is lost | Both changes survive |



`NaiveBoardSync` also runs behind authorized GET/PUT requests at `/api/v1/boards/{id}/comparison/naive`. It keeps separate, ephemeral demonstration state; replacing that snapshot does not touch the real board's PostgreSQL log. The named `ConcurrentMoveAndRecolorTest` asserts the exact two-editor scenario and both delivery orders. The Phase 5 **Naive demo** canvas toggle now uses this transport; demo state remains temporary and per server instance.



[CRDT semantics](docs/crdt-semantics.md) specifies clock rules, register/existence reductions, assumptions, convergence reasoning, immutable strokes, comparison endpoints, and reproduction instructions. The Java property test uses actual HLC ticks and receives under clock rollback, multiple elements and types, duplicates, delayed delivery and deletes, and validates full serialized state against a set-reduction oracle. The tests do not claim that independently advanced local clocks themselves converge.



[CI](.github/workflows/ci.yml) runs two Java base seeds (`73129` and `982451653`), 1,200 cases per seed, plus the client suite, cross-language parity, build, and two-browser reconnect checks. The two Java seed ranges and their parity traces passed locally. The workflow is configured but has not been run on GitHub from this workspace. Projection failures save a replayable trace in `server/target/convergence-failure.json`; fixture files are generated in `server/target/` and remain untracked.



## Phase 4 hardening



Two server instances behind a test load balancer now relay edits and presence through Redis. PostgreSQL catch-up repairs missed notifications. Compaction writes full CRDT snapshots and moves older operations into an archive that preserves retry deduplication and future history. The browser rebases on a compacted snapshot while retaining offline edits. Signed-link expiry is enforced on idle sessions, and per-connection operation floods receive an explicit close reason.



The 50,000-operation local fixture loaded in **414.22 ms** by full replay versus **2.98 ms** from its snapshot (medians of three warmed measurements), with identical state and all operations retained. See [Phase 4 hardening](docs/phase4-hardening.md) for methodology, limitations, settings, and multi-instance test details. This is snapshot-load evidence, not a general capacity benchmark.



Run `./scripts/start-redis.ps1` before the server or full Java tests. It starts the installed Redis executable on loopback port 56379. Shared deployments should supply their own Redis endpoint and password through environment variables. Default limits are 100 operations/second with a 200-operation burst; the client queue sends at most 50/second. CI now includes Redis 7 as well as PostgreSQL.



## Phase 5 editor and measured load



Use **History** to replay saved operations, then **Back to live** to return to current edits. **Export PNG/SVG** exports the visible live or historical state with all offscreen shapes. **Naive demo** switches to an isolated whole-snapshot canvas; **Run concurrent edits** demonstrates a move and recolor under either sync mode and either arrival order. Change your name above the canvas to update cursor labels and avatars.



```powershell

cd client

npm run test:phase5

npm run test:load

```



The final local load run used 5, 10, 20 and 40 raw WebSocket editors, three rounds each, five field updates per second per editor. All 12 rounds converged to identical client and PostgreSQL state. The table reports the median of each round's p95 latency, rather than a pooled percentile.



| Editors | Offered updates/s | Median p95 broadcast latency | Median convergence after last send |

| ---: | ---: | ---: | ---: |

| 5 | 25 | 54.97 ms | 49.38 ms |

| 10 | 50 | 101.56 ms | 69.05 ms |

| 20 | 100 | 147.06 ms | 135.79 ms |

| 40 | 200 | 1,958.48 ms | 2,130.53 ms |



These are short, same-laptop loopback tests on a Ryzen 7 4800H, Java 21 platform threads and PostgreSQL 18.1, excluding browser paint and internet latency. At 40 editors the backlog grows; no low-latency capacity guarantee is claimed there. [Raw results](docs/phase5-load.json) retain all rounds, including the slower ones. [Phase 5 methodology and feature details](docs/phase5-polish-and-load.md) explain the test, memory bounds, history semantics and broadcast optimization.



The Java suite passes 23 tests. Client, parity, production-build and all three browser suites pass locally. The complete Docker Compose stack is healthy and passes those browser suites, including decoded QR invites and mobile editing. Public deployment and an actual GitHub CI run are the remaining external release checks.



For the packaged app, use `./scripts/build.ps1`. With Docker running, use `./scripts/prepare-compose.ps1` then `docker compose up --build --wait --wait-timeout 180`. Compose opens on port **5188** and uses its own persistent database volume; the existing preview stays on **5187**. See [deployment instructions and free-tier limits](docs/deployment.md). The Render blueprint is prepared, not deployed.



## Core contract



- Timestamps order by physical milliseconds, logical counter, then **canonical lowercase UUID string**. Java uses string comparison explicitly to match TypeScript (Java's built-in UUID comparison uses signed components). Physical time fits JavaScript's safe integer range and logical counters fit a nonnegative Java int. Clock overflow fails explicitly.



- Each replica must tick its HLC once per authored operation. An HLC timestamp uniquely identifies one event; it must not be reused for contradictory values. Operation IDs and element IDs are globally unique. Exact replay is idempotent. The server rejects conflicting payloads under one operation ID. Contradictory events sharing an HLC remain outside the core's valid-input contract; malicious clock validation is Phase 6.



- Every mutable field merges independently by greatest HLC. Updating x cannot replace color. Creation fields enter the same registers, so an edit arriving before its create is retained.



- Unknown elements are retained as invisible placeholders until creation arrives. Deletes produce permanent tombstones even before creation and regardless of timestamp; subsequent edits remain in the log/projection but cannot restore visibility. The greatest remove timestamp is retained for deterministic snapshots.



- Freeform stroke points are immutable after creation. Points are normalized within the bounding box, so move/resize changes bounding-box fields, not the point sequence. Nobody edits another user's points within the same gesture. This scope avoids unnecessary sequence-CRDT complexity. Color and stroke width remain editable.



- Element identity belongs to the earliest creation if an element ID is accidentally reused; points belong to that same creation. Ordinary creation fields still merge by their clocks. Applications should always allocate fresh IDs.



- The append-only PostgreSQL operation log is the durable source of truth; the visible board is its projection. Delivery-order log arrays need not match; projected snapshots do. Reads return detached snapshots (TypeScript) or immutable records/maps/lists (Java).



## Phase gate



- [x] Phase 1: core, local canvas, and convergence tests.



- [x] Phase 2: raw WebSocket sync, persistence, board APIs, presence, reconnect.



- [x] Phase 3: naive comparison, 1,200-case convergence suites, cross-language checks, and precise semantics.



- [x] Phase 4: Redis relay, archive-preserving snapshot compaction, link-access hardening, and per-connection operation caps.



- [ ] Phase 5: editor/history/comparison/export and load testing implemented and locally verified; hosted deployment, GitHub CI and container runtime verification pending.



- [ ] Phase 6: adversarial correctness and external benchmarks.



Proceed to the next phase only when the user says **go**. Multiple server instances now share edits through Redis and PostgreSQL. Phase 5 still needs its deployment and external verification gates completed. Phase 6 hardening remains deferred.







## Phase 6: correctness under failure and measured merge performance



Replicas acknowledge their applied sequence; stable deleted elements are collected after lagging leases expire. A small retired-ID fence prevents stale offline edits from restoring deleted identities. Live projection storage shrinks while the complete history archive remains. The synthetic disconnect/reconnect check reduced its snapshot from **1,889 to 897 bytes** and left no active log rows, with all three historical operations still available.



Far-future clocks are rejected before persistence, repeated forgery triggers abuse protection, and healthy editors remain connected. Every WebSocket has a bounded queue and a Java 21 virtual worker. CI runs a real TCP proxy test with latency, jitter, partitions and replayed outboxes. **Invite to board** produces a signed QR locally; browser tests decode the actual QR and verify a phone-sized browser can join and edit.



| Merge hot path (JMH 1.37, Java 21) | Mean ns/op | 99.9% CI error |

| --- | ---: | ---: |

| Operation application | 1,538.34 | ±63.94 |

| HLC comparison | 8.99 | ±0.31 |

| Field-register merge | 11.03 | ±0.49 |



The real `automerge-perf` trace contains 259,778 edits and ends at 104,852 characters. Weave maps characters to whiteboard elements, with ordering maintained externally by the trace adapter. **This is a merge-engine workload, not an implementation of a text sequence CRDT.** Hardware, runtime, workload and memory accounting differ from the published figures; this table is not a speedup claim.



| Implementation | Replay ms | Edits/s | Retained memory |

| --- | ---: | ---: | --- |

| Weave adapter, median of three local rounds | 1,875.55 | 138,508 | 54.7 MB additional engine heap, excluding prebuilt operations |

| Published Yjs 13.6.11 | 5,714 | 45,464 | 3.2 MB JS heap |

| Published Automerge 2.1.10 | 14,326 | 18,133 | Not meaningfully measured; upstream excludes WASM memory |



Weave's shuffled-operation merge took **1,776.86 ms** median and produced exactly the same registers and final text. Upstream separately reports encoded-document parse times of 39 ms (Yjs) and 1,805 ms (Automerge); those measure a different operation. See the [pinned upstream B4 table](https://github.com/dmonad/crdt-benchmarks/tree/796f70250c8c003dfb3a50369d0b2733a760e54d).



[Phase 6 methodology and reproduction](docs/phase6-hardening.md) documents the protocol, trace adapter, memory boundaries and reference hardware. Raw measured results: [JMH](docs/phase6-jmh.json), [editing trace](docs/phase6-trace.json), [GC](docs/phase6-gc.json). Run `npm run test:phase6 --prefix client` against a running app to verify the signed QR flow.


The [virtual-worker load rerun](docs/phase6-load.json) converged in all 15 rounds at 5/10/20/40/80 editors. It did **not** demonstrate the specification's hoped-for higher low-latency ceiling: median round p95 was 147 ms at 20, 2,691 ms at 40 and 12,725 ms at 80. Virtual threads are implemented, but the measured bottleneck remains. See the methodology for the comparison and its limits.
