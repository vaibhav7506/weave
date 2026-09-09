package com.vaibhav.weave.bench;
import com.vaibhav.weave.crdt.*;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3,time=1) @Measurement(iterations=5,time=1) @Fork(value=2,jvmArgsAppend={"-Xms256m","-Xmx512m"})
@State(Scope.Thread)
public class MergeBenchmark {
    private static final UUID BOARD=new UUID(0,1),REPLICA=new UUID(0,2);
    private final HybridLogicalClock.Timestamp[] clocks=new HybridLogicalClock.Timestamp[1024];
    private final FieldRegister<Integer>[] registers=new FieldRegister[1024];
    private final Operation[] operations=new Operation[1024];
    private int cursor;
    @Setup public void prepare(){
        var random=new Random(73129);
        for(int i=0;i<1024;i++){
            clocks[i]=new HybridLogicalClock.Timestamp(random.nextInt(10),random.nextInt(10),new UUID(0,random.nextInt(10)));
            registers[i]=new FieldRegister<>(i,clocks[i]);
            operations[i]=new Operation(new UUID(1,i),BOARD,new UUID(2,i%64),new HybridLogicalClock.Timestamp(i,0,REPLICA),i<64?Operation.Type.ELEMENT_CREATED:Operation.Type.FIELD_UPDATED,i<64?Element.Kind.RECTANGLE:null,i<64?Map.of("x",i):Map.of(),i<64?null:"x",i<64?null:i);
        }
    }
    @Benchmark public int compareHlc(){int i=cursor++&1023;return clocks[i].compareTo(clocks[(i+1)&1023]);}
    @Benchmark public FieldRegister<Integer> mergeRegister(){int i=cursor++&1023;return registers[i].mergeWith(registers[(i+1)&1023]);}
    // A bounded batch avoids unbounded operation-log growth. Result consumption prevents DCE.
    @Benchmark @OperationsPerInvocation(1024) public OperationApplier applyOperations(){
        var board=new OperationApplier(BOARD);for(var op:operations)board.apply(op);return board;
    }
}
