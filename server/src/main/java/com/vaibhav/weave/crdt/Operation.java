package com.vaibhav.weave.crdt;
import java.util.*;
import com.vaibhav.weave.crdt.HybridLogicalClock.Timestamp;
public record Operation(UUID opId, UUID boardId, UUID elementId, Timestamp hlc, Type type,
                        Element.Kind elementType, Map<String,Object> fields, String field, Object value) {
    public enum Type { ELEMENT_CREATED, FIELD_UPDATED, ELEMENT_REMOVED }
    public static final Set<String> FIELDS = Set.of("x","y","width","height","color","strokeWidth","text","points");
    public Operation {
        Objects.requireNonNull(opId); Objects.requireNonNull(boardId); Objects.requireNonNull(elementId);
        Objects.requireNonNull(hlc); Objects.requireNonNull(type);
        if(type == Type.ELEMENT_CREATED) Objects.requireNonNull(elementType);
        if(type == Type.FIELD_UPDATED && (!FIELDS.contains(field) || field.equals("points"))) throw new IllegalArgumentException("Invalid or immutable field");
        var copy = new TreeMap<String,Object>();
        if(fields != null) fields.forEach((k,v)-> { if(!FIELDS.contains(k)) throw new IllegalArgumentException("Unknown field"); copy.put(k,freeze(v)); });
        fields = Collections.unmodifiableMap(copy);
        if(type == Type.FIELD_UPDATED) value = freeze(value);
    }
    private static Object freeze(Object v) {
        if(v instanceof List<?> list) return list.stream().map(Operation::freeze).toList();
        if(v instanceof String || v instanceof Number) return v;
        throw new IllegalArgumentException("Unsupported field value");
    }
}
