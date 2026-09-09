package com.vaibhav.weave.websocket;

import com.fasterxml.jackson.databind.*;
import com.vaibhav.weave.auth.EditTokens;
import com.vaibhav.weave.crdt.*;
import com.vaibhav.weave.persistence.BoardStore;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "weave.token-secret=phase-two-integration-test-secret-32-bytes",
    "spring.datasource.url=${WEAVE_TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55432/weave?currentSchema=weave_phase2_test}",
    "spring.flyway.schemas=weave_phase2_test"
})
class SyncIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired BoardStore store;
    @Autowired EditTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    record Invite(UUID id,String token) {}
    Invite create() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/boards")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Integration test\"}")).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(201,response.statusCode());
        var body=json.readTree(response.body());return new Invite(UUID.fromString(body.path("id").asText()),body.path("editToken").asText());
    }
    Operation op(Invite board,UUID element,String field,Object value) {
        return new Operation(UUID.randomUUID(),board.id(),element,new HybridLogicalClock(UUID.randomUUID()).tick(),field==null?Operation.Type.ELEMENT_CREATED:Operation.Type.FIELD_UPDATED,field==null?Element.Kind.RECTANGLE:null,field==null?Map.of("x",0,"y",0,"width",100,"height",50,"color","#425eeb"):Map.of(),field,value);
    }
    final class Peer implements WebSocket.Listener,AutoCloseable {
        final BlockingQueue<JsonNode> inbox=new LinkedBlockingQueue<>();
        final StringBuilder partial=new StringBuilder();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();
        WebSocket socket;
        Peer(Invite invite,long since) {this(invite,since,port);}
        Peer(Invite invite,long since,int targetPort) {
            socket=http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+targetPort+"/ws"),this).join();
            send(Map.of("type","SYNC_REQUEST","boardId",invite.id(),"editToken",invite.token(),"sinceSequence",since));
        }
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){
            partial.append(text);if(last){try{inbox.add(json.readTree(partial.toString()));}catch(Exception e){throw new RuntimeException(e);}partial.setLength(0);}ws.request(1);return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){closed.complete(code);return null;}
        @Override public void onError(WebSocket ws,Throwable error){closed.completeExceptionally(error);}
        void send(Object message){try{socket.sendText(json.writeValueAsString(message),true).join();}catch(Exception e){throw new RuntimeException(e);}}
        void operation(Operation op){send(Map.of("type","OPERATION","operation",op));}
        JsonNode next(String type) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline){var event=inbox.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&event.path("type").asText().equals(type))return event;}
            throw new AssertionError("Timed out awaiting "+type);
        }
        @Override public void close(){if(socket!=null)socket.abort();}
    }
    @Test void historyPagesRemainStableAcrossCompactionAndNewAppends() throws Exception {
        Invite board=create();UUID element=UUID.randomUUID();
        var created=op(board,element,null,null);store.append(created);
        store.append(op(board,element,"x",120));store.append(op(board,element,"color","#e06c48"));
        var first=history(board,"?limit=2",true);assertEquals(200,first.statusCode());
        var page=json.readTree(first.body());assertEquals(3,page.path("through").asLong());assertEquals(2,page.path("operations").size());
        store.compact(board.id());store.append(op(board,element,"y",200));
        var second=history(board,"?after=2&through=3&limit=2",true);assertEquals(200,second.statusCode());
        var tail=json.readTree(second.body());assertEquals(1,tail.path("operations").size());assertEquals(3,tail.path("nextSequence").asLong());
        assertEquals("color",tail.path("operations").get(0).path("operation").path("field").asText());
        assertEquals(4,json.readTree(history(board,"",true).body()).path("operations").size());
        assertEquals(401,history(board,"",false).statusCode());
        assertEquals(400,history(board,"?limit=1001",true).statusCode());
        assertEquals(400,history(board,"?after=3&through=2",true).statusCode());
        assertEquals(400,history(board,"?through=999",true).statusCode());
    }
    HttpResponse<String> history(Invite board,String query,boolean authorized) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/boards/"+board.id()+"/history"+query));
        if(authorized)request.header("Authorization","Bearer "+board.token());
        return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void durableBroadcastReplayDedupAndPresence() throws Exception {
        Invite board=create();UUID element=UUID.randomUUID();
        try(var a=new Peer(board,0);var b=new Peer(board,0)) {
            a.next("SYNC_COMPLETE");b.next("SYNC_COMPLETE");
            var create=op(board,element,null,null);a.operation(create);
            assertEquals(1,a.next("OPERATION").path("sequenceNumber").asLong());assertEquals(1,b.next("OPERATION").path("sequenceNumber").asLong());
            var move=op(board,element,"x",42);var color=op(board,element,"color","#e06c48");
            CompletableFuture.allOf(CompletableFuture.runAsync(()->a.operation(move)),CompletableFuture.runAsync(()->b.operation(color))).join();
            var left=new OperationApplier(board.id());var right=new OperationApplier(board.id());left.apply(create);right.apply(create);
            for(int i=0;i<2;i++){left.apply(json.treeToValue(a.next("OPERATION").path("operation"),Operation.class));right.apply(json.treeToValue(b.next("OPERATION").path("operation"),Operation.class));}
            assertEquals(left.snapshot(),right.snapshot());assertEquals(42,left.live().getFirst().fields().get("x").value());assertEquals("#e06c48",left.live().getFirst().fields().get("color").value());
            a.operation(move);assertTrue(a.next("OPERATION").path("sequenceNumber").asLong()<=3);assertEquals(3,store.sequence(board.id()));
            a.send(Map.of("type","PRESENCE","presence",Map.of("name","Alice","color","#425eeb","x",12,"y",34,"selection",element.toString())));
            assertEquals("Alice",b.next("PRESENCE").path("presence").path("name").asText());assertEquals(3,store.sequence(board.id()));
            a.close();b.next("PRESENCE_LEFT");
            b.operation(op(board,element,"y",90));assertEquals(4,b.next("OPERATION").path("sequenceNumber").asLong());
            try(var reconnected=new Peer(board,3)){
                assertEquals(4,reconnected.next("OPERATION").path("sequenceNumber").asLong());assertEquals(4,reconnected.next("SYNC_COMPLETE").path("sequenceNumber").asLong());
                reconnected.operation(op(board,element,"width",160));assertEquals(5,reconnected.next("OPERATION").path("sequenceNumber").asLong());assertEquals(5,b.next("OPERATION").path("sequenceNumber").asLong());
            }
            // Fresh store instance rebuilds exclusively from PostgreSQL, with no room state.
            var rebuilt=new BoardStore(jdbc,transactions,json).snapshot(board.id());assertEquals(5,rebuilt.sequenceNumber());assertEquals(90,rebuilt.elements().getFirst().fields().get("y").value());assertEquals(160,rebuilt.elements().getFirst().fields().get("width").value());
        }
    }
    @Test void snapshotAuthorizationAndBoardIsolation() throws Exception {
        Invite a=create(),b=create();
        var uri=URI.create("http://127.0.0.1:"+port+"/api/v1/boards/"+a.id()+"/snapshot");
        assertEquals(401,http.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(401,http.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+b.token()).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200,http.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+a.token()).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        try(var wrong=new Peer(new Invite(a.id(),b.token()),0)){assertEquals(4001,wrong.closed.get(10,TimeUnit.SECONDS));}
        try(var expired=new Peer(new Invite(a.id(),tokens.issue(a.id(),Instant.now().minusSeconds(1).getEpochSecond())),0)){assertEquals(4001,expired.closed.get(10,TimeUnit.SECONDS));}
        try(var sender=new Peer(a,0);var unrelated=new Peer(b,0)){
            sender.next("SYNC_COMPLETE");unrelated.next("SYNC_COMPLETE");sender.operation(op(a,UUID.randomUUID(),null,null));sender.next("OPERATION");
            unrelated.send(Map.of("type","PING"));unrelated.next("PONG");assertEquals(0,store.sequence(b.id()));assertTrue(unrelated.inbox.isEmpty());
        }
    }
    @Test void joiningWhileEditsArriveNeverMissesASequence() throws Exception {
        Invite board=create();UUID element=UUID.randomUUID();
        try(var writer=new Peer(board,0)) {
            writer.next("SYNC_COMPLETE");writer.operation(op(board,element,null,null));writer.next("OPERATION");
            var writing=CompletableFuture.runAsync(()->{for(int i=0;i<40;i++)writer.operation(op(board,element,"x",i));});
            try(var joining=new Peer(board,0)){
                Set<Long> sequences=new TreeSet<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean complete=false;
                while((sequences.size()<41||!complete)&&System.nanoTime()<deadline){var event=joining.inbox.poll(100,TimeUnit.MILLISECONDS);if(event==null)continue;if(event.path("type").asText().equals("OPERATION"))sequences.add(event.path("sequenceNumber").asLong());if(event.path("type").asText().equals("SYNC_COMPLETE"))complete=true;}
                writing.join();assertTrue(complete);assertEquals(41,sequences.size());for(long i=1;i<=41;i++)assertTrue(sequences.contains(i));
            }
        }
    }
    @Test void conflictingDuplicateAndMalformedFieldsDoNotAppend() throws Exception {
        Invite board=create();UUID element=UUID.randomUUID();var creation=op(board,element,null,null);
        try(var peer=new Peer(board,0)){
            peer.next("SYNC_COMPLETE");peer.operation(creation);peer.next("OPERATION");
            var conflicting=new Operation(creation.opId(),board.id(),element,creation.hlc(),creation.type(),creation.elementType(),Map.of("x",99),null,null);
            peer.operation(conflicting);assertEquals(4002,peer.closed.get(10,TimeUnit.SECONDS));assertEquals(1,store.sequence(board.id()));
        }
        try(var peer=new Peer(board,1)){
            peer.next("SYNC_COMPLETE");peer.operation(op(board,element,"width",-5));assertEquals(4002,peer.closed.get(10,TimeUnit.SECONDS));assertEquals(1,store.sequence(board.id()));
        }
    }

    @Test void naiveHttpModeLosesConcurrentEditsWithoutChangingTheDurableCrdtBoard() throws Exception {
        Invite board=create();UUID element=UUID.randomUUID();
        var creation=op(board,element,null,null);store.append(creation);
        var uri=URI.create("http://127.0.0.1:"+port+"/api/v1/boards/"+board.id()+"/comparison/naive");
        assertEquals(401,http.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        var base=http.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+board.token()).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,base.statusCode());
        var a=new OperationApplier(board.id());a.apply(creation);a.apply(op(board,element,"x",80));
        var b=new OperationApplier(board.id());b.apply(creation);b.apply(op(board,element,"color","#e06c48"));
        JsonNode last=null;
        for(var fullSnapshot:List.of(a.snapshot(),b.snapshot())) {
            var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+board.token()).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("elements",fullSnapshot)))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());last=json.readTree(response.body());
        }
        assertNotNull(last);assertEquals("NAIVE_WHOLE_BOARD_LWW",last.path("mode").asText());assertEquals(2,last.path("arrivalNumber").asLong());
        var fields=last.path("elements").get(0).path("fields");assertEquals(0,fields.path("x").path("value").asInt());assertEquals("#e06c48",fields.path("color").path("value").asText());
        assertEquals(1,store.sequence(board.id()));assertEquals("#425eeb",store.snapshot(board.id()).elements().getFirst().fields().get("color").value());
    }

    @Test void stableCollectionWaitsForStrictAckAndEvictsDisconnectedReplica() throws Exception {
        var board=create();var id=UUID.randomUUID();var creation=op(board,id,null,null);store.append(creation);
        store.append(new Operation(UUID.randomUUID(),board.id(),id,new HybridLogicalClock(UUID.randomUUID()).tick(),Operation.Type.ELEMENT_REMOVED,null,Map.of(),null,null));
        store.append(op(board,UUID.randomUUID(),null,null));
        store.compact(board.id());
        store.acknowledge(board.id(),"online",2,300);store.acknowledge(board.id(),"disconnected",1,300);
        assertEquals(0,store.collectStable(board.id()).removed());
        store.acknowledge(board.id(),"disconnected",2,300);
        assertEquals(0,store.collectStable(board.id()).removed());
        store.acknowledge(board.id(),"online",3,300);
        assertEquals(0,store.collectStable(board.id()).removed());
        jdbc.update("UPDATE replica_leases SET expires_at=now()-interval '1 second' WHERE board_id=? AND lease_id='disconnected'",board.id());
        var result=store.collectStable(board.id());assertEquals(1,result.removed());assertTrue(result.afterBytes()<result.beforeBytes());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM operations WHERE board_id=?",Integer.class,board.id()));
        assertEquals(3,store.history(board.id(),0,null,100).operations().size()); // History deliberately retained.
        assertEquals(1,store.snapshot(board.id()).elements().size());
        assertThrows(BoardStore.RetiredElementException.class,()->store.append(creation));
        // A client already at the same sequence still receives the new GC generation.
        try(var returning=new Peer(board,3)){
            var snapshot=returning.next("SYNC_SNAPSHOT").path("snapshot");
            assertEquals(1,snapshot.path("gcVersion").asInt());assertEquals(1,snapshot.path("elements").size());
            returning.next("SYNC_COMPLETE");returning.operation(op(board,id,"x",999));
            assertEquals("RETIRED_ELEMENT",returning.next("OPERATION_REJECTED").path("code").asText());
            returning.next("SYNC_SNAPSHOT");assertEquals(3,store.sequence(board.id()));
        }
        store.append(op(board,UUID.randomUUID(),null,null));
        var ahead=store.replay(board.id(),4,0);assertNotNull(ahead.snapshot());assertEquals(4,ahead.snapshot().sequenceNumber());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/gc-result.json"),json.writeValueAsString(result));
    }
    @Test void forgedFutureClockCannotWinAndRepeatedForgeryClosesReplica() throws Exception {
        var board=create();var id=UUID.randomUUID();
        try(var attacker=new Peer(board,0);var honest=new Peer(board,0)){
            attacker.next("SYNC_COMPLETE");honest.next("SYNC_COMPLETE");
            honest.operation(op(board,id,null,null));honest.next("OPERATION");attacker.next("OPERATION");
            for(int i=0;i<3;i++){
                var forged=new Operation(UUID.randomUUID(),board.id(),id,new HybridLogicalClock.Timestamp(System.currentTimeMillis()+86_400_000,0,UUID.randomUUID()),Operation.Type.FIELD_UPDATED,null,Map.of(),"x",999);
                attacker.operation(forged);assertEquals("CLOCK_SKEW",attacker.next("OPERATION_REJECTED").path("code").asText());attacker.next("SYNC_SNAPSHOT");
            }
            assertEquals(4008,attacker.closed.get(10,TimeUnit.SECONDS));
            honest.operation(op(board,id,"x",42));honest.next("OPERATION");
            assertEquals(2,store.sequence(board.id()));assertEquals(42,store.snapshot(board.id()).elements().getFirst().fields().get("x").value());
        }
    }
    @Test void freshInviteRequiresAuthorizationAndJoinsTheSameBoard() throws Exception {
        var board=create();var uri=URI.create("http://127.0.0.1:"+port+"/api/v1/boards/"+board.id()+"/invites");
        assertEquals(401,http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        var response=http.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+board.token()).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        try(var peer=new Peer(new Invite(board.id(),json.readTree(response.body()).path("editToken").asText()),0)){peer.next("SYNC_COMPLETE");}
    }
    /** Real TCP relay: both directions have deterministic delay/jitter and a switchable hard partition. */
    final class ChaosProxy implements AutoCloseable {
        final java.net.ServerSocket listener=new java.net.ServerSocket(0);
        final Set<java.net.Socket> sockets=ConcurrentHashMap.newKeySet();
        volatile boolean partitioned,stopped;
        final Thread acceptor;
        ChaosProxy() throws Exception {
            acceptor=Thread.ofVirtual().start(()->{while(!stopped)try{
                var incoming=listener.accept();if(partitioned){incoming.close();continue;}
                var outgoing=new java.net.Socket("127.0.0.1",port);sockets.add(incoming);sockets.add(outgoing);
                Thread.ofVirtual().start(()->relay(incoming,outgoing,73129));Thread.ofVirtual().start(()->relay(outgoing,incoming,982451653));
            }catch(java.io.IOException failure){if(!stopped)throw new RuntimeException(failure);}});
        }
        void relay(java.net.Socket from,java.net.Socket to,long seed){
            var random=new Random(seed);var buffer=new byte[4096];
            try {int count;while((count=from.getInputStream().read(buffer))!=-1){Thread.sleep(15+random.nextInt(36));to.getOutputStream().write(buffer,0,count);to.getOutputStream().flush();}}
            catch(Exception expectedDisconnect){}finally{try{from.close();to.close();}catch(Exception ignored){}sockets.remove(from);sockets.remove(to);}
        }
        void partition(){partitioned=true;for(var socket:sockets)try{socket.close();}catch(Exception ignored){}}
        public void close()throws Exception{stopped=true;partition();listener.close();acceptor.join(2000);}
    }
    @Test void tcpLatencyJitterPartitionAndReconnectConverge() throws Exception {
        var board=create();var id=UUID.randomUUID();var creation=op(board,id,null,null);
        var left=new OperationApplier(board.id());var right=new OperationApplier(board.id());
        try(var proxy=new ChaosProxy();var a=new Peer(board,0,proxy.listener.getLocalPort());var b=new Peer(board,0)){
            a.next("SYNC_COMPLETE");b.next("SYNC_COMPLETE");a.operation(creation);
            left.apply(json.treeToValue(a.next("OPERATION").path("operation"),Operation.class));right.apply(json.treeToValue(b.next("OPERATION").path("operation"),Operation.class));
            proxy.partition();
            var offlineMove=op(board,id,"x",314);left.apply(offlineMove); // A retains its unsent outbox.
            var onlineColor=op(board,id,"color","#e06c48");b.operation(onlineColor);right.apply(json.treeToValue(b.next("OPERATION").path("operation"),Operation.class));
            proxy.partitioned=false;
            try(var returned=new Peer(board,1,proxy.listener.getLocalPort())){
                left.apply(json.treeToValue(returned.next("OPERATION").path("operation"),Operation.class));returned.next("SYNC_COMPLETE");
                returned.operation(offlineMove);left.apply(json.treeToValue(returned.next("OPERATION").path("operation"),Operation.class));right.apply(json.treeToValue(b.next("OPERATION").path("operation"),Operation.class));
                returned.operation(offlineMove);returned.next("OPERATION"); // ACK lost/retry is idempotent.
                assertEquals(3,store.sequence(board.id()));assertEquals(left.snapshot(),right.snapshot());assertEquals(left.snapshot(),store.snapshot(board.id()).elements());
                assertEquals(314,left.live().getFirst().fields().get("x").value());assertEquals("#e06c48",left.live().getFirst().fields().get("color").value());
            }
        }
    }
}

