package com.vaibhav.weave.websocket;

import com.vaibhav.weave.crdt.Operation;
import java.util.List;

/** Structural protocol checks, independent of the later clock-skew/rate-limit hardening. */
public final class OperationValidation {
    private OperationValidation() {}
    public static void validate(Operation op) {
        if(op.type()==Operation.Type.ELEMENT_CREATED) {
            op.fields().forEach(OperationValidation::field);
            if(op.fields().containsKey("points") && op.elementType()!=com.vaibhav.weave.crdt.Element.Kind.FREEFORM_STROKE)throw new IllegalArgumentException("Only strokes have points");
        }
        if(op.type()==Operation.Type.FIELD_UPDATED)field(op.field(),op.value());
    }
    private static void field(String name,Object value) {
        switch(name) {
            case "color" -> {if(!(value instanceof String s)||!s.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("Invalid color");}
            case "text" -> {if(!(value instanceof String s)||s.length()>10000)throw new IllegalArgumentException("Text is too long");}
            case "points" -> {
                if(!(value instanceof List<?> points)||points.size()>20000)throw new IllegalArgumentException("Invalid points");
                for(var point:points) {
                    if(!(point instanceof List<?> xy)||xy.size()!=2)throw new IllegalArgumentException("Invalid point");
                    for(var coordinate:xy)number(coordinate,0,1);
                }
            }
            case "width","height" -> number(value,0,1_000_000);
            case "strokeWidth" -> number(value,0.1,100);
            case "x","y" -> number(value,-1_000_000,1_000_000);
            default -> throw new IllegalArgumentException("Unknown field");
        }
    }
    private static void number(Object value,double min,double max) {
        if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException("Invalid numeric value");
    }
}
