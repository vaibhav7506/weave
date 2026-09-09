package com.vaibhav.weave.hardening;

import com.fasterxml.jackson.databind.*;
import com.vaibhav.weave.WeaveApplication;
import com.vaibhav.weave.auth.EditTokens;
import com.vaibhav.weave.crdt.*;
import com.vaibhav.weave.persistence.BoardStore;
import com.vaibhav.weave.websocket.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HardeningIntegrationTest {
    ServletWebServerApplicationContext first,second;
    BoardStore store;EditTokens tokens;ObjectMapper json;JdbcTemplate jdbc;
    int port1,port2;
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeAll void launchTwoInstances(){
        String url=System.getenv().getOrDefault("WEAVE_TEST_DATABASE_URL","jdbc:postgresql://127.0.0.1:55432/weave?currentSchema=weave_phase2_test").replace("weave_phase2_test","weave_phase4_test");
        String channel="weave:phase4:"+UUID.randomUUID();
        String[] args={"--server.port=0","--spring.datasource.url="+url,"--spring.flyway.schemas=weave_phase4_test","--weave.token-secret=shared-phase-four-integration-secret-32-bytes","--weave.redis.enabled=true","--weave.redis.channel="+channel,"--weave.sync.sweep-ms=600000","--weave.compaction.interval-ms=600000","--weave.operations-per-second=1","--weave.operation-burst=10"};
        first=(ServletWebServerApplicationContext)new SpringApplicationBuilder(WeaveApplication.class).run(args);
        second=(ServletWebServerApplicationContext)new SpringApplicationBuilder(WeaveApplication.class).run(args);
        port1=first.getWebServer().getPort();port2=second.getWebServer().getPort();
        store=first.getBean(BoardStore.class);tokens=first.getBean(EditTokens.class);json=first.getBean(ObjectMapper.class);jdbc=first.getBean(JdbcTemplate.class);
    }
    @AfterAll void stop(){if(second!=null)second.close();if(first!=null)first.close();}
    Operation create(UUID board,UUID id){return new Operation(UUID.randomUUID(),board,id,new HybridLogicalClock(UUID.randomUUID()).tick(),Operation.Type.ELEMENT_CREATED,Element.Kind.RECTANGLE,Map.of("x",0,"y",0,"width",100,"height",50,"color","#425eeb"),null,null);}
    Operation update(Operation base,String field,Object value,long delta){return new Operation(UUID.randomUUID(),base.boardId(),base.elementId(),new HybridLogicalClock.Timestamp(base.hlc().physicalTime()+delta,0,UUID.randomUUID()),Operation.Type.FIELD_UPDATED,null,Map.of(),field,value);}
    final class Peer implements WebSocket.Listener,AutoCloseable {
        final BlockingQueue<JsonNode> inbox=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder partial=new StringBuilder();WebSocket socket;String closeReason;
        Peer(int port,UUID board,String token,long since){socket=http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws"),this).join();send(Map.of("type","SYNC_REQUEST","boardId",board,"editToken",token,"sinceSequence",since));}
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){partial.append(data);if(last){try{inbox.add(json.readTree(partial.toString()));}catch(Exception e){throw new RuntimeException(e);}partial.setLength(0);}socket.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closeReason=reason;closed.complete(code);return null;}
        void send(Object data){try{socket.sendText(json.writeValueAsString(data),true).join();}catch(Exception e){throw new RuntimeException(e);}}
        JsonNode next(String type)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<deadline){var message=inbox.poll(100,TimeUnit.MILLISECONDS);if(message!=null&&message.path("type").asText().equals(type))return message;}throw new AssertionError("No "+type);}
        JsonNode operation(UUID opId)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<deadline){var message=next("OPERATION");if(opId.toString().equals(message.path("operation").path("opId").asText()))return message;}throw new AssertionError("No operation "+opId);}
        void op(Operation op){send(Map.of("type","OPERATION","operation",op));}
        @Override public void close(){socket.abort();}
    }
    /** Real TCP pass-through load balancer: each upgraded connection stays on one backend. */
    static final class Balancer implements AutoCloseable {
        final ServerSocket listener;final ExecutorService threads=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});
        final List<Socket> sockets=new CopyOnWriteArrayList<>();final List<Integer> routed=new CopyOnWriteArrayList<>();
        Balancer(int... ports)throws IOException{listener=new ServerSocket(0,50,InetAddress.getLoopbackAddress());threads.submit(()->{try{int next=0;while(!listener.isClosed()){Socket client=listener.accept();int port=ports[next++%ports.length];Socket backend=new Socket("127.0.0.1",port);routed.add(port);sockets.add(client);sockets.add(backend);threads.submit(()->copy(client,backend));threads.submit(()->copy(backend,client));}}catch(IOException ignored){}});}
        void copy(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());}catch(IOException ignored){}finally{try{from.close();to.close();}catch(IOException ignored){}}}
        int port(){return listener.getLocalPort();}
        @Override public void close()throws IOException{listener.close();for(var socket:sockets)socket.close();threads.shutdownNow();}
    }
    @Test void redisRelaysAcrossTwoInstancesBehindLoadBalancerAndReplaysCompactedHistory()throws Exception {
        var board=store.create("Two instance integration");String token=tokens.issue(board.id());var creation=create(board.id(),UUID.randomUUID());
        try(var lb=new Balancer(port1,port2);var a=new Peer(lb.port(),board.id(),token,0);var b=new Peer(lb.port(),board.id(),token,0)){
            a.next("SYNC_COMPLETE");b.next("SYNC_COMPLETE");assertEquals(List.of(port1,port2),lb.routed);
            a.op(creation);a.next("OPERATION");b.next("OPERATION");
            var move=update(creation,"x",80,1);var color=update(creation,"color","#e06c48",2);
            a.op(move);b.op(color);
            var pa=new OperationApplier(board.id());var pb=new OperationApplier(board.id());pa.apply(creation);pb.apply(creation);
            for(int i=0;i<2;i++){pa.apply(json.treeToValue(a.next("OPERATION").path("operation"),Operation.class));pb.apply(json.treeToValue(b.next("OPERATION").path("operation"),Operation.class));}
            assertEquals(pa.snapshot(),pb.snapshot());assertEquals(80,pa.live().getFirst().fields().get("x").value());
            a.send(Map.of("type","PRESENCE","presence",Map.of("name","Across Redis","color","#425eeb","x",10,"y",20,"selection","")));
            assertEquals("Across Redis",b.next("PRESENCE").path("presence").path("name").asText());
            a.close();b.next("PRESENCE_LEFT");
            store.compact(board.id());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operations WHERE board_id=?",Integer.class,board.id()));
            b.op(update(creation,"y",90,3));b.next("OPERATION");
            try(var rejoin=new Peer(port1,board.id(),token,1)){
                assertEquals(3,rejoin.next("SYNC_SNAPSHOT").path("snapshot").path("sequenceNumber").asLong());
                assertEquals(4,rejoin.next("OPERATION").path("sequenceNumber").asLong());rejoin.next("SYNC_COMPLETE");
                // Cross-instance Redis delivery can leave an already-delivered replay in the inbox.
                // Verify the retry acknowledgement by its immutable operation ID, rather than queue order.
                rejoin.op(move);assertEquals(2,rejoin.operation(move.opId()).path("sequenceNumber").asLong());assertEquals(4,store.sequence(board.id()));
            }
        }
    }
    @Test void periodicDatabaseCatchUpRecoversACommitWithNoRedisNotification()throws Exception {
        var board=store.create("Lost notification");try(var peer=new Peer(port2,board.id(),tokens.issue(board.id()),0)){
            peer.next("SYNC_COMPLETE");store.append(create(board.id(),UUID.randomUUID()));
            second.getBean(WeaveWebSocketHandler.class).recoverMissedNotifications();
            assertEquals(1,peer.next("OPERATION").path("sequenceNumber").asLong());
        }
    }
    @Test void operationFloodClosesOnlyOffendingConnectionWithExplicitReason()throws Exception {
        var board=store.create("Rate cap");var base=create(board.id(),UUID.randomUUID());
        try(var peer=new Peer(port1,board.id(),tokens.issue(board.id()),0);var healthy=new Peer(port2,board.id(),tokens.issue(board.id()),0)){
            peer.next("SYNC_COMPLETE");healthy.next("SYNC_COMPLETE");
            for(int i=0;i<30&&!peer.closed.isDone();i++)try{peer.op(i==0?base:update(base,"x",i,i));}catch(RuntimeException ignored){break;}
            assertEquals(4008,peer.closed.get(8,TimeUnit.SECONDS));assertTrue(peer.closeReason.contains("Operation rate exceeded"));assertTrue(store.sequence(board.id())<=12);
            healthy.send(Map.of("type","PING"));healthy.next("PONG");
        }
    }
    @Test void idleExpiredTokensAreDisconnectedAndForgedTokensRejected()throws Exception {
        var board=store.create("Expiry");
        try(var peer=new Peer(port1,board.id(),tokens.issue(board.id(),Instant.now().getEpochSecond()+2),0)){
            peer.next("SYNC_COMPLETE");Thread.sleep(2100);first.getBean(WeaveWebSocketHandler.class).recoverMissedNotifications();assertEquals(4001,peer.closed.get(5,TimeUnit.SECONDS));
        }
        try(var peer=new Peer(port2,board.id(),tokens.issue(board.id())+"tampered",0)){assertEquals(4001,peer.closed.get(5,TimeUnit.SECONDS));}
    }
    @Test void compactionPreservesTombstonesUnknownEditsAndArchiveDedup()throws Exception {
        var board=store.create("Tombstone compaction");var creation=create(board.id(),UUID.randomUUID());
        var remove=new Operation(UUID.randomUUID(),board.id(),creation.elementId(),creation.hlc(),Operation.Type.ELEMENT_REMOVED,null,Map.of(),null,null);
        store.append(remove);store.append(creation);store.append(update(creation,"x",99,2));
        var orphan=update(create(board.id(),UUID.randomUUID()),"y",50,1);store.append(orphan);
        var before=store.snapshot(board.id());store.compact(board.id());assertEquals(before,store.snapshot(board.id()));
        assertEquals(0,OperationApplier.fromSnapshot(board.id(),before.elements()).live().size());
        assertEquals(1,store.append(remove).sequenceNumber());assertEquals(4,store.since(board.id(),0,4).size());
        store.compact(board.id());assertEquals(before,store.snapshot(board.id()));
    }
    @Test void fiftyThousandOperationBoardLoadsFasterAfterCompaction()throws Exception {
        var board=store.create("Compaction measurement");var creation=create(board.id(),UUID.randomUUID());store.append(creation);
        // Seed valid immutable log records in a batch; measure loading, not fixture insertion.
        var rows=new ArrayList<Object[]>();for(int i=1;i<50000;i++){var op=update(creation,"x",i,i);rows.add(new Object[]{board.id(),op.opId(),i+1,json.writeValueAsString(op)});}
        jdbc.batchUpdate("INSERT INTO operations(board_id,op_id,sequence_number,payload) VALUES (?,?,?,?::jsonb)",rows);
        jdbc.update("UPDATE boards SET last_sequence=50000 WHERE id=?",board.id());
        store.snapshot(board.id());long[] full=new long[3];BoardStore.Snapshot before=null;
        for(int i=0;i<3;i++){long start=System.nanoTime();before=store.snapshot(board.id());full[i]=System.nanoTime()-start;}
        store.compact(board.id());store.snapshot(board.id());long[] compact=new long[3];
        for(int i=0;i<3;i++){long start=System.nanoTime();var after=store.snapshot(board.id());compact[i]=System.nanoTime()-start;assertEquals(before,after);}
        Arrays.sort(full);Arrays.sort(compact);assertTrue(compact[1]<full[1],"Compacted median must beat full replay");
        Files.createDirectories(Path.of("target"));json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/compaction-result.json").toFile(),Map.of("operations",50000,"fullReplayMedianMs",full[1]/1e6,"snapshotMedianMs",compact[1]/1e6,"activeOperations",jdbc.queryForObject("SELECT count(*) FROM operations WHERE board_id=?",Integer.class,board.id()),"archiveOperations",50000,"equalSnapshots",true));
        System.out.printf("COMPACTION: 50000 ops, full replay %.2f ms, snapshot %.2f ms (medians of 3)%n",full[1]/1e6,compact[1]/1e6);
    }
    @Test void compactionRacingWithAppendsKeepsEveryCommittedOperation() throws Exception {
        var board=store.create("Concurrent compaction");var base=create(board.id(),UUID.randomUUID());store.append(base);
        var writer=CompletableFuture.runAsync(()->{for(int i=1;i<=100;i++)store.append(update(base,"x",i,i));});
        for(int i=0;i<5;i++)store.compact(board.id());writer.get(15,TimeUnit.SECONDS);
        var log=store.since(board.id(),0,101);assertEquals(101,log.size());
        var expected=new OperationApplier(board.id());long sequence=0;
        for(var entry:log){assertEquals(++sequence,entry.sequenceNumber());expected.apply(entry.operation());}
        assertEquals(expected.snapshot(),store.snapshot(board.id()).elements());
        var replay=store.replay(board.id(),0);var restored=replay.snapshot()==null?new OperationApplier(board.id()):OperationApplier.fromSnapshot(board.id(),replay.snapshot().elements());
        for(var entry:replay.operations())restored.apply(entry.operation());assertEquals(expected.snapshot(),restored.snapshot());
    }
    @Test void rateBucketUsesMonotonicTimeAndRefills(){var nanos=new AtomicLong();var cap=new OperationRateLimit(2,2,nanos::get);assertTrue(cap.acquire());assertTrue(cap.acquire());assertFalse(cap.acquire());nanos.set(500_000_000);assertTrue(cap.acquire());assertFalse(cap.acquire());}
}
