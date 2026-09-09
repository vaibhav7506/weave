package com.vaibhav.weave.comparison;

import com.vaibhav.weave.auth.EditTokens;
import com.vaibhav.weave.crdt.Element;
import com.vaibhav.weave.persistence.BoardStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.*;

/** Runnable comparison transport. Demo state is isolated from the durable CRDT log. */
@RestController
@RequestMapping("/api/v1/boards/{id}/comparison/naive")
public class NaiveComparisonController {
    private final Map<UUID, NaiveBoardSync> demos = new ConcurrentHashMap<>();
    private final BoardStore boards;
    private final EditTokens tokens;
    public NaiveComparisonController(BoardStore boards, EditTokens tokens) { this.boards=boards; this.tokens=tokens; }
    public record Replacement(List<Element> elements) {}
    private NaiveBoardSync demo(UUID id, String auth) {
        tokens.authorize(id,auth);
        return demos.computeIfAbsent(id,key->new NaiveBoardSync(key,boards.snapshot(key).elements()));
    }
    @GetMapping public NaiveBoardSync.State get(@PathVariable UUID id, @RequestHeader(value="Authorization",required=false) String auth) {
        return demo(id,auth).snapshot();
    }
    @PutMapping public NaiveBoardSync.State replace(@PathVariable UUID id, @RequestHeader(value="Authorization",required=false) String auth, @RequestBody Replacement request) {
        return demo(id,auth).replaceWith(request.elements());
    }
}
