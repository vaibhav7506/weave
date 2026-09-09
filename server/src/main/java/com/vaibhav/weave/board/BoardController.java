package com.vaibhav.weave.board;
import com.vaibhav.weave.auth.EditTokens;
import com.vaibhav.weave.persistence.BoardStore;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/boards")
public class BoardController {
    private final BoardStore boards;
    private final EditTokens tokens;
    public BoardController(BoardStore boards, EditTokens tokens) {this.boards=boards;this.tokens=tokens;}
    public record CreateRequest(String name) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@RequestBody CreateRequest request) {
        String name=request.name()==null?"Untitled board":request.name().strip();
        if(name.isEmpty()||name.length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Name must contain 1–120 characters");
        var board=boards.create(name);
        return Map.of("id",board.id(),"name",board.name(),"editToken",tokens.issue(board.id()));
    }
    @PostMapping("/{id}/invites")
    public Map<String,String> invite(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false)String auth) {
        tokens.authorize(id,auth);boards.sequence(id);
        return Map.of("editToken",tokens.issue(id));
    }
    @GetMapping("/{id}/snapshot")
    public BoardStore.Snapshot snapshot(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false)String auth) {
        tokens.authorize(id,auth);return boards.snapshot(id);
    }
    @GetMapping("/{id}/history")
    public BoardStore.HistoryPage history(@PathVariable UUID id,
            @RequestHeader(value="Authorization",required=false)String auth,
            @RequestParam(defaultValue="0")long after,@RequestParam(required=false)Long through,
            @RequestParam(defaultValue="500")int limit) {
        tokens.authorize(id,auth);return boards.history(id,after,through,limit);
    }
}
