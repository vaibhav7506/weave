package com.vaibhav.weave.websocket;
import java.util.function.LongSupplier;
/** Per-connection token bucket based on monotonic time, independent of user-supplied HLCs. */
public final class OperationRateLimit {
    private final double rate,capacity; private final LongSupplier now; private double tokens; private long last;
    public OperationRateLimit(int rate,int capacity){this(rate,capacity,System::nanoTime);}
    public OperationRateLimit(int rate,int capacity,LongSupplier now){if(rate<1||capacity<1)throw new IllegalArgumentException("Invalid rate cap");this.rate=rate;this.capacity=capacity;this.tokens=capacity;this.now=now;last=now.getAsLong();}
    public synchronized boolean acquire(){long time=now.getAsLong();tokens=Math.min(capacity,tokens+Math.max(0,time-last)/1e9*rate);last=time;if(tokens<1)return false;tokens--;return true;}
}
