package com.vaibhav.weave.websocket;

import com.vaibhav.weave.crdt.Operation;
import java.util.function.LongSupplier;

/** Rejects forged future clocks before they enter the durable log or other replicas. */
public final class ClockGuard {
    private final long toleranceMillis;
    private final LongSupplier now;
    public ClockGuard(long toleranceMillis) { this(toleranceMillis,System::currentTimeMillis); }
    public ClockGuard(long toleranceMillis,LongSupplier now) {
        if(toleranceMillis<0||toleranceMillis>300_000)throw new IllegalArgumentException("Clock tolerance must be 0–300000 ms");
        this.toleranceMillis=toleranceMillis;this.now=now;
    }
    public boolean accepts(Operation operation) {
        return operation.hlc().physicalTime()-now.getAsLong()<=toleranceMillis;
    }
}
