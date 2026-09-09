package com.vaibhav.weave.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaibhav.weave.crdt.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class BoardStore {
    public record Board(UUID id, String name) {}
    public record LoggedOperation(long sequenceNumber, Operation operation) {}
    public record Snapshot(UUID boardId, String name, long sequenceNumber, List<Element> elements,long gcVersion) {
        public Snapshot(UUID boardId,String name,long sequenceNumber,List<Element> elements){this(boardId,name,sequenceNumber,elements,0);}
    }
    public static class RetiredElementException extends IllegalArgumentException {}
    public record CollectionResult(int removed,int beforeBytes,int afterBytes,long gcVersion) {}
    public record Replay(Snapshot snapshot, List<LoggedOperation> operations, long through) {}
    public record HistoryPage(long through, long nextSequence, List<LoggedOperation> operations) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public BoardStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc=jdbc; this.transactions=transactions; this.json=json;
    }
    public Board create(String name) {
        var board=new Board(UUID.randomUUID(),name);
        jdbc.update("INSERT INTO boards(id,name) VALUES (?,?)",board.id(),board.name());return board;
    }
    public long sequence(UUID board) {return sequence(board,"");}
    private long sequence(UUID board,String lock) {
        var rows=jdbc.query("SELECT last_sequence FROM boards WHERE id=?"+lock,(r,n)->r.getLong(1),board);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Board not found");
        return rows.getFirst();
    }
    public LoggedOperation append(Operation op) {
        return transactions.execute(status->{
            long previous=sequence(op.boardId()," FOR UPDATE");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retired_elements WHERE board_id=? AND element_id=?)",Boolean.class,op.boardId(),op.elementId())))throw new RetiredElementException();
            var existing=jdbc.query("SELECT sequence_number,payload::text FROM operations WHERE board_id=? AND op_id=? UNION ALL SELECT sequence_number,payload::text FROM operations_archive WHERE board_id=? AND op_id=?",(r,n)->new LoggedOperation(r.getLong(1),decode(r.getString(2))),op.boardId(),op.opId(),op.boardId(),op.opId());
            if(!existing.isEmpty()) {
                if(!json.valueToTree(existing.getFirst().operation()).equals(json.valueToTree(op)))throw new IllegalArgumentException("Operation ID reused");
                return existing.getFirst();
            }
            long next=Math.incrementExact(previous);
            jdbc.update("INSERT INTO operations(board_id,op_id,sequence_number,payload) VALUES (?,?,?,?::jsonb)",op.boardId(),op.opId(),next,encode(op));
            jdbc.update("UPDATE boards SET last_sequence=? WHERE id=?",next,op.boardId());
            return new LoggedOperation(next,op);
        });
    }
    /** Full logical log, including archived operations, retained for audit/history and tests. */
    public HistoryPage history(UUID board,long after,Long requestedThrough,int limit) {
        long latest=sequence(board);
        long through=requestedThrough==null?latest:requestedThrough;
        if(after<0||through<after||through>latest||limit<1||limit>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid history range or page size");
        // One MVCC statement sees each row exactly once even while compaction moves it.
        var rows=jdbc.query("SELECT sequence_number,payload::text FROM (SELECT * FROM operations UNION ALL SELECT * FROM operations_archive) log WHERE board_id=? AND sequence_number>? AND sequence_number<=? ORDER BY sequence_number LIMIT ?",(r,n)->new LoggedOperation(r.getLong(1),decode(r.getString(2))),board,after,through,limit);
        return new HistoryPage(through,rows.isEmpty()?after:rows.getLast().sequenceNumber(),rows);
    }
    public List<LoggedOperation> since(UUID board,long since,long through) {
        return jdbc.query("SELECT sequence_number,payload::text FROM (SELECT * FROM operations UNION ALL SELECT * FROM operations_archive) log WHERE board_id=? AND sequence_number>? AND sequence_number<=? ORDER BY sequence_number",(r,n)->new LoggedOperation(r.getLong(1),decode(r.getString(2))),board,since,through);
    }
    private List<LoggedOperation> tail(UUID board,long since,long through) {
        return jdbc.query("SELECT sequence_number,payload::text FROM operations WHERE board_id=? AND sequence_number>? AND sequence_number<=? ORDER BY sequence_number",(r,n)->new LoggedOperation(r.getLong(1),decode(r.getString(2))),board,since,through);
    }
    private Snapshot compacted(UUID board) {
        var rows=jdbc.query("SELECT s.sequence_number,s.compacted_state::text,b.name,s.gc_version FROM board_snapshots s JOIN boards b ON b.id=s.board_id WHERE board_id=?",(r,n)->new Snapshot(board,r.getString(3),r.getLong(1),elements(r.getString(2)),r.getLong(4)),board);
        return rows.isEmpty()?null:rows.getFirst();
    }
    /** Row-share lock prevents compaction from moving rows between reading the snapshot and tail. */
    public Replay replay(UUID board,long since) {
        return replay(board,since,0);
    }
    public Replay replay(UUID board,long since,long gcVersion) {
        return transactions.execute(status->{
            long through=sequence(board," FOR SHARE");
            if(since<0||since>through)throw new IllegalArgumentException("Invalid sequence");
            var saved=compacted(board);
            if(saved!=null&&gcVersion<saved.gcVersion()&&since>saved.sequenceNumber())
                return new Replay(project(board,through),List.of(),through);
            var base=saved!=null&&(since<saved.sequenceNumber()||gcVersion<saved.gcVersion())?saved:null;
            return new Replay(base,tail(board,base==null?since:base.sequenceNumber(),through),through);
        });
    }
    public Snapshot snapshot(UUID board) {
        return transactions.execute(status->{
            long through=sequence(board," FOR SHARE");return project(board,through);
        });
    }
    private Snapshot project(UUID board,long through) {
        var saved=compacted(board);
        var projection=saved==null?new OperationApplier(board):OperationApplier.fromSnapshot(board,saved.elements());
        tail(board,saved==null?0:saved.sequenceNumber(),through).forEach(row->projection.apply(row.operation()));
        String name=jdbc.queryForObject("SELECT name FROM boards WHERE id=?",String.class,board);
        long version=jdbc.queryForObject("SELECT gc_version FROM boards WHERE id=?",Long.class,board);
        return new Snapshot(board,name,through,projection.snapshot(),version);
    }
    public List<UUID> compactionCandidates(int threshold) {
        return jdbc.query("SELECT b.id FROM boards b LEFT JOIN board_snapshots s ON b.id=s.board_id WHERE b.last_sequence-COALESCE(s.sequence_number,0)>=? ORDER BY b.created_at LIMIT 50",(r,n)->r.getObject(1,UUID.class),threshold);
    }
    public Snapshot compact(UUID board) {
        return transactions.execute(status->{
            long through=sequence(board," FOR UPDATE");
            var snapshot=project(board,through);
            saveSnapshot(snapshot);
            return snapshot;
        });
    }
    private void saveSnapshot(Snapshot snapshot){
        jdbc.update("INSERT INTO board_snapshots(board_id,sequence_number,compacted_state,gc_version) VALUES (?,?,?::jsonb,?) ON CONFLICT(board_id) DO UPDATE SET sequence_number=EXCLUDED.sequence_number,compacted_state=EXCLUDED.compacted_state,gc_version=EXCLUDED.gc_version,created_at=now()",snapshot.boardId(),snapshot.sequenceNumber(),encode(snapshot.elements()),snapshot.gcVersion());
        jdbc.update("INSERT INTO operations_archive SELECT * FROM operations WHERE board_id=? AND sequence_number<=?",snapshot.boardId(),snapshot.sequenceNumber());
        jdbc.update("DELETE FROM operations WHERE board_id=? AND sequence_number<=?",snapshot.boardId(),snapshot.sequenceNumber());
    }
    public void acknowledge(UUID board,String leaseId,long sequence,long ttlSeconds){
        transactions.executeWithoutResult(status->{
            long latest=sequence(board," FOR SHARE");
            if(sequence<0||sequence>latest||ttlSeconds<1)throw new IllegalArgumentException("Invalid acknowledgement");
            jdbc.update("INSERT INTO replica_leases(board_id,lease_id,acknowledged_sequence,expires_at) VALUES (?,?,?,now()+?*interval '1 second') ON CONFLICT(board_id,lease_id) DO UPDATE SET acknowledged_sequence=GREATEST(replica_leases.acknowledged_sequence,EXCLUDED.acknowledged_sequence),expires_at=EXCLUDED.expires_at",board,leaseId,sequence,ttlSeconds);
        });
    }
    public long gcVersion(UUID board){return jdbc.queryForObject("SELECT gc_version FROM boards WHERE id=?",Long.class,board);}
    public List<UUID> collectionCandidates(){
        return jdbc.query("SELECT board_id FROM board_snapshots WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(compacted_state) e WHERE e->>'removedHlc' IS NOT NULL) UNION SELECT board_id FROM operations WHERE payload->>'type'='ELEMENT_REMOVED' LIMIT 50",(r,n)->r.getObject(1,UUID.class));
    }
    public CollectionResult collectStable(UUID board){
        return transactions.execute(status->{
            long through=sequence(board," FOR UPDATE");
            jdbc.update("DELETE FROM replica_leases WHERE board_id=? AND expires_at<=now()",board);
            long stable=jdbc.queryForObject("SELECT COALESCE(MIN(acknowledged_sequence),?) FROM replica_leases WHERE board_id=?",Long.class,through,board);
            var snapshot=project(board,through);int before=encode(snapshot.elements()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            var removedSequences=new HashMap<UUID,Long>();
            jdbc.query("SELECT (payload->>'elementId')::uuid AS id,MAX(sequence_number) AS seq FROM (SELECT * FROM operations UNION ALL SELECT * FROM operations_archive) log WHERE board_id=? AND payload->>'type'='ELEMENT_REMOVED' GROUP BY id HAVING MAX(sequence_number)<?",r->{removedSequences.put(r.getObject(1,UUID.class),r.getLong(2));},board,stable);
            var kept=new ArrayList<Element>();int removed=0;
            for(var element:snapshot.elements()){
                Long sequence=removedSequences.get(element.id());
                if(element.removedHlc()!=null&&sequence!=null){
                    jdbc.update("INSERT INTO retired_elements(board_id,element_id,removed_sequence) VALUES (?,?,?) ON CONFLICT DO NOTHING",board,element.id(),sequence);removed++;
                }else kept.add(element);
            }
            if(removed==0)return new CollectionResult(0,before,before,snapshot.gcVersion());
            long version=Math.incrementExact(snapshot.gcVersion());jdbc.update("UPDATE boards SET gc_version=? WHERE id=?",version,board);
            saveSnapshot(new Snapshot(board,snapshot.name(),through,kept,version));
            return new CollectionResult(removed,before,encode(kept).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,version);
        });
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}}
    private Operation decode(String value) {try{return json.readValue(value,Operation.class);}catch(Exception e){throw new IllegalStateException("Invalid stored operation",e);}}
    private List<Element> elements(String value) {try{return json.readValue(value,new TypeReference<List<Element>>(){});}catch(Exception e){throw new IllegalStateException("Invalid stored snapshot",e);}}
}
