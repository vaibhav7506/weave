# Phase 4: multiple instances, compaction and access boundaries

## Redis relay and recovery

Phase 5 subsequently optimized local fan-out: an already-committed next operation is delivered directly, and catch-up reads are shared for equal client cursors. The recovery and compaction guarantees below remain in effect. See [Phase 5](phase5-polish-and-load.md).

Each server subscribes to a configurable Redis Pub/Sub channel. After committing an operation to PostgreSQL, the writer catches up its local clients and publishes a board notification. Other instances read the committed database suffix and deliver it to their own clients in sequence order. Redis messages do not determine field winners or allocate sequence numbers. PostgreSQL's board-row lock serializes sequence allocation across processes.

[Redis Pub/Sub delivery is at-most-once](https://redis.io/docs/latest/develop/pubsub/). The implementation therefore also scans connected rooms every two seconds by default. A missed notification, including a process dying between commit and publication, is recovered from PostgreSQL without requiring the user to disconnect. The integration suite deliberately commits an operation without publishing and invokes this recovery path. The dedicated multi-instance relay test makes the scheduled scan interval ten minutes, so its eight-second delivery assertions actually require Redis.

Per-client server cursors and a local room lock avoid gaps between replay and registration. A database commit racing registration is caught by the queued Redis notification or the next scan. Notifications may be duplicated or reordered; they only trigger reads of the committed suffix. An old operation retry is acknowledged to its sender with its original sequence, including after compaction, without adding another durable row. It is not rebroadcast to unrelated clients that have already received it.

Presence is relayed over the same channel, never written to PostgreSQL. IDs include an instance UUID to avoid collisions between servlet session IDs. Joining clients request a refresh from other instances. Client heartbeats renew remote presence; entries not refreshed for 35 seconds expire, so an abruptly killed instance does not leave permanent ghost cursors. Redis unavailability degrades live presence; database reconciliation still recovers edits. Redis must be available for initial listener startup unless `WEAVE_REDIS_ENABLED=false` is set. Use Redis-enabled instances together for the supported scaling mode.

This phase uses Spring's [RedisMessageListenerContainer](https://docs.spring.io/spring-data/redis/reference/3.5/redis/pubsub.html). Redis and PostgreSQL must be trusted infrastructure, not browser-accessible endpoints. All instances share the database, signing secret, Redis channel and origin configuration. Configure the load balancer to forward WebSocket upgrades and preserve the client's Origin. Each upgraded connection stays attached to one backend; reconnection may reach another.

## Compaction without losing history

Flyway V2 adds `board_snapshots` and `operations_archive`. Every minute, the compactor selects up to 50 boards with at least 1,000 active operations since the previous snapshot. Both interval and threshold are configurable.

For a board, one database transaction acquires its row lock, restores any existing snapshot, applies the active suffix, writes the new full snapshot, copies that prefix into the archive, and removes the copied rows from the active table. The snapshot retains all register clocks, immutable stroke points, incomplete element records and tombstones. Appends and other compactors take the same board lock. Snapshot/replay readers hold a shared row lock while reading their consistent snapshot and suffix. The concurrency regression races repeated compaction with 100 new appends and verifies the contiguous full log and exact resulting state.

The logical operation log is now the union of active and archived rows. The archive preserves exact payloads for idempotent retries and the later history feature. Compaction reduces replay work and the active log; it does **not** reduce total retained history storage. Tombstone garbage collection remains Phase 6. The original operation payloads are never rewritten.

`GET /api/v1/boards/{id}/snapshot` uses the saved snapshot plus its short active suffix. A reconnect cursor older than the snapshot receives:

```json
{"type":"SYNC_SNAPSHOT","snapshot":{"boardId":"UUID","name":"Board","sequenceNumber":1000,"elements":[]}}
```

The shown empty `elements` is just a schema example; the actual message includes all merge metadata. The server follows it with newer `OPERATION` messages and the usual `SYNC_COMPLETE` on initial/reconnected join. A live client that falls behind compaction can also receive a snapshot during catch-up.

The client replaces its confirmed projection in the same board object, advances its received prefix to the snapshot boundary, then reapplies every pending local operation. It observes snapshot clocks before authoring future edits. A late acknowledgment for an archived operation clears that pending item without moving the sequence cursor backward. The client regression verifies pending creations survive snapshot replacement and offline edits cannot resurrect a deleted element.

## Measured acceptance result

Local run on 2026-09-08: Windows, Java 21.0.1, PostgreSQL 18.1 over loopback. Fixture: one rectangle with 49,999 subsequent x-field updates, 50,000 operations total. Each path was warmed once and measured three times with `System.nanoTime`; the table reports medians. Every measured snapshot was checked for exact equality.

| Load path | Median time |
| --- | ---: |
| Replay all 50,000 operations | 414.22 ms |
| Load compacted snapshot | 2.98 ms |

Afterward: zero active operations, 50,000 archived operations, identical projected state. The fixture measures snapshot-load improvement for a long edit history with a small resulting board; it does not measure throughput, network latency, memory use or a board containing 50,000 visible elements. This is an integration measurement, not a JMH microbenchmark or a deployment capacity claim. The original numeric result is in [phase4-compaction.json](phase4-compaction.json). Subsequent test runs write their own measurements to `server/target/compaction-result.json`.

## Link access and operation limits

Access remains **link-based**, with no user accounts or per-user roles. An HMAC-SHA256 edit token binds a board ID to its expiry. All server instances must use the same stable signing secret. Lifetime defaults to seven days and is configurable. Missing, forged, expired or wrong-board credentials fail snapshot access and joining; each operation is board-bound and expiry-checked. Periodic scans also disconnect expired idle sessions. An unauthenticated socket has ten seconds to send a valid join message. Allowed browser origins default to Weave's dedicated local port, 5187. A token's validity is not evidence that its holder is an honest replica; forged HLC validation remains Phase 6.

Each authenticated connection has a monotonic-time token bucket: 100 operations/second, capacity 200 by default. Every submitted operation, including retries, consumes capacity. A flood closes that connection with code `4008` and the reason `Operation rate exceeded; queued edits retained`. Other connections continue operating. The client reports `Rate limited`, preserves pending edits, and stops automatically reconnecting. Its regular outgoing queue is paced at 50 operations/second to fit the default cap, including reconnect backlogs. Changing the server limit below that requires a corresponding client pacing change.

Writes to one socket are serialized through a Spring session decorator with send-time and buffer limits. This is basic per-connection protection; it is not a per-account quota or protection against an attacker repeatedly reconnecting. There is no public compaction endpoint for untrusted clients to repeatedly trigger expensive work.

## Run and verify

From the repository root on the current Windows environment:

```powershell
./scripts/start-db.ps1
./scripts/start-redis.ps1
./scripts/maven.ps1 test
# Stop a running server before packaging its jar on Windows.
./scripts/maven.ps1 -MavenArgs @('package','-DskipTests')
./scripts/start-server.ps1
```

In another terminal, run `npm run dev` from `client`. Open `http://127.0.0.1:5187/`. The Redis helper uses the already-installed Windows executable on isolated loopback port 56379, with persistence disabled because it holds only relay traffic. That local installation reports Redis 3.0.504; CI is configured with Redis 7 and PostgreSQL 17. Local execution does not constitute a completed GitHub CI run.

| Setting | Default |
| --- | --- |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `56379` |
| `REDIS_PASSWORD` | Empty for the isolated local instance |
| `WEAVE_REDIS_CHANNEL` | `weave:boards:v1` |
| `WEAVE_REDIS_ENABLED` | `true` |
| `WEAVE_SYNC_SWEEP_MS` | `2000` |
| `WEAVE_COMPACTION_THRESHOLD` | `1000` |
| `WEAVE_COMPACTION_INTERVAL_MS` | `60000` |
| `WEAVE_OPERATIONS_PER_SECOND` | `100` |
| `WEAVE_OPERATION_BURST` | `200` |
| `WEAVE_TOKEN_LIFETIME_SECONDS` | `604800` |

`HardeningIntegrationTest` starts two independent Spring application contexts with separate listening ports against shared PostgreSQL/Redis. Its TCP load balancer routes the two WebSocket clients to different backends and asserts that routing. Tests cover cross-instance edits/presence, stale reconnects after compaction, archived duplicate acknowledgments, missed-notification recovery, flood isolation, idle expiry, forgery, retained tombstones/orphans, compaction racing appends, and the 50,000-operation measurement. Existing convergence, comparison, HTTP, and browser suites remain part of CI. The client suite additionally checks snapshot replacement while offline edits are pending.
