package com.vaibhav.weave.crdt;
import java.util.Objects;
import com.vaibhav.weave.crdt.HybridLogicalClock.Timestamp;
public record FieldRegister<T>(T value, Timestamp hlc) {
    public FieldRegister { Objects.requireNonNull(value); Objects.requireNonNull(hlc); }
    public FieldRegister<T> mergeWith(FieldRegister<T> remote) { return remote.hlc.compareTo(hlc) > 0 ? remote : this; }
}
