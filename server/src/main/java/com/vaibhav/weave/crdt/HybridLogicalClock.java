package com.vaibhav.weave.crdt;

import java.util.UUID;
import java.util.function.LongSupplier;

public final class HybridLogicalClock {
    public record Timestamp(long physicalTime, int logicalCounter, UUID replicaId) implements Comparable<Timestamp> {
        public Timestamp {
            if (physicalTime < 0 || physicalTime > 9_007_199_254_740_991L || logicalCounter < 0 || replicaId == null)
                throw new IllegalArgumentException("Invalid clock timestamp");
        }
        public int compareTo(Timestamp other) {
            int c = Long.compare(physicalTime, other.physicalTime);
            if (c == 0) c = Integer.compare(logicalCounter, other.logicalCounter);
            // UUID.compareTo uses signed longs; canonical string order matches TypeScript.
            return c == 0 ? replicaId.toString().compareTo(other.replicaId.toString()) : c;
        }
    }
    private final UUID replicaId;
    private final LongSupplier wallClock;
    private long physical;
    private int logical;
    public HybridLogicalClock(UUID replicaId) { this(replicaId, System::currentTimeMillis); }
    public HybridLogicalClock(UUID replicaId, LongSupplier wallClock) { this.replicaId = replicaId; this.wallClock = wallClock; }
    public Timestamp tick() { return advance(null); }
    public Timestamp merge(Timestamp remote) { return advance(remote); }
    private synchronized Timestamp advance(Timestamp remote) {
        long p = Math.max(Math.max(physical, wallClock.getAsLong()), remote == null ? 0 : remote.physicalTime());
        int l;
        if (remote != null && p == physical && p == remote.physicalTime()) l = Math.incrementExact(Math.max(logical, remote.logicalCounter()));
        else if (p == physical) l = Math.incrementExact(logical);
        else if (remote != null && p == remote.physicalTime()) l = Math.incrementExact(remote.logicalCounter());
        else l = 0;
        Timestamp result = new Timestamp(p, l, replicaId);
        physical = p; logical = l;
        return result;
    }
}
