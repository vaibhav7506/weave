package com.vaibhav.weave.comparison;

import com.vaibhav.weave.crdt.*;
import java.util.*;

/** A repeatable Section 1.1 scenario; also executable without a server or dependencies. */
public final class ComparisonDemo {
    public static final UUID BOARD = new UUID(0,1), SHAPE = new UUID(0,2);
    public record Result(String mode, String delivery, Object x, Object color, String outcome) {}
    public record Scenario(Operation creation, Operation move, Operation recolor) {}
    public static Scenario scenario() {
        var a=new HybridLogicalClock(new UUID(0,10),()->1000);
        var b=new HybridLogicalClock(new UUID(0,20),()->1000);
        var creation=new Operation(new UUID(0,100),BOARD,SHAPE,a.tick(),Operation.Type.ELEMENT_CREATED,Element.Kind.RECTANGLE,Map.of("x",0,"y",0,"color","#425eeb"),null,null);
        b.merge(creation.hlc());
        // Neither editor sees the other's edit. Each only sees the original rectangle.
        var move=new Operation(new UUID(0,101),BOARD,SHAPE,a.tick(),Operation.Type.FIELD_UPDATED,null,Map.of(),"x",80);
        var recolor=new Operation(new UUID(0,102),BOARD,SHAPE,b.tick(),Operation.Type.FIELD_UPDATED,null,Map.of(),"color","#e06c48");
        return new Scenario(creation,move,recolor);
    }
    public static List<Element> project(Operation... operations) {
        var board=new OperationApplier(BOARD);
        for(var op:operations)board.apply(op);
        return board.snapshot();
    }
    public static List<Result> run() {
        var s=scenario();
        var a=project(s.creation(),s.move());
        var b=project(s.creation(),s.recolor());
        var results=new ArrayList<Result>();
        for(boolean aFirst:List.of(true,false)) {
            String order=aFirst?"A then B":"B then A";
            var naive=new NaiveBoardSync(BOARD,project(s.creation()));
            naive.replaceWith(aFirst?a:b);naive.replaceWith(aFirst?b:a);
            results.add(result("NAIVE",order,naive.snapshot().elements(),aFirst?"LOST MOVE":"LOST COLOR"));
            var crdt=project(s.creation(),aFirst?s.move():s.recolor(),aFirst?s.recolor():s.move());
            results.add(result("CRDT",order,crdt,"BOTH SURVIVE"));
        }
        return List.copyOf(results);
    }
    private static Result result(String mode,String delivery,List<Element> board,String outcome) {
        var fields=board.getFirst().fields();return new Result(mode,delivery,fields.get("x").value(),fields.get("color").value(),outcome);
    }
    public static void main(String[] args) {
        System.out.printf("%-8s %-14s %-8s %-12s %s%n","MODE","DELIVERY","X","COLOR","OUTCOME");
        for(var row:run())System.out.printf("%-8s %-14s %-8s %-12s %s%n",row.mode(),row.delivery(),row.x(),row.color(),row.outcome());
    }
}
