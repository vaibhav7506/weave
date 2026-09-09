# Weave — Complete Build Specification

**Purpose of this document:** a build-ready specification for a real-time collaborative whiteboard with a hand-built CRDT core, detailed enough to hand to a coding agent one phase at a time. Same depth and structure as the EDIFlow/Loadline specs.

---

## 0. Project Identity

- **Name:** Weave. (Concurrent edits weaving together into one consistent board — literal enough to be clear, not so literal it's boring.)
- **One-liner:** a real-time collaborative whiteboard, backed by a CRDT (Conflict-free Replicated Data Type) implemented from scratch — not pulled from a library — so that concurrent edits from multiple users, over unreliable networks, always converge to the same correct state on every client, provably.
- **Why this project, specifically:** it's the rare project where the hard engineering problem, the demo, and the UI polish are the same fifteen seconds of screen time. Open two browser windows, edit both, watch them converge — a non-technical person understands it instantly, and a senior engineer immediately has the right question ready ("what happens if I edit the same field on both at once?") that this project has a real, testable answer to.
- **The naive-code-fails story, stated up front:** the obvious way to sync a shared document — last-write-wins over the whole board — silently destroys one user's edits whenever two people touch the board close together in time. Weave's entire premise is not doing that, and proving it.
- **Stack:** Java 21, Spring Boot, raw WebSocket (not STOMP — the sync protocol itself is the point, it shouldn't be hidden behind a framework abstraction), PostgreSQL, React + HTML5 Canvas for the frontend, Docker.

---

## 1. Domain Primer — CRDTs, HLCs, and Why Naive Sync Fails

Read this before writing any merge logic. Get this wrong and nothing downstream is trustworthy.

### 1.1 The failure mode this project exists to fix

Two users, A and B, both connected to the same board. A moves a rectangle. At nearly the same instant, B changes that same rectangle's color. Naive whole-document last-write-wins: whichever update reaches the server microseconds later **overwrites the entire board state**, silently discarding the other user's change — not just the conflicting field, everything. This is the default behavior of the simplest possible sync approach, and it's the thing every other design decision in this document exists to avoid.

### 1.2 Hybrid Logical Clock (HLC) — a total order across replicas with no coordination

A plain timestamp can't reliably order events from different machines (clock skew), and a plain counter can't relate to wall-clock time (useful for the history scrub bar in Section 10). An HLC is `(physicalTime: long, logicalCounter: int, replicaId: UUID)`:

- **On a local event:** `physicalTime = max(lastLocalPhysicalTime, currentWallClockMillis)`. If that's unchanged from the last event, increment `logicalCounter`; otherwise reset it to 0.
- **On receiving a remote HLC:** `physicalTime = max(lastLocalPhysicalTime, remote.physicalTime, currentWallClockMillis)`. The `logicalCounter` update depends on which of the three was the maximum — the standard HLC merge rule (implement this precisely; a subtly wrong merge breaks the total order guarantee everything else depends on).
- **Comparison (total order):** compare `physicalTime`, then `logicalCounter`, then `replicaId` as the final deterministic tiebreaker. The `replicaId` tiebreak matters — without it, two replicas can produce genuinely identical `(physicalTime, logicalCounter)` pairs and have no way to agree on an order.

Every replica that has seen the same set of operations, applied in **any** order, must reach the identical HLC-based ordering. That's the property Phase 3's convergence test verifies.

### 1.3 Per-field Last-Write-Wins registers, not whole-document LWW

Each board element's fields (`x`, `y`, `width`, `height`, `color`, `strokeWidth`, `text`) are independent LWW-registers: `(value, hlc)`. On receiving a remote update for a field, adopt the remote value only if `remote.hlc` is greater (per 1.2's total order) than the field's current `hlc`; otherwise keep the local value. Because this is per-field, A moving a rectangle and B recoloring it **both survive** — they touch different registers. Only a genuine same-field concurrent conflict (both dragging the same corner at once) picks a winner, deterministically, on every replica.

### 1.4 Element existence: remove-wins, tombstones, no resurrection

Elements are add/remove via a simplified OR-Set: an element has a `createdHlc` and an optional `removedHlc`. **Once a remove operation is observed, the element is permanently gone for that replica — full stop.** A concurrent field-edit operation to an already-removed element is still stored in the operation log (needed for the Section 10 history scrub bar) but does **not** resurrect the element on the live board. This is a deliberate simplification over full undelete-aware OR-Set semantics: it's the behavior users actually expect ("I deleted it, it's deleted"), and it removes an entire category of confusing edge cases from both the implementation and the interview explanation.

### 1.5 Freeform strokes are immutable once created

A pen stroke is drawn by one user in one continuous gesture. Rather than making stroke *points* individually mergeable (real complexity for approximately zero real-world benefit — nobody co-draws the same stroke simultaneously), a `FREEFORM_STROKE` element's `points` field is set once at creation and never updated; only its existence (remove) and non-geometry fields (color, if you allow recoloring a stroke after the fact) participate in the per-field merge. State this scoping decision explicitly in the README — it's a real, defensible engineering judgment call, not an oversight.

---

## 2. Architecture

```mermaid
flowchart LR
  A["Client A<br/>(React + Canvas)"] <-->|"WebSocket: ops + presence"| SRV["Weave Server<br/>(Spring Boot)"]
  B["Client B"] <-->|"WebSocket"| SRV
  SRV --> LOG[("Operation Log<br/>(Postgres, append-only)")]
  SRV --> SNAP[("Board Snapshots<br/>(Postgres, compacted)")]
  SRV -.->|"Phase 4: multi-instance"| REDIS[("Redis Pub/Sub")]
```

Single Spring Boot service for Phases 1–3 (this is deliberately not split into microservices — nothing here needs an independent failure boundary yet; Phase 4 adds horizontal scaling via Redis, still one deployable service, just N instances of it).

| Component | Responsibility |
|---|---|
| CRDT core | Element/field merge logic, HLC, operation application — pure Java, no framework dependency, fully unit-testable in isolation |
| WebSocket layer | Per-board session management, op broadcast, presence relay, reconnect/replay protocol |
| Operation log | Append-only source of truth. Live board state is a **projection** over it — same instinct as Loadline's event-sourced shipment state, applied here because it's what makes convergence provable and time-travel free, not because it's a pattern to repeat for its own sake |
| Snapshot store | Periodic compaction of the log so new/reconnecting clients don't replay a board's entire history |

---

## 3. Repository Layout

```text
weave/
  server/
    src/main/java/com/vaibhav/weave/
      crdt/              Element, FieldRegister, HLC, OperationApplier — no Spring dependency
      websocket/         WeaveWebSocketHandler, session registry, protocol (de)serialization
      board/             Board, BoardController (REST), snapshot compaction job
      persistence/        Flyway migrations, repositories
      auth/              Signed board-edit tokens
    src/test/java/com/vaibhav/weave/
      crdt/              unit tests + the randomized convergence property test
      websocket/          multi-client integration tests
  client/                React app
    src/
      canvas/            drawing surface (native Canvas 2D), tool handling
      crdt/              client-side mirror of the merge logic (TypeScript port — same semantics, needed for optimistic local application)
      presence/
      history/           the time-travel scrub bar
  infra/
    docker/
```

---

## 4. Data Model

| Entity | Key fields |
|---|---|
| `Board` | id, name, createdAt, editToken (signed, expiring) |
| `Operation` (append-only log) | opId, boardId, type (`ELEMENT_CREATED`/`FIELD_UPDATED`/`ELEMENT_REMOVED`), elementId, elementType (create only), field (field-update only), value, hlc (physicalTime, logicalCounter, replicaId), sequenceNumber (server-assigned, monotonic per board) |
| `Element` (**projection**, not source of truth) | id, boardId, type, fields (map: fieldName → {value, hlc}), createdHlc, removedHlc (nullable) |
| `BoardSnapshot` | boardId, sequenceNumberAtSnapshot, compactedState (JSONB), createdAt |

Presence (cursor position, selection, display name/color) is **never persisted** — broadcast-only, ephemeral by nature, no merge semantics needed since the newest position is simply the newest position.

---

## 5. Phase 1 — MVP: the CRDT core and a single-user canvas

**Goal:** get the correctness engine right before any networking exists. This is the phase to spend real care on — everything else is comparatively mechanical once this is right.

### Tasks
1. Implement `HybridLogicalClock`: local tick, remote merge, and the total-order comparator from Section 1.2.
2. Implement the per-field LWW register (`FieldRegister<T>`: value + hlc, with a `mergeWith(remote)` method) and the `Element` model composing multiple registers plus `createdHlc`/`removedHlc`.
3. Implement `OperationApplier`: takes a stream of `Operation`s in **any order** and produces the resulting board state, using the merge rules from Section 1.
4. In-memory single-board state for now (no persistence, no networking) — this phase proves the core is correct in isolation.
5. React canvas: rectangle, ellipse, freeform pen, text tools; select/move/resize; color picker. All local-only, applying operations to the same `OperationApplier` logic ported to TypeScript.
6. **Unit tests, written now, not deferred:** apply a hand-picked sequence of concurrent operations in multiple different orders and assert identical final state for each ordering.

### Acceptance criteria
- A test that creates two conflicting field-updates to the same element with out-of-order delivery still converges to the HLC-later value, regardless of the order the test feeds them in.
- The canvas is usable single-player: draw shapes, move them, recolor them, undo via remove.

---

## 6. Phase 2 — Real-time multi-user sync

**Goal:** two browsers, same board, live.

### Tasks
1. `WeaveWebSocketHandler` (Spring's raw `WebSocketHandler`, not STOMP): per-board session registry, broadcasts each incoming `Operation` to every other session on the same board.
2. Client sends operations as they're generated locally (optimistic — apply immediately to local state, then send); receives and applies remote operations through the same `OperationApplier` used in Phase 1.
3. Persist every operation to the `operations` table (Postgres) as it's received — this is now the durable source of truth, not just an in-memory convenience.
4. `POST /api/v1/boards` creates a board and returns its id plus a signed, expiring edit token. `GET /api/v1/boards/{id}/snapshot` returns current state for a client's initial load.
5. Presence channel: a separate lightweight message type (`PRESENCE`) broadcasting cursor position, selection, display name, and color — never written to the operation log.
6. **Reconnection protocol:** client tracks the last `sequenceNumber` it has applied. On reconnect, it sends `{type: "SYNC_REQUEST", sinceSequence: N}`; server replays every operation with a higher sequence number. Because merge order doesn't matter (Section 1), this just works — that's the actual payoff of doing the CRDT work in Phase 1.

### Acceptance criteria
- Two browser windows editing concurrently converge to identical state, verified by comparing rendered output, not just assumed.
- Kill one client's network mid-edit (browser devtools offline mode), keep drawing locally, reconnect — the board ends up fully synced with nothing lost on either side.

---

## 7. Phase 3 — Prove it: the naive-vs-CRDT comparison and the convergence test

**Goal:** turn "it should be correct" into "here's the test that proves it," and build the side-by-side demo that makes the difference undeniable.

### Tasks
1. Implement a deliberately naive `NaiveBoardSync` mode: whole-board state replaced wholesale by whichever client's full snapshot arrives last at the server — the thing Section 1.1 describes. This is a real, runnable mode, not a description in a README.
2. **Randomized convergence property test:** generate a random sequence of concurrent operations across N simulated replicas (aim for 1,000+ randomized cases in CI), apply them in a different shuffled order per replica, and assert every replica reaches bit-identical final state. This is the single most important test in the project — it's the one that would actually catch a subtly wrong HLC merge or a field-register bug that hand-written unit tests would miss.
3. A specific, named test reproducing Section 1.1's exact scenario: two replicas, one moves an element, the other recolors the same element concurrently — assert both changes survive under Weave's CRDT, and separately assert one is lost under `NaiveBoardSync` (yes, write a test that asserts the naive version is broken — that's the evidence for the "before" half of your demo).
4. Document the merge semantics precisely (Section 1's rules, written into `docs/crdt-semantics.md`) — this is the artifact you'll actually reference defending the design under interview questioning.

### Acceptance criteria
- The convergence property test passes at 1,000+ random iterations, reliably, not just once.
- Running the same concurrent-edit scenario against `NaiveBoardSync` and against the real CRDT path in the same test suite produces different (and correctly labeled) outcomes.

---

## 8. Phase 4 — Hardening: scale, log compaction, access control

**Goal:** the parts that turn a correct demo into something that could plausibly run for real.

### Tasks
1. **Horizontal scaling:** Redis pub/sub so multiple server instances can each hold WebSocket connections for the same board and still relay operations to every connected client regardless of which instance they're attached to. Modest, real distributed-systems signal — don't over-build this into a bigger project than it needs to be.
2. **Snapshot compaction:** a background job that periodically collapses a board's operation log into a `BoardSnapshot` plus only the operations since that snapshot — so a new client joining a board with 50,000 historical operations doesn't replay all 50,000, it loads the snapshot and a short tail. This is the same category of concern real CRDT systems (Automerge, Yjs) have to solve, applied at an appropriately small scale here.
3. **Access control:** signed, expiring per-board edit tokens (link-based access, matching how real whiteboard tools actually work — "anyone with this link can edit" — rather than building a full user/auth system that doesn't fit this product's actual usage pattern). Keep it honest in the README: this is link-based access control, not per-user permissions, and that's a deliberate scope decision, not a gap you didn't notice.
4. **Basic abuse protection:** a per-connection operation-rate cap on the WebSocket handler, rejecting a client that's clearly flooding (bug or malicious), with a clear close-reason sent back.

### Acceptance criteria
- Two server instances behind a load balancer, clients connected to different instances, still converge correctly — verified by an integration test that actually spins up two instances.
- Loading a board with a large synthetic operation history is measurably faster after compaction than replaying the full log.

---

## 9. Phase 5 — Polish, load testing, deployment

**Goal:** the parts that make the demo feel finished, plus honest numbers instead of adjectives.

### Tasks
1. UI polish: toolbar, shape style panel, live cursor labels (name + color), a presence avatar list, connection-status indicator (online/offline/reconnecting/synced — make the reconnection story visible, not just functionally correct).
2. **History scrub bar:** since the full operation log already exists, expose a slider that replays the board's state at any point in its history — a genuinely nice feature that falls out of the architecture almost for free, worth highlighting as such.
3. The naive-vs-CRDT comparison from Phase 3, wired into the actual deployed app as a clearly labeled demo toggle — this is your best 30 seconds of recruiter attention.
4. Export board to PNG/SVG.
5. **Load test:** N simulated concurrent clients against a single board, measure operation broadcast latency and time-to-convergence as N grows. Report real numbers, not adjectives — "handles 40 concurrent editors at p95 broadcast latency under 80ms" beats "scalable" every time.
6. Docker Compose for local dev; deploy to a free tier (Render/Fly.io/Oracle, same disciplined budgeting as your other projects).

### Acceptance criteria
- Full test suite (unit, convergence property test, multi-instance integration test) green in CI.
- Load test results are real, recorded, and in the README — not estimated.

---

## 10. Phase 6 — Top-Tier Hardening: Adversarial Correctness, External Benchmarks, Live-Stakes Proof

**Goal:** Phases 1–5 produce a genuinely correct, demoable project. This phase is what separates "a correct CRDT" from "a CRDT whose correctness, performance, and security posture are all independently checkable" — each addition below exists because it preempts a specific question a skeptical senior engineer would actually ask, not because it sounds impressive. Treat this phase as the layer you reach for once Phases 1–5 are solid, not a blocker to a working demo.

### Tasks

1. **Tombstone garbage collection with causal stability.** The operation log currently only grows — every deleted element's tombstone lives forever. Track each connected replica's last-acknowledged `sequenceNumber` (already available from Phase 2). A tombstone becomes eligible for garbage collection only once every currently-known replica has acknowledged an operation with a higher sequence number than the tombstone's — i.e. no replica can still be holding a stale view that needs it. Add a timeout/eviction policy for replicas that disconnect and never return, so one abandoned client doesn't block GC forever. This is the part CRDT tutorials almost always skip, precisely because it's fiddly.

2. **Defend against a malicious client.** Nothing currently stops a client from forging an HLC with `physicalTime` set far in the future to guarantee its edits always win LWW comparisons. Add server-side HLC validation: reject or clamp any incoming HLC whose `physicalTime` exceeds the server's own wall clock by more than a defined tolerance (accounting for legitimate clock skew). Write a test that specifically simulates a forged far-future HLC and asserts it's rejected/clamped, not silently trusted. Feed repeated forgery attempts from one replica into Phase 4's abuse protection as a flagged signal.

3. **Benchmark against Yjs and Automerge on a real editing trace.** Replay the `automerge-perf` dataset (~260,000 real keystroke operations from an actual document-editing session — the same trace the CRDT research community itself benchmarks against) through Weave's merge engine. Measure throughput, memory, and merge time, and report these numbers in the README next to published Yjs/Automerge figures on the same trace. This is externally checkable, not self-reported — the single highest-leverage item in this phase.

4. **JMH microbenchmarks for the merge hot path, and virtual threads for the WebSocket layer.** Write proper JMH (Java Microbenchmark Harness) benchmarks for field-register merging, HLC comparison, and operation application — done correctly, accounting for JIT warm-up and avoiding the dead-code-elimination pitfalls naive `System.currentTimeMillis()` timing falls into. Separately, back each WebSocket connection with a Java 21 virtual thread instead of a platform thread, and re-run Phase 5's concurrent-editor load test to report the improved connection-count ceiling.

5. **Chaos-test the network in CI, not just by hand.** Integrate Toxiproxy (or a lightweight custom proxy) between test clients and the server inside the WebSocket integration tests. Inject latency, jitter, and hard partitions on command, and assert reconnection and convergence still hold — as an automated CI assertion every run makes, not a manual devtools demo you did once.

6. **Live join demo via QR code.** Generate a QR code linking to a fresh edit-token URL for the currently open board — Phase 2's signed edit tokens already support this. Surface it prominently in the UI as a one-click invite. Near-zero engineering cost, the highest-impact demo moment on this list: the interviewer edits the board with you, live, from their own phone.

**Optional ceiling, not core scope:** a short TLA+ specification of Section 1's merge semantics, model-checked for the convergence property — the same technique AWS and MongoDB use to verify distributed protocols. A real time investment to learn properly; worth knowing it's the genuine top of this ladder if you want to reach for it, but don't let it block shipping everything above.

### Acceptance criteria
- Tombstones for elements removed and acknowledged by all replicas are actually purged on the next compaction pass — verified by comparing log/snapshot size before and after a synthetic replica-disconnect-and-reconnect scenario.
- A forged far-future HLC from a test client is rejected or clamped, verified by a dedicated test, and does not win a concurrent LWW comparison it shouldn't.
- The automerge-perf trace replay completes and produces a documented throughput/memory/merge-time comparison table against published Yjs/Automerge numbers in the README.
- JMH benchmark results are committed to the repo, not run once and discarded, and the virtual-thread load test shows a measurably higher concurrent-connection ceiling than Phase 5's platform-thread baseline.
- The chaos-test suite (latency/jitter/partition injection) passes in CI, asserting convergence under induced network failure.
- Scanning the QR code from a phone lands on the board with a valid edit token and no manual entry required.

---

## 11. Dashboard / UX Specification

- **Toolbar:** select, rectangle, ellipse, freeform pen, text, sticky note, color picker, stroke width.
- **Live cursors:** each connected user's cursor rendered with their name and an assigned color, moving in real time via the presence channel.
- **Presence list:** small avatar row showing who's currently on the board.
- **Connection status:** a small, honest indicator — online / offline (editing locally, queued) / reconnecting / synced — this is not decorative, it's the visible half of the reconnection protocol working correctly.
- **History scrub bar:** drag to see the board's state at any earlier point.
- **Naive vs. CRDT demo toggle:** clearly labeled, switches the active sync mode so the before/after comparison is a live feature of the app, not just a claim in the README.

Keep the visual design plain and functional — a whiteboard's UI should get out of the way of the content on it; the impressive part here is correctness under real conditions, not visual flourish.

---

## 12. README & Portfolio Packaging

- Problem statement: lead with Section 1.1's failure mode and the fact that it's provably fixed, not just handled — most portfolios claim correctness, this one has a 1,000-iteration property test backing the claim.
- Explicitly connect the event-sourced "live state is a projection over an append-only log" instinct to Loadline's shipment timeline — same architectural judgment, applied for a different reason (convergence + time-travel here, audit trail there) — worth stating once as a pattern you reach for deliberately.
- Suggested resume bullets:
  - "Implemented a CRDT-based real-time collaborative editor from scratch, using per-field LWW-registers and hybrid logical clocks to guarantee convergence regardless of network delivery order."
  - "Proved correctness with a randomized property test validating convergence across simulated replicas under 1,000+ random concurrent operation orderings."
  - "Built and shipped a deliberately naive comparison implementation to make the correctness improvement concretely demonstrable, not just claimed."
  - "<measured p95 broadcast latency> with <measured concurrent editor count> connected clients per board."
  - "Benchmarked the merge engine against Yjs and Automerge on a real ~260K-operation editing trace, with results published alongside the libraries' own reported numbers."
  - "Hardened the protocol against a forged-clock attack on the LWW conflict resolution, and validated reconnection/convergence under chaos-injected network partitions in CI."

---

## 13. How to Use This Document

Phase 1 is the one to get exactly right — everything else is UI and infrastructure around a correctness engine that either works or doesn't. Feed Section 1 plus each phase to your coding agent in order, and don't move past Phase 1 until the convergence test is genuinely passing at scale (hundreds of random iterations), not just on the hand-picked example. That test is the artifact that makes this project defensible under real questioning, and it's worth the extra care before building anything on top of it.

Phases 1–5 are the core project — finish and ship those first; a working, correct, well-demoed whiteboard is already a strong portfolio piece on its own. Phase 6 is what pushes it from strong to genuinely top-tier, but treat its six items as independent additions you can pick up in any order once the core is solid, not a single monolithic phase that has to be finished all at once — the QR-code live-join (item 6) is a fifteen-minute addition with outsized demo payoff, while the Yjs/Automerge benchmark (item 3) is worth doing properly rather than rushing, since it's the one external reviewers can actually check against published numbers themselves.