package com.vaibhav.weave.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaibhav.weave.auth.EditTokens;
import com.vaibhav.weave.crdt.Operation;
import com.vaibhav.weave.persistence.BoardStore;
import com.vaibhav.weave.sync.ClusterRelay;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Component
public class WeaveWebSocketHandler extends TextWebSocketHandler {
    private record Cursor(long sequence,long gcVersion) {}
    private record Seen(Map<String,Object> value,long at) {}
    private static final class Room {
        final ReentrantLock lock=new ReentrantLock();
        final Map<String,Client> clients=new HashMap<>();
        final Map<String,Seen> peers=new HashMap<>();
    }
    private final class Client {
        final WebSocketSession session; final UUID boardId; final Room room; final long expires; final String presenceId;
        final OperationRateLimit limiter=new OperationRateLimit(rate,burst);
        long sequence; long lastSnapshot; long gcVersion; long acknowledged; int clockViolations; Map<String,Object> presence;
        Client(WebSocketSession raw,UUID board,Room room,long expires,long sequence){
            session=new ConcurrentWebSocketSessionDecorator(raw,5000,1024*1024);boardId=board;this.room=room;this.expires=expires;this.sequence=sequence;presenceId=relay.instanceId()+":"+raw.getId();
        }
    }
    private final class Mailbox {
        final ArrayBlockingQueue<TextMessage> messages=new ArrayBlockingQueue<>(256);
        final java.util.concurrent.atomic.AtomicInteger queuedBytes=new java.util.concurrent.atomic.AtomicInteger();
        final Thread worker;
        Mailbox(WebSocketSession session){
            Runnable run=()->{try{while(session.isOpen()){var message=messages.take();queuedBytes.addAndGet(-message.getPayloadLength());processMessage(session,message);}}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                catch(Exception failure){close(session,1011,"Message processing failed");}};
            worker=(virtualThreads?Thread.ofVirtual():Thread.ofPlatform()).name("weave-ws-"+session.getId()).unstarted(run);
        }
    }
    @Value("${weave.websocket.virtual-threads:true}") private boolean virtualThreads;
    private final Map<String,Mailbox> mailboxes=new ConcurrentHashMap<>();
    private final Map<UUID,Room> rooms=new ConcurrentHashMap<>();
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final Map<String,WebSocketSession> connections=new ConcurrentHashMap<>();
    private final BoardStore store; private final EditTokens tokens; private final ObjectMapper json; private final ClusterRelay relay;
    private final int rate,burst;
    @Value("${weave.clock-tolerance-ms:30000}") private long clockToleranceMillis;
    @Value("${weave.replica-lease-seconds:300}") private long leaseSeconds;
    public WeaveWebSocketHandler(BoardStore store,EditTokens tokens,ObjectMapper json,ClusterRelay relay,@Value("${weave.operations-per-second}")int rate,@Value("${weave.operation-burst}")int burst){
        this.store=store;this.tokens=tokens;this.json=json;this.relay=relay;this.rate=rate;this.burst=burst;
        if(rate<1||burst<1)throw new IllegalArgumentException("Invalid operation rate");
        relay.subscribe(this::receive);
    }
    @Override public void afterConnectionEstablished(WebSocketSession session){session.setTextMessageSizeLimit(512*1024);session.getAttributes().put("openedAt",System.nanoTime());connections.put(session.getId(),session);var mailbox=new Mailbox(session);mailboxes.put(session.getId(),mailbox);mailbox.worker.start();}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) {
        var mailbox=mailboxes.get(session.getId());
        if(mailbox!=null&&(mailbox.queuedBytes.addAndGet(message.getPayloadLength())>1024*1024||!mailbox.messages.offer(message)))close(session,4008,"Inbound message queue exceeded");
    }
    private void processMessage(WebSocketSession session,TextMessage message) throws Exception {
        if(!session.isOpen())return;
        try {
            JsonNode data=json.readTree(message.getPayload());String type=data.path("type").asText();
            if(type.equals("SYNC_REQUEST")){join(session,data);return;}
            Client client=clients.get(session.getId());if(client==null)throw new IllegalArgumentException("Join first");
            {client.room.lock.lock();try {
                if(!authorized(client))return;
                switch(type){
                    case "OPERATION" -> {
                        if(!client.limiter.acquire()){close(session,4008,"Operation rate exceeded; queued edits retained");return;}
                        Operation op=json.treeToValue(data.path("operation"),Operation.class);
                        if(!client.boardId.equals(op.boardId()))throw new IllegalArgumentException("Wrong board");
                        OperationValidation.validate(op);
                        if(!new ClockGuard(clockToleranceMillis).accepts(op)){
                            send(client.session,Map.of("type","OPERATION_REJECTED","opId",op.opId(),"elementId",op.elementId(),"code","CLOCK_SKEW","message","Your device clock is too far ahead. Correct it and reopen the board."));
                            forceSnapshot(client);
                            if(++client.clockViolations>=3)close(session,4008,"Repeated future-clock violations");
                            return;
                        }
                        long before=client.sequence;
                        BoardStore.LoggedOperation logged;
                        try{logged=store.append(op);}catch(BoardStore.RetiredElementException retired){
                            send(client.session,Map.of("type","OPERATION_REJECTED","opId",op.opId(),"elementId",op.elementId(),"code","RETIRED_ELEMENT","message","This shape was deleted while you were away. Its queued edits were discarded."));
                            forceSnapshot(client);return;
                        } // Durable before either notification or acknowledgment.
                        drainCommitted(client.room,logged);
                        // Also acknowledge old retries, including operations now represented by a snapshot.
                        if(logged.sequenceNumber()<=before||logged.sequenceNumber()<=client.lastSnapshot)send(client.session,Map.of("type","OPERATION","sequenceNumber",logged.sequenceNumber(),"operation",logged.operation()));
                        relay.publish(client.boardId,"OPERATIONS",Map.of());
                    }
                    case "PRESENCE" -> {
                        var p=data.path("presence");String name=p.path("name").asText("Guest"),color=p.path("color").asText("#425eeb");
                        double x=p.path("x").asDouble(Double.NaN),y=p.path("y").asDouble(Double.NaN);
                        if(name.isBlank()||name.length()>40||!color.matches("#[0-9a-fA-F]{6}")||!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Invalid presence");
                        String selection=p.path("selection").asText("");if(!selection.isEmpty())UUID.fromString(selection);
                        client.presence=Map.of("sessionId",client.presenceId,"name",name,"color",color,"x",x,"y",y,"selection",selection);
                        presence(client.room,client.presence,client.presenceId);relay.publish(client.boardId,"PRESENCE",client.presence);
                    }
                    case "ACK" -> {
                        var ack=data.path("sequenceNumber");
                        if(!ack.isIntegralNumber()||!ack.canConvertToLong()||ack.asLong()<0||ack.asLong()>client.sequence)throw new IllegalArgumentException("Invalid ACK");
                        client.acknowledged=Math.max(client.acknowledged,ack.asLong());
                        store.acknowledge(client.boardId,client.presenceId,client.acknowledged,leaseSeconds);
                    }
                    case "PING" -> {store.acknowledge(client.boardId,client.presenceId,client.acknowledged,leaseSeconds);send(client.session,Map.of("type","PONG"));if(client.presence!=null){client.room.peers.put(client.presenceId,new Seen(client.presence,System.currentTimeMillis()));relay.publish(client.boardId,"PRESENCE",client.presence);}}
                    default -> throw new IllegalArgumentException("Unknown message");
                }
            }finally{client.room.lock.unlock();}}
        }catch(org.springframework.web.server.ResponseStatusException e){close(session,4001,"Invalid or expired board link");}
        catch(IllegalArgumentException|com.fasterxml.jackson.core.JsonProcessingException e){close(session,4002,"Invalid protocol message");}
        catch(org.springframework.dao.DataAccessException e){close(session,1011,"Database temporarily unavailable");}
    }
    private void join(WebSocketSession session,JsonNode data)throws IOException {
        if(clients.containsKey(session.getId()))throw new IllegalArgumentException("Already joined");
        UUID board=UUID.fromString(data.path("boardId").asText());long expiry=tokens.verify(board,data.path("editToken").asText());
        var cursor=data.path("sinceSequence");if(!cursor.isIntegralNumber()||!cursor.canConvertToLong()||cursor.asLong()<0)throw new IllegalArgumentException("Invalid cursor");
        Room room=rooms.computeIfAbsent(board,key->new Room());
        {room.lock.lock();try {
            Client client=new Client(session,board,room,expiry,cursor.asLong());
            var generation=data.path("gcVersion");
            if(!generation.isMissingNode()&&(!generation.isIntegralNumber()||!generation.canConvertToLong()||generation.asLong()<0||generation.asLong()>store.gcVersion(board)))throw new IllegalArgumentException("Invalid GC version");
            client.gcVersion=generation.asLong(0);
            store.acknowledge(board,client.presenceId,0,leaseSeconds);catchUp(client);
            for(var peer:room.peers.values())send(client.session,Map.of("type","PRESENCE","presence",peer.value()));
            send(client.session,Map.of("type","SYNC_COMPLETE","sequenceNumber",client.sequence,"sessionId",client.presenceId));
            room.clients.put(session.getId(),client);clients.put(session.getId(),client);
            if(!session.isOpen()){room.clients.remove(session.getId());clients.remove(session.getId(),client);return;}
            // Any DB commit racing this registration is caught by the pending relay or periodic sweep.
            relay.publish(board,"PRESENCE_REQUEST",Map.of());
        }finally{room.lock.unlock();}}
    }
    private boolean authorized(Client client){if(client.expires<=Instant.now().getEpochSecond()){close(client.session,4001,"Board link expired");return false;}return client.session.isOpen();}
    private void catchUp(Client client)throws IOException {
        if(!authorized(client))return;
        deliverReplay(client,store.replay(client.boardId,client.sequence,client.gcVersion));
    }
    private void deliverReplay(Client client,BoardStore.Replay replay)throws IOException {
        if(replay.snapshot()!=null){send(client.session,Map.of("type","SYNC_SNAPSHOT","snapshot",replay.snapshot()));client.lastSnapshot=replay.snapshot().sequenceNumber();client.gcVersion=replay.snapshot().gcVersion();}
        for(var row:replay.operations())send(client.session,Map.of("type","OPERATION","sequenceNumber",row.sequenceNumber(),"operation",row.operation()));
        client.sequence=replay.through();
    }
    private void forceSnapshot(Client client)throws IOException {
        var snapshot=store.snapshot(client.boardId);
        send(client.session,Map.of("type","SYNC_SNAPSHOT","snapshot",snapshot));
        client.sequence=snapshot.sequenceNumber();client.lastSnapshot=client.sequence;client.gcVersion=snapshot.gcVersion();
    }
    private void drain(Room room){
        var replays=new HashMap<Cursor,BoardStore.Replay>();
        for(var client:List.copyOf(room.clients.values()))if(authorized(client))try{
            var replay=replays.computeIfAbsent(new Cursor(client.sequence,client.gcVersion),cursor->store.replay(client.boardId,cursor.sequence(),cursor.gcVersion()));
            deliverReplay(client,replay);
        }catch(Exception failure){close(client.session,1011,"Replay temporarily unavailable");}
    }
    /** The committed row is sufficient for clients at its immediate predecessor. */
    private void drainCommitted(Room room,BoardStore.LoggedOperation logged){
        var replays=new HashMap<Cursor,BoardStore.Replay>();
        for(var client:List.copyOf(room.clients.values()))if(authorized(client))try{
            if(client.sequence==logged.sequenceNumber()-1){
                send(client.session,Map.of("type","OPERATION","sequenceNumber",logged.sequenceNumber(),"operation",logged.operation()));
                client.sequence=logged.sequenceNumber();
            }else if(client.sequence<logged.sequenceNumber()){
                var replay=replays.computeIfAbsent(new Cursor(client.sequence,client.gcVersion),cursor->store.replay(client.boardId,cursor.sequence(),cursor.gcVersion()));
                deliverReplay(client,replay);
            }
        }catch(Exception failure){close(client.session,1011,"Replay temporarily unavailable");}
    }
    private void presence(Room room,Map<String,Object> payload,String except){
        room.peers.put((String)payload.get("sessionId"),new Seen(payload,System.currentTimeMillis()));
        broadcast(room,Map.of("type","PRESENCE","presence",payload),except);
    }
    private void receive(ClusterRelay.Event event){
        Room room=rooms.get(event.boardId());if(room==null)return;
        {room.lock.lock();try {switch(event.type()){
            case "OPERATIONS" -> drain(room);
            case "PRESENCE" -> presence(room,event.payload(),null);
            case "PRESENCE_LEFT" -> {room.peers.remove((String)event.payload().get("sessionId"));broadcast(room,Map.of("type","PRESENCE_LEFT","sessionId",event.payload().get("sessionId")),null);}
            case "PRESENCE_REQUEST" -> {for(var client:room.clients.values())if(client.presence!=null&&authorized(client))relay.publish(client.boardId,"PRESENCE",client.presence);}
            default -> { }
        }}finally{room.lock.unlock();}}
    }
    @Scheduled(fixedDelayString="${weave.sync.sweep-ms}")
    public void recoverMissedNotifications(){
        for(var room:rooms.values()){room.lock.lock();try {
            drain(room);
            var expired=room.peers.entrySet().stream().filter(e->System.currentTimeMillis()-e.getValue().at()>35000).map(Map.Entry::getKey).toList();
            for(var id:expired){room.peers.remove(id);broadcast(room,Map.of("type","PRESENCE_LEFT","sessionId",id),null);}
        }finally{room.lock.unlock();}}
        for(var session:connections.values())if(!clients.containsKey(session.getId())&&System.nanoTime()-(long)session.getAttributes().get("openedAt")>10_000_000_000L)close(session,4001,"Authentication required");
    }
    private void send(WebSocketSession session,Object payload)throws IOException {if(session.isOpen())session.sendMessage(new TextMessage(json.writeValueAsString(payload)));}
    private void broadcast(Room room,Object payload,String except){
        for(var client:List.copyOf(room.clients.values()))if(!client.presenceId.equals(except)&&authorized(client))try{send(client.session,payload);}catch(Exception failure){close(client.session,1011,"Slow or disconnected client");}
    }
    private void close(WebSocketSession session,int code,String reason){try{session.close(new CloseStatus(code,reason));}catch(IOException|IllegalStateException ignored){}}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){
        var mailbox=mailboxes.remove(session.getId());if(mailbox!=null){mailbox.messages.clear();mailbox.messages.offer(new TextMessage("{}"));}
        connections.remove(session.getId());Client client=clients.remove(session.getId());
        if(client!=null){client.room.lock.lock();try {client.room.clients.remove(session.getId());client.room.peers.remove(client.presenceId);broadcast(client.room,Map.of("type","PRESENCE_LEFT","sessionId",client.presenceId),null);relay.publish(client.boardId,"PRESENCE_LEFT",Map.of("sessionId",client.presenceId));}finally{client.room.lock.unlock();}}
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable error){close(session,1011,"Transport error");}
}
