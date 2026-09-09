package com.vaibhav.weave.crdt;
import java.util.*;
public final class OperationApplier {
    private final UUID boardId;
    private final Map<UUID,Element> elements = new TreeMap<>(Comparator.comparing(UUID::toString));
    private final Map<UUID,Operation> log = new LinkedHashMap<>();
    public OperationApplier(UUID boardId) { this.boardId = boardId; }
    public static OperationApplier fromSnapshot(UUID boardId, List<Element> snapshot) {
        var projection = new OperationApplier(boardId);
        for (var element : snapshot) {
            if (!boardId.equals(element.boardId())) throw new IllegalArgumentException("Wrong board in snapshot");
            projection.elements.put(element.id(), element);
        }
        return projection;
    }
    public void apply(Operation op) {
        if(!boardId.equals(op.boardId())) throw new IllegalArgumentException("Wrong board");
        if(log.putIfAbsent(op.opId(), op) != null) return;
        var e = elements.getOrDefault(op.elementId(), new Element(op.elementId(), boardId, null, Map.of(), null, null));
        var fields = new TreeMap<>(e.fields());
        var created = e.createdHlc(); var removed = e.removedHlc(); var kind = e.type();
        switch(op.type()) {
            case ELEMENT_REMOVED -> { if(removed == null || op.hlc().compareTo(removed)>0) removed = op.hlc(); }
            case ELEMENT_CREATED -> {
                if(created == null || op.hlc().compareTo(created)<0) {
                    created = op.hlc(); kind = op.elementType(); fields.remove("points");
                    if(kind == Element.Kind.FREEFORM_STROKE && op.fields().containsKey("points")) fields.put("points", new FieldRegister<>(op.fields().get("points"),op.hlc()));
                }
                op.fields().forEach((k,v)-> { if(!k.equals("points")) fields.merge(k,new FieldRegister<>(v,op.hlc()),FieldRegister::mergeWith); });
            }
            case FIELD_UPDATED -> fields.merge(op.field(),new FieldRegister<>(op.value(),op.hlc()),FieldRegister::mergeWith);
        }
        elements.put(e.id(),new Element(e.id(),boardId,kind,fields,created,removed));
    }
    public List<Element> snapshot() { return List.copyOf(elements.values()); }
    public List<Element> live() { return snapshot().stream().filter(Element::visible).toList(); }
    public List<Operation> operations() { return List.copyOf(log.values()); }
}
