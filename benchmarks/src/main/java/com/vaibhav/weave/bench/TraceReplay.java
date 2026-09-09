package com.vaibhav.weave.bench;
import com.vaibhav.weave.crdt.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
public class TraceReplay {
    static final UUID BOARD=new UUID(0,1),REPLICA=new UUID(0,2);
    static final ObjectMapper JSON=new ObjectMapper();
    static volatile Object retained;
    record Adapted(List<Operation> operations,List<UUID> order,String expected){}
    static long heap()throws Exception{System.gc();Thread.sleep(200);return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    static Adapted adapt(JsonNode trace){
        var operations=new ArrayList<Operation>();var order=new ArrayList<UUID>();long n=0;
        for(var edit:trace.path("edits")){
            int index=edit.get(0).asInt(),deletions=edit.get(1).asInt();
            for(int j=0;j<deletions;j++){var id=order.remove(index);operations.add(new Operation(new UUID(1,++n),BOARD,id,new HybridLogicalClock.Timestamp(n,0,REPLICA),Operation.Type.ELEMENT_REMOVED,null,Map.of(),null,null));}
            if(edit.size()>2){String inserted=edit.get(2).asText();for(int j=0;j<inserted.length();j++){
                var id=new UUID(2,++n);order.add(index+j,id);operations.add(new Operation(new UUID(1,n),BOARD,id,new HybridLogicalClock.Timestamp(n,0,REPLICA),Operation.Type.ELEMENT_CREATED,Element.Kind.TEXT,Map.of("text",inserted.substring(j,j+1)),null,null));
            }}
        }
        return new Adapted(operations,order,trace.path("finalText").asText());
    }
    static String content(OperationApplier board,List<UUID> order){
        var visible=new HashMap<UUID,Element>();for(var e:board.live())visible.put(e.id(),e);
        if(visible.size()!=order.size())throw new AssertionError("Visible character count differs");
        var text=new StringBuilder();for(var id:order)text.append(visible.get(id).fields().get("text").value());return text.toString();
    }
    public static void main(String[] args)throws Exception{
        JsonNode trace=JSON.readTree(Path.of(args[0]).toFile());long start=System.nanoTime();var adapted=adapt(trace);trace=null;
        double adapterMs=(System.nanoTime()-start)/1e6;var rounds=new ArrayList<Map<String,Object>>();
        // Two full warm-up replays, then three measured rounds. Trace/adapter allocations precede heap baseline.
        for(int round=-2;round<3;round++){
            retained=null;long before=heap();start=System.nanoTime();var board=new OperationApplier(BOARD);
            for(var op:adapted.operations())board.apply(op);
            if(!content(board,adapted.order()).equals(adapted.expected()))throw new AssertionError("Final text mismatch");
            double replayMs=(System.nanoTime()-start)/1e6;retained=board;long memory=heap()-before;
            var shuffled=new ArrayList<>(adapted.operations());Collections.shuffle(shuffled,new Random(73129));
            start=System.nanoTime();var merged=new OperationApplier(BOARD);for(var op:shuffled)merged.apply(op);
            double mergeMs=(System.nanoTime()-start)/1e6;
            if(!board.snapshot().equals(merged.snapshot())||!content(merged,adapted.order()).equals(adapted.expected()))throw new AssertionError("Shuffled merge mismatch");
            if(round>=0)rounds.add(Map.of("round",round+1,"replayMs",replayMs,"operationsPerSecond",adapted.operations().size()*1000/replayMs,"retainedEngineHeapBytes",memory,"shuffledMergeMs",mergeMs));
            System.out.println("Round "+round+" verified");
            board=null;merged=null;shuffled=null;
        }
        var result=Map.of("traceOperations",adapted.operations().size(),"finalCharacters",adapted.expected().length(),"adapterMs",adapterMs,"rounds",rounds,"java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"processors",Runtime.getRuntime().availableProcessors(),"method","One character per TEXT element; trace-index ordering supplied externally. Prebuilt operations excluded from retained heap; no wire encoding, DB or sequence CRDT.");
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),result);
    }
}
