package com.vaibhav.weave.comparison;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConcurrentMoveAndRecolorTest {
    @Test void concurrentMoveAndRecolor_bothSurviveCrdt_butNaiveLosesTheFirstEdit() {
        var results=ComparisonDemo.run();
        for(var row:results) {
            if(row.mode().equals("CRDT")) {assertEquals(80,row.x());assertEquals("#e06c48",row.color());assertEquals("BOTH SURVIVE",row.outcome());}
            else if(row.delivery().equals("A then B")){assertEquals(0,row.x());assertEquals("#e06c48",row.color());assertEquals("LOST MOVE",row.outcome());}
            else {assertEquals(80,row.x());assertEquals("#425eeb",row.color());assertEquals("LOST COLOR",row.outcome());}
        }
        assertEquals(4,results.size());
    }
    @Test void retryingAStaleSnapshotIsDestructiveInNaiveMode() {
        var s=ComparisonDemo.scenario();var initial=ComparisonDemo.project(s.creation());
        var naive=new NaiveBoardSync(ComparisonDemo.BOARD,initial);
        naive.replaceWith(ComparisonDemo.project(s.creation(),s.move(),s.recolor()));
        naive.replaceWith(initial);
        assertEquals(initial,naive.snapshot().elements());assertEquals(2,naive.snapshot().arrivalNumber());
        assertThrows(UnsupportedOperationException.class,()->naive.snapshot().elements().clear());
    }
}
