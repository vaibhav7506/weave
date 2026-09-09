package com.vaibhav.weave.board;
import com.vaibhav.weave.persistence.BoardStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class SnapshotCompactor {
    private final BoardStore store; private final int threshold;
    public SnapshotCompactor(BoardStore store,@Value("${weave.compaction.threshold}") int threshold){this.store=store;this.threshold=threshold;if(threshold<1)throw new IllegalArgumentException("Compaction threshold must be positive");}
    @Scheduled(fixedDelayString="${weave.compaction.interval-ms}",initialDelayString="${weave.compaction.interval-ms}")
    public void compactEligible(){
        for(var board:store.compactionCandidates(threshold))store.compact(board);
        for(var board:store.collectionCandidates())store.collectStable(board);
    }
}
