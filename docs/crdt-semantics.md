# CRDT merge semantics

This is the contract implemented by `server/.../crdt` and `client/src/crdt`. The live board is a projection over operations, not the durable source of truth. PostgreSQL stores accepted operations; sequence numbers support delivery and replay, while HLCs resolve field conflicts. These orders serve different purposes.

## Valid operations and assumptions

An operation has an immutable operation ID, board ID, element ID, HLC, and one of three payloads:

| Type | Payload | Effect |
| --- | --- | --- |
| `ELEMENT_CREATED` | element type and initial field values | Supplies identity and candidates for initial registers |
| `FIELD_UPDATED` | one mutable field and value | Supplies one register candidate |
| `ELEMENT_REMOVED` | no field payload | Supplies a permanent tombstone |

A replica ticks once for every authored operation, never reuses its replica ID with a reset clock, and does not assign the same HLC to contradictory events. IDs are canonical lowercase UUIDs on the wire. Exact retransmission is allowed; contradictory payloads under the same operation ID are rejected by the Phase 2 server. The pure core assumes valid, noncontradictory input. In particular, different operation IDs carrying the same HLC and different values are outside the convergence contract; Phase 2 does not enforce that HLC uniqueness against malicious clients. A signed edit token establishes access, not trustworthiness of a client's clock.

Values are finite numbers, strings, or immutable point arrays. Wire validation applies field-specific types and bounds. Fields are `x`, `y`, `width`, `height`, `color`, `strokeWidth`, `text`, and creation-only `points`. Element types are rectangle, ellipse, freeform stroke, and text. A normal application always uses a fresh element ID for a new shape.

## Hybrid logical clock

A timestamp is `(p, l, r)`: physical milliseconds, a logical counter, and a replica UUID. Java uses `long` and `int`; interoperable physical time is restricted to `0..2^53-1`, and the logical counter to `0..2^31-1`. Initial clock state is `(0,0)`.

For a local event, with previous state `(p,l)` and wall-clock reading `w`:

```text
p' = max(p, w)
l' = l + 1, if p' = p
     0,     otherwise
```

For reception of a remote timestamp `(pr,lr,rr)`:

```text
p' = max(p, pr, w)
l' = max(l,lr) + 1, if p' = p and p' = pr
     l + 1,         if p' = p only
     lr + 1,        if p' = pr only
     0,             otherwise (wall time strictly dominates)
```

The returned timestamp always uses the local replica ID. The first branch takes precedence when physical times tie, including ties involving wall time. Receiving an event advances the clock even if that operation is an exact replay. Every next local tick follows the previous local event; a remote merge follows both the previous local event and the received event. Clock rollback cannot move the logical clock backward. Overflow fails explicitly instead of wrapping.

Compare physical time first, logical counter second, and the canonical UUID string last, using lexicographic ascending order. Java intentionally does **not** use `UUID.compareTo`, whose signed-long ordering differs from the TypeScript string order for UUIDs with their high bit set.

This supplies a deterministic total order on valid event timestamps and extends observed causality. It does not recover true wall-clock ordering across independent machines, and it is not a vector clock that detects concurrency. Two replicas receiving the same operations in different orders can end with different **local clock states**. The guarantee is that their comparisons of immutable operation timestamps agree, so their board projections converge. Delivery-shuffle tests alone would not verify correct HLC generation; the suite also checks local/remote advancement and the exact merge branches.

## Mutable fields: independent LWW registers

A register is `(value, hlc)`. Its merge is the candidate with the greatest HLC; equal timestamps keep the existing candidate. Under the valid-input contract, an equal timestamp represents the same event/value, so equality is harmless.

For any element `e` and mutable field `f`, collect all candidates supplied by creates and field updates in the observed operation set `S`:

```text
register(e,f,S) = arg max HLC over all candidates for (e,f)
```

No candidate means that field is absent. Registers are independent: a new `x` value cannot replace `color`. Position changes currently issue separate `x` and `y` operations, so moving a shape is not an atomic multi-field transaction. Intermediate renders can show one coordinate before the other arrives; eventual convergence still holds.

A field update received before its create is retained on an invisible placeholder. When creation arrives, its initial values compete by HLC with the retained updates. We do not blindly reinitialize the element. If a field update has an older timestamp than its creation's value, creation wins that field, as required by LWW.

## Existence and identity

For each observed element ID:

```text
createdHlc = earliest observed creation HLC, or absent
removedHlc = greatest observed removal HLC, or absent
visible = createdHlc is present AND removedHlc is absent
```

Any removal makes the element permanently invisible, even if it arrives before creation or has a timestamp older than creation. This is a simplified permanent-remove model, not a full observed-remove set with reusable tags or undelete semantics. Undo-by-removal deletes the most recently created surviving shape; restoring a deleted shape would require a fresh ID.

Later edits to a removed element remain in the log and update its hidden registers. They cannot resurrect it. Snapshots include hidden records, incomplete placeholders, and tombstones; exporting only visible shapes is insufficient for reconnect state.

Reusing an element ID is discouraged. The defined defensive behavior chooses identity/type and immutable geometry from the earliest creation. All creation-supplied mutable field candidates still compete independently by greatest HLC. Equal creation HLCs with contradictory payloads remain outside the input contract.

