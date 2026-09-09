package com.vaibhav.weave.comparison;

import com.vaibhav.weave.crdt.Element;
import java.util.*;

/** Intentionally incorrect sync: the last complete snapshot received replaces everything. */
public final class NaiveBoardSync {
    public record State(String mode, long arrivalNumber, List<Element> elements) {}
    private final UUID boardId;
    private List<Element> state;
    private long arrivals;
    public NaiveBoardSync(UUID boardId, List<Element> initial) {
        this.boardId = boardId;
        this.state = checked(initial);
    }
    public synchronized State replaceWith(List<Element> incoming) {
        state = checked(incoming); // Deliberately NO per-field merge, timestamp checks, or deduplication.
        arrivals++;
        return snapshot();
    }
    public synchronized State snapshot() { return new State("NAIVE_WHOLE_BOARD_LWW", arrivals, state); }
    private List<Element> checked(List<Element> incoming) {
        Objects.requireNonNull(incoming);
        var ids = new HashSet<UUID>();
        for (var element : incoming) {
            if (!boardId.equals(element.boardId()) || !ids.add(element.id()))
                throw new IllegalArgumentException("Wrong board or duplicate element ID");
        }
        return List.copyOf(incoming);
    }
}
