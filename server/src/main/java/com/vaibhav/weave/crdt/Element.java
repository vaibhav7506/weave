package com.vaibhav.weave.crdt;
import java.util.*;
import com.vaibhav.weave.crdt.HybridLogicalClock.Timestamp;
public record Element(UUID id, UUID boardId, Kind type, Map<String,FieldRegister<Object>> fields,
                      Timestamp createdHlc, Timestamp removedHlc) {
    public enum Kind { RECTANGLE, ELLIPSE, FREEFORM_STROKE, TEXT }
    public Element { fields = Collections.unmodifiableMap(new TreeMap<>(fields)); }
    public boolean visible() { return createdHlc != null && removedHlc == null; }
}