## Strokes

A freeform gesture creates one immutable normalized `points` array. Only that array is immutable: moving and resizing change `x`, `y`, `width`, and `height`, which transform the array's rendered bounding box. Color and stroke width are also mutable. Weave does not support two editors merging point insertions within the same stroke. A `FIELD_UPDATED` for `points` is rejected. The points/type association follows the earliest creation and is never selected by delivery order.

## Why the projection converges

Under the input contract, each component is a deterministic reduction over the set of observed operations: maximum timestamp per mutable field, minimum creation timestamp for identity/points, maximum removal timestamp for a tombstone, and a visibility predicate over the latter two components.

Taking a maximum/minimum over the same candidates is associative, commutative, and idempotent. Composing these reductions preserves those properties. Therefore any delivery order, grouping, or exact duplication of the **same complete operation set** produces the same projection. Temporary divergence while replicas have different sets is expected. Eventual delivery is a separate networking obligation, exercised by the Phase 2 reconnect tests.

This is a reasoning argument under stated assumptions, supported by tests. Randomized finite tests are not an exhaustive formal proof, and final-state agreement alone would not prove a correct conflict policy. The property test therefore also compares against an independent set-reduction oracle. TLA+ model checking is not implemented.

The operation log's array order and server receipt timestamps need not be byte-identical across simulations. The comparison includes the full projected state: sorted element IDs, identity, field values and clocks, immutable points, pending placeholders, and tombstones. Java compares serialized snapshot bytes. TypeScript compares canonical JSON, normalizing Java's nullable optional metadata to match TypeScript's absent properties for cross-language checks.

## Naive comparison

`NaiveBoardSync.replaceWith(snapshot)` replaces the entire board with the latest submitted full snapshot. It deliberately has no field merge, HLC ordering, or retry deduplication. Server request serialization order determines the winner; even a stale retried snapshot replaces newer content.

The exact named regression scenario starts with `(x=0, color=blue)`. A moves to `x=80` from that base while B recolors it orange from the same base:

| Delivery | Naive result | CRDT result |
| --- | --- | --- |
| A then B | `x=0, orange` — move lost | `x=80, orange` |
| B then A | `x=80, blue` — color lost | `x=80, orange` |

Run `./scripts/compare.ps1` for the Java comparison. No server or database is needed for that entry point.

The real server exposes an isolated demo mode at `GET`/`PUT /api/v1/boards/{id}/comparison/naive`, authorized by the same bearer edit token. The first request copies the current durable board projection. `PUT` accepts `{ "elements": [...] }` containing a complete snapshot and returns `{mode: "NAIVE_WHOLE_BOARD_LWW", arrivalNumber, elements}`. Two clients can GET the same base, alter separate fields, and PUT their complete snapshots to reproduce the loss over HTTP. GET returns the last accepted replacement. State is in-memory for this deliberately broken demo and resets after a server restart. It never changes the normal board's PostgreSQL log or WebSocket projection. The HTTP integration test exercises real requests and verifies this isolation.

This endpoint is a runnable comparison mode, not a new collaboration transport. Connecting the comparison to the deployed canvas toggle is explicitly Phase 5.

## Reproduce the evidence

```powershell
# Requires the Phase 2 PostgreSQL setup for integration tests:
./scripts/maven.ps1 test
cd client
npm test
npm run test:parity
```

- Java default: 1,200 independent cases using 3–6 simulated replicas, 60–109 authored operations per case, multiple element types, orphan updates/removes, random skew/rollback, actual HLC generation/reception, duplicate deliveries, optimistic local application, shuffled final delivery, and a set-based expected result.
- TypeScript: 1,200 seeded cases with four shuffled receivers each, plus fixed clock/register/delete/hydration regressions.
- Cross-language: 24 Java-generated traces are replayed forward and backward through TypeScript and compared to the full Java oracle snapshots. Maven generates `server/target/convergence-cases.json`; `npm run test:parity` fails if it is missing instead of silently skipping.
- Named comparison tests assert both preserved CRDT changes and each direction of naive loss. A second regression checks destructive stale-snapshot retry.
- CI runs these checks, the PostgreSQL integration suite, the client build, and the two-browser reconnect regressions for two explicit Java seed ranges.

Java defaults to base seed `73129`. Override `-Dweave.seed` for another range and `-Dweave.cases` for a smaller debugging run. A projection mismatch writes the generated operation set, actual delivery order, expected state, and actual state to `server/target/convergence-failure.json`. Reproduce its reported seed with:

```powershell
./scripts/maven.ps1 -MavenArgs @('test','-Dtest=ConvergencePropertyTest','-Dweave.seed=REPORTED_SEED','-Dweave.cases=1')
```

## Boundaries still open

Phase 6 adds server-side future-clock rejection and causally stable collection of deleted element registers, with replica leases, GC generations and permanent retired-ID fences. The pure merge engine deliberately retains tombstones; collection belongs to the authenticated persistence/reconnect protocol. Complete history remains archived. See [Phase 6 hardening](phase6-hardening.md) for the boundary between algebraic convergence and operational collection.
