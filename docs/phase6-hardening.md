# Phase 6: adversarial correctness and measurements



## Replica acknowledgments and deletion collection



Clients acknowledge a contiguous applied sequence, once per second when it advances. A PostgreSQL lease records the highest acknowledgment for each connection; heartbeats renew it. Disconnects retain their lease for 300 seconds (`WEAVE_REPLICA_LEASE_SECONDS`). Expired replicas are evicted on collection. Under the same board row lock used by appends, collection requires every unexpired lease to acknowledge a sequence strictly greater than the latest removal of that element.



Each compaction pass considers snapshots with tombstones and new deletion operations, even below the normal 1,000-operation compaction threshold. It removes stable element registers and tombstones from the active snapshot, archives its log prefix, and increments a GC generation. Reconnecting clients include their generation and receive a replacement snapshot when needed, even at the same sequence. A client ahead of the saved snapshot receives a fresh projection through the current sequence; its cursor never moves backward.



A permanent, small `(board, element ID, removal sequence)` fence rejects edits to retired identities. This is required because disconnected replicas may retain arbitrarily old outboxes. Invalid queued edits receive `RETIRED_ELEMENT` and a current snapshot; unrelated queued creations survive. Old creation acknowledgments below the snapshot floor cannot resurrect shapes.



**Storage boundary:** live snapshots and the active log shrink. Minimal retired-ID fences and the full history archive remain. Total historical storage is not bounded: deleting that archive would conflict with Phase 5's complete history and audit requirements. No claim of reclaiming all historical bytes is made.



`stableCollectionWaitsForStrictAckAndEvictsDisconnectedReplica` verifies blocking by a lagging lease, equality versus strictly higher ACK, eviction, smaller snapshot bytes, empty active log, preserved history, a same-sequence reconnect, stale edit rejection, and a reconnect ahead of the compacted sequence. Raw size evidence: `phase6-gc.json`.



## Clock defense and connection isolation



Incoming operations more than 30 seconds ahead of server time are rejected before persistence (`WEAVE_CLOCK_TOLERANCE_MS`). Three future-clock violations close that connection with code 4008. A snapshot repairs the optimistic client view, and the browser halts a clock-skewed session with an explicit error. Boundary and real-WebSocket tests establish that forged writes never enter the log or win a register; a healthy editor continues working after the attacker is closed.



Each connection has one Java 21 virtual worker, a 256-message queue and a 1 MiB queued-payload limit. Overflow uses the existing abuse close code. Board serialization uses `ReentrantLock`, avoiding an application monitor held over blocking database/Redis work. Closing a connection wakes its worker without interrupting a broadcast to another editor. `WEAVE_VIRTUAL_THREADS=false` selects platform workers for controlled experiments. This experimental mode is not identical to Phase 5's Tomcat dispatch pool. Virtual threads do not eliminate per-board database serialization, transport limits, or JVM/library pinning.



## Real network chaos in every CI run



`tcpLatencyJitterPartitionAndReconnectConverge` places a custom TCP proxy between a real WebSocket client and the actual Spring server. Both byte streams receive 15–50 ms seeded delay/jitter. A hard partition closes both sockets; another editor commits while the disconnected replica retains an unsent move. Reconnection fetches missed operations, replays the outbox and retries an acknowledged operation. Both replicas and PostgreSQL must match, including the independently expected position and color. This test runs in the ordinary Maven suite for both CI seeds.



## JMH microbenchmarks



Java 21.0.1, Windows 11, Ryzen 7 4800H, 16 logical processors. JMH 1.37: two isolated JVM forks, three 1-second warm-up iterations and five 1-second measurement iterations per fork, one thread, 256–512 MiB heap. Results are returned to JMH for consumption; register and clock operands vary through seeded state. Operation application uses a bounded 1,024-operation batch over 64 shapes, including board allocation and normal dedup/log maintenance. Operations themselves are prepared outside timing. Reported errors are JMH's 99.9% confidence intervals, not production latency bounds.



| Work | Mean | Error | Unit |

| --- | ---: | ---: | --- |

| Apply operation | 1,538.34 | ±63.94 | ns/op |

| Compare HLC | 8.99 | ±0.31 | ns/op |

| Merge field register | 11.03 | ±0.49 | ns/op |



Raw results: [phase6-jmh.json](phase6-jmh.json). Reproduce from the repository root:



```sh

mvn -f benchmarks/pom.xml package

java -jar benchmarks/target/benchmarks.jar -rf json -rff docs/phase6-jmh.json

```



## Replaying the community editing trace



