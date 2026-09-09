# Phase 2 wire protocol

Historical Phase 2 design. [Phase 4](phase4-hardening.md) supersedes the single-instance relay, full-log replay, expiry checks, and unpaced outbox behavior described below.

Transport: raw text WebSocket at `/ws`, one JSON object per message. The Vite development proxy forwards `/api` and `/ws` to the Spring service on `127.0.0.1:8080`. The service uses Spring's raw WebSocket handler, with no STOMP or CRDT library.

## Board links and initial load

`POST /api/v1/boards` with `{ "name": "My board" }` returns HTTP 201 and `{id, name, editToken}`. Tokens are HMAC-SHA256 signed, bound to a board UUID, and expire after seven days. Keep `WEAVE_TOKEN_SECRET` stable across restarts; changing it invalidates existing links. Links carry the token in the URL fragment (`?board=UUID#token=TOKEN`), so it is not part of HTTP URLs or ordinary server access logs. Anyone holding the complete link can edit; there are no user accounts or per-user permissions.

`GET /api/v1/boards/{id}/snapshot` requires `Authorization: Bearer TOKEN`. It returns `{boardId, name, sequenceNumber, elements}`. Elements include registers, clocks, pending-before-create records and tombstones, including removed elements. The client must restore register methods after parsing JSON; it must not hydrate from visible shapes alone. The endpoint reconstructs a committed prefix of the operation log. Snapshot storage/compaction remains Phase 4.

## Join/reconnect

The socket starts unjoined. Its first message is:

```json
{"type":"SYNC_REQUEST","boardId":"UUID","editToken":"TOKEN","sinceSequence":0}
```

Use the snapshot's sequence number on initial load and the last contiguous applied sequence on reconnect. Under a per-board lock the server captures the committed sequence, replays all later operations in ascending sequence order, sends current presence records, sends `SYNC_COMPLETE`, and registers the socket for live delivery. Operation appends use that same lock. This prevents a gap between replay and live broadcast. This synchronization is valid for one server instance; Redis fan-out is Phase 4.

```json
{"type":"SYNC_COMPLETE","sequenceNumber":12,"sessionId":"ephemeral-id"}
```

## Durable edits

```json
{"type":"OPERATION","operation":{"opId":"UUID","boardId":"UUID","elementId":"UUID","hlc":{"physicalTime":100,"logicalCounter":0,"replicaId":"UUID"},"type":"FIELD_UPDATED","field":"x","value":42}}
```

Creation operations carry `elementType` and `fields`; removes carry neither. Java's nullable, irrelevant fields may be omitted by the TypeScript sender. Server structural validation checks UUIDs, operation kind, field names, value types and bounds, stroke-point immutability, and board identity.

The server locks the board's database row, deduplicates `(boardId, opId)`, allocates the next sequence, appends JSONB and advances the board sequence in one transaction. A contradictory payload with an existing op ID is rejected. Exact retries return their original sequence number without inserting a new row. App code only inserts into the operation log; no update or delete endpoint exists.

After the transaction commits, every joined session **including the sender** receives:

```json
{"type":"OPERATION","sequenceNumber":13,"operation":{"...":"original operation"}}
```

This is both broadcast and durable acknowledgment. Clients remove that op from the pending queue, merge its HLC, and apply it idempotently. The cursor only advances across a contiguous committed prefix, never to the largest sequence received. An old duplicate acknowledgment can clear a pending op without moving the cursor backward. If a server dies after commit but before acknowledgment, the client retries and the database returns the original operation.

## Offline edits

Clients apply edits optimistically and retain every unacknowledged operation in an outbox. The outbox is copied to per-tab `sessionStorage` so it survives a page reload. On reconnect, the client replays server history first, then flushes the outbox after `SYNC_COMPLETE`. A fresh page first loads a snapshot, restores pending operations, and merges all observed clocks before authoring more edits. Storage exhaustion is surfaced; the current tab retains its in-memory queue. Closing a tab before synchronization can lose its pending edits. Loading the entire app while offline is not supported in this phase.

A 10-second heartbeat (`PING`/`PONG`) detects a silent connection after 25 seconds; reconnect uses bounded exponential backoff. Browser offline/online events close/reopen the transport promptly. Status labels distinguish offline/queued, reconnecting, syncing, synced, expired link, and protocol errors. Token errors (close 4001) and malformed messages (4002) stop retries and preserve pending data for diagnosis; transient transport failures retry.

## Presence

```json
{"type":"PRESENCE","presence":{"name":"Alice","color":"#425eeb","x":120,"y":80,"selection":"element-UUID-or-empty"}}
```

The server adds its own `sessionId` and relays presence to other sessions in the board. It broadcasts `PRESENCE_LEFT` on disconnect. Cursor updates are throttled to at most 20/second; leaving the canvas bypasses the throttle to hide the cursor promptly. Presence never enters a transaction or the operation table. Joining clients receive current presence; disconnected clients clear their peer list. Names are temporary guest labels in this phase.

## Verification

`SyncIntegrationTest` runs a real Spring server on a random port against PostgreSQL, using the isolated `weave_phase2_test` schema. It covers REST authorization, expiration, isolation, commit-before-ack, duplicate replay, conflicting-ID rejection, invalid-field rejection, concurrent edits, replay, presence removal/non-persistence, fresh projection rebuild, and joining during active broadcasts.

`client/tests/sync.e2e.mjs` opens two independent Chromium contexts against the actual app and compares PNG-encoded canvas pixels. It checks initial broadcast, simultaneous move/color changes, editing in both clients through a network partition, reconnect, snapshot reload, and editing after hydration. Local selections and ephemeral cursors are cleared before comparing shared board pixels. Test output goes to the ignored `client/test-results/` directory.

Framework reference: [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

