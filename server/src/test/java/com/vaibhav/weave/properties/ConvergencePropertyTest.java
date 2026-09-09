package com.vaibhav.weave.properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaibhav.weave.crdt.*;
import com.vaibhav.weave.crdt.HybridLogicalClock.Timestamp;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConvergencePropertyTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final UUID BOARD=new UUID(0,500);
    record Candidate(Object value,Timestamp stamp) {}
    record Trace(List<Operation> operations,List<OperationApplier> replicas) {}

    @Test void shuffledDuplicatedOperationsConvergeAcrossReplicasAndMatchTheSetOracle() throws Exception {
        long baseSeed=Long.getLong("weave.seed",73129L);
        int cases=Integer.getInteger("weave.cases",1200);
        var parityCases=new ArrayList<Map<String,Object>>();
        for(int iteration=0;iteration<cases;iteration++) {
            long seed=baseSeed+iteration;
            var random=new Random(seed);
            var trace=generate(random,seed);
            String expected=JSON.writeValueAsString(oracle(trace.operations()));
            for(int replica=0;replica<trace.replicas().size();replica++) {
                var delivery=new ArrayList<>(trace.operations());
                for(int repeat=0;repeat<15;repeat++)delivery.add(trace.operations().get(random.nextInt(trace.operations().size())));
                Collections.shuffle(delivery,random);
                var projection=trace.replicas().get(replica);
                for(var op:delivery)projection.apply(op);
                String actual=JSON.writeValueAsString(projection.snapshot());
                if(!expected.equals(actual)) {
                    Files.createDirectories(Path.of("target"));
                    JSON.writeValue(Path.of("target/convergence-failure.json").toFile(),Map.of("seed",seed,"replica",replica,"operations",trace.operations(),"delivery",delivery,"expected",JSON.readTree(expected),"actual",JSON.readTree(actual)));
                }
                assertEquals(expected,actual,"seed="+seed+", replica="+replica+"; trace: target/convergence-failure.json");
                for(var op:delivery)projection.apply(op);
                assertEquals(actual,JSON.writeValueAsString(projection.snapshot()),"Replay idempotency; seed="+seed);
                assertEquals(trace.operations().size(),projection.operations().size());
            }
            if(iteration<24)parityCases.add(Map.of("seed",seed,"boardId",BOARD,"operations",trace.operations(),"expected",JSON.readTree(expected)));
        }
        Files.createDirectories(Path.of("target"));
        JSON.writeValue(Path.of("target/convergence-cases.json").toFile(),parityCases);
        System.out.println("PASS: "+cases+" generated cases; 3-6 replicas; set oracle; real HLCs; seed range "+baseSeed+".."+(baseSeed+cases-1));
    }

    private Trace generate(Random random,long seed) {
        int n=3+random.nextInt(4);
        long[] walls=new long[n];Arrays.fill(walls,1000);
        var clocks=new ArrayList<HybridLogicalClock>();
        var replicas=new ArrayList<OperationApplier>();
        Timestamp[] last=new Timestamp[n];
        for(int i=0;i<n;i++){final int index=i;clocks.add(new HybridLogicalClock(new UUID(i%2==0?Long.MIN_VALUE:0,i+1),()->walls[index]));replicas.add(new OperationApplier(BOARD));}
        var operations=new ArrayList<Operation>();
        int events=60+random.nextInt(50);
        for(int event=0;event<events;event++) {
            int author=random.nextInt(n);
            walls[author]=Math.max(0,walls[author]+random.nextInt(101)-50);
            if(!operations.isEmpty()&&random.nextBoolean()) {
                var remote=operations.get(random.nextInt(operations.size()));
                Timestamp merged=clocks.get(author).merge(remote.hlc());
                assertTrue(merged.compareTo(remote.hlc())>0,"receive must follow remote event; seed="+seed);
                if(last[author]!=null)assertTrue(merged.compareTo(last[author])>0,"receive must follow local event; seed="+seed);
                last[author]=merged;replicas.get(author).apply(remote);
            }
            Timestamp stamp=clocks.get(author).tick();
            if(last[author]!=null)assertTrue(stamp.compareTo(last[author])>0,"local ticks must advance despite rollback; seed="+seed);
            last[author]=stamp;
            UUID element=new UUID(0,event<6?100+event:100+random.nextInt(8));
            Operation op;
            if(event<6) {
                var fields=new TreeMap<String,Object>(Map.of("x",event*10,"y",0,"width",100,"height",50,"color","#425eeb","strokeWidth",2));
                var kind=Element.Kind.values()[event%4];
                if(kind==Element.Kind.FREEFORM_STROKE)fields.put("points",List.of(List.of(0,0),List.of(0.5,1),List.of(1,0)));
                if(kind==Element.Kind.TEXT)fields.put("text","hello");
                op=new Operation(new UUID(0,event+1),BOARD,element,stamp,Operation.Type.ELEMENT_CREATED,kind,fields,null,null);
            } else if(event==6||random.nextInt(7)==0) {
                op=new Operation(new UUID(0,event+1),BOARD,element,stamp,Operation.Type.ELEMENT_REMOVED,null,Map.of(),null,null);
            } else {
                String field=List.of("x","y","width","height","color","text","strokeWidth").get(random.nextInt(7));
                Object value=switch(field){case "color"->random.nextBoolean()?"#e06c48":"#32a080";case "text"->"edit "+event;case "strokeWidth"->1+random.nextInt(8);default->random.nextInt(500);};
                op=new Operation(new UUID(0,event+1),BOARD,element,stamp,Operation.Type.FIELD_UPDATED,null,Map.of(),field,value);
            }
            operations.add(op);replicas.get(author).apply(op);
        }
        return new Trace(operations,replicas);
    }

    /** Set reduction, not another sequential OperationApplier: select maxima over all candidates. */
    private List<Element> oracle(List<Operation> operations) {
        var grouped=new TreeMap<UUID,List<Operation>>(Comparator.comparing(UUID::toString));
        operations.forEach(op->grouped.computeIfAbsent(op.elementId(),id->new ArrayList<>()).add(op));
        var result=new ArrayList<Element>();
        for(var entry:grouped.entrySet()) {
            var ops=entry.getValue();
            var creation=ops.stream().filter(op->op.type()==Operation.Type.ELEMENT_CREATED).min(Comparator.comparing(Operation::hlc)).orElse(null);
            var removed=ops.stream().filter(op->op.type()==Operation.Type.ELEMENT_REMOVED).map(Operation::hlc).max(Timestamp::compareTo).orElse(null);
            var fields=new TreeMap<String,FieldRegister<Object>>();
            for(String field:Operation.FIELDS) {
                if(field.equals("points"))continue;
                var candidates=new ArrayList<Candidate>();
                for(var op:ops) {
                    if(op.type()==Operation.Type.ELEMENT_CREATED&&op.fields().containsKey(field))candidates.add(new Candidate(op.fields().get(field),op.hlc()));
                    if(op.type()==Operation.Type.FIELD_UPDATED&&field.equals(op.field()))candidates.add(new Candidate(op.value(),op.hlc()));
                }
                candidates.stream().max(Comparator.comparing(Candidate::stamp)).ifPresent(c->fields.put(field,new FieldRegister<>(c.value(),c.stamp())));
            }
            if(creation!=null&&creation.elementType()==Element.Kind.FREEFORM_STROKE&&creation.fields().containsKey("points"))fields.put("points",new FieldRegister<>(creation.fields().get("points"),creation.hlc()));
            result.add(new Element(entry.getKey(),BOARD,creation==null?null:creation.elementType(),fields,creation==null?null:creation.hlc(),removed));
        }
        return result;
    }
}
