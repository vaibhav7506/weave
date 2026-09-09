package com.vaibhav.weave.websocket;
import com.vaibhav.weave.crdt.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClockGuardTest {
    @Test void toleranceBoundaryIsInclusive(){
        var guard=new ClockGuard(30000,()->100000L);
        for(long time:new long[]{99999,130000,130001}){
            var op=new Operation(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new HybridLogicalClock.Timestamp(time,0,UUID.randomUUID()),Operation.Type.ELEMENT_REMOVED,null,Map.of(),null,null);
            assertEquals(time<=130000,guard.accepts(op));
        }
    }
}