The downloaded trace is pinned to [`automerge-perf` revision da212e9](https://github.com/automerge/automerge-perf/tree/da212e984c777d31ee7d888f82637288aa4c61d3/edit-by-index), attributed to Martin Kleppmann. Its JavaScript file SHA-256 is `23116070719243e2950c310006dd50b3d80744be7542a86c62e7d5c2ed7326d0`. `fetch-trace.py` verifies the hash and parses only JSON literals; it does not execute downloaded JavaScript. The dataset is downloaded into ignored `.tools/trace`, not redistributed in this repository. See the upstream [license](https://github.com/automerge/automerge-perf/blob/da212e984c777d31ee7d888f82637288aa4c61d3/LICENSE).



The adapter maps each inserted character to a TEXT element and each deletion to an element removal. Trace indexes maintain an external order list. This exercises Weave's merge engine on 259,778 actual character edits, but **does not implement a concurrent text sequence CRDT**. Ordering is supplied by the trace and is excluded from the merge measurement. Adapter preparation took 1,335 ms and is separately recorded. Full snapshot equality after shuffled delivery and exact final text equality are asserted.



Two complete warm-up rounds precede three measured rounds in one JVM. Replay includes operation application, visible-content extraction and equality verification. Shuffled merge times application alone, with shuffle and verification outside timing. Heap is the post-GC delta for the projection and dedup index, excluding prebuilt operation objects, trace, order list and second replica; it is not total process memory. Raw results: [phase6-trace.json](phase6-trace.json).



Published figures below are from the first versioned B4 table in [`crdt-benchmarks` revision 796f702](https://github.com/dmonad/crdt-benchmarks/tree/796f70250c8c003dfb3a50369d0b2733a760e54d). That host used an i5-8400 and Node 20.5.0. Workload semantics, machines, runtimes, encoding and memory accounting differ. These are reference figures, not a head-to-head speedup claim.



| Implementation | Replay ms | Derived edits/s | Reported retained memory | Additional merge/load metric |

| --- | ---: | ---: | --- | --- |

| Weave character-element adapter, local median | 1,875.55 | 138,508 | 54.7 MB additional engine heap | Shuffled operation application: 1,776.86 ms |

| Published Yjs 13.6.11 | 5,714 | 45,464 | 3.2 MB JS heap | Encoded document parse: 39 ms |

| Published Automerge 2.1.10 | 14,326 | 18,133 | Table reports 0 B; WASM memory excluded, so not a usable total | Encoded document parse: 1,805 ms |



Weave's shuffled merge and upstream parse-time columns measure different operations. Weave does not implement the upstream compact binary codec, so no equivalent encoded-load claim is made.



```sh

python scripts/fetch-trace.py

mvn -f benchmarks/pom.xml package

java -Xms256m -Xmx2g -cp benchmarks/target/benchmarks.jar com.vaibhav.weave.bench.TraceReplay .tools/trace/trace.json docs/phase6-trace.json

```



## Signed QR invite



**Invite to board** requests a newly issued token from authenticated `POST /api/v1/boards/{id}/invites`, then generates the QR entirely in the browser. No third-party QR service receives the token. The token remains in the URL fragment. The dialog includes copyable text, keyboard dismissal and a local-preview explanation. The favicon is served with the packaged frontend.



`npm run test:phase6` decodes the actual displayed QR pixels, opens that URL in a separate phone-sized touch browser, verifies authenticated joining and cross-client edits, checks the favicon response and checks mobile overflow. This verifies the software path; a physical phone scan requires the eventual public deployment.


## Virtual-worker concurrent-editor rerun

The original Phase 5 workload was repeated on the same Windows host and local PostgreSQL/Redis installations with five field edits/second/editor, 15 measured edits/editor, and three rounds at each size. The run extends to 80 editors. All 15 rounds converged to identical client and PostgreSQL state. Raw evidence: [phase6-load.json](phase6-load.json). These are separate runs, not a controlled A/B isolation of virtual threads; Phase 6 also adds retired-ID checks and replica leases.

| Editors | Phase 5 median round p95, ms | Phase 6 median round p95, ms | Every round converged |
| --- | ---: | ---: | --- |
| 5 | 54.97 | 74.76 | Yes |
| 10 | 101.56 | 104.17 | Yes |
| 20 | 147.06 | 147.44 | Yes |
| 40 | 1,958.48 | 2,690.78 | Yes |
| 80 | Not measured | 12,725.24 | Yes |

**The requested higher low-latency connection ceiling was not demonstrated.** Both runs remain below 200 ms median-of-round p95 through 20 active editors; 40 produces backlog. At 80, Phase 6 converges but has approximately 12.7 seconds p95 latency. Eighty connections is a tested count, not an established ceiling or an improvement over an unmeasured Phase 5 count. Virtual workers are implemented; they do not remove serialized durable writes, per-board locking or all-to-all fan-out. Claiming a higher ceiling from these results would be false. Sustained capacity improvement needs a separately benchmarked batching/backpressure design and hosted measurements.
