import {HybridLogicalClock,OperationApplier,type Element,type Operation} from '../crdt/core';

export type Snapshot={boardId:string;name:string;sequenceNumber:number;elements:Element[];gcVersion?:number};

export type Presence={sessionId:string;name:string;color:string;x:number;y:number;selection:string};

export type SyncStatus='Connecting'|'Synced'|'Syncing'|'Offline'|'Reconnecting'|'Link expired'|'Sync error'|'Rate limited';

export class BoardConnection {

  readonly peers=new Map<string,Presence>();

  readonly pending=new Map<string,Operation>();

  status:SyncStatus='Connecting';

  error='';

  sequence:number;

  private socket?:WebSocket;

  private ready=false;
  private sent=new Set<string>();
  private pump?:ReturnType<typeof setInterval>;

  private stopped=false;
  private snapshotFloor:number;
  gcVersion=0;
  private stopAfterSnapshot=false;
  private ackTimer?:ReturnType<typeof setInterval>;
  private lastAck=-1;

  private retry?:ReturnType<typeof setTimeout>;

  private heartbeat?:ReturnType<typeof setInterval>;

  private lastReceived=Date.now();

  private attempts=0;

  private listeners=new Set<()=>void>();

  private received=new Set<number>();

  private lastPresence=0;

  private ownPresence={name:'Guest '+Math.floor(100+Math.random()*900),color:['#425eeb','#b64b2c','#18765b','#8b49b5'][Math.floor(Math.random()*4)],x:-1000,y:-1000,selection:''};

  private readonly storageKey:string;

  constructor(readonly board:OperationApplier,readonly clock:HybridLogicalClock,readonly token:string,sequence:number,private readonly url:string) {

    this.sequence=sequence;this.snapshotFloor=sequence;

    this.storageKey='weave-outbox:'+board.boardId;

    // Per-tab storage survives reload without different editors sharing a mutable outbox.

    const stored=sessionStorage.getItem(this.storageKey);

    if(stored)for(const op of JSON.parse(stored) as Operation[]){this.pending.set(op.opId,op);clock.tick(op.hlc);board.apply(op);}

    window.addEventListener('online',this.online);

    window.addEventListener('offline',this.offline);

  }

  subscribe=(listener:()=>void)=>{this.listeners.add(listener);return()=>{this.listeners.delete(listener);};};

  private notify(){for(const listener of this.listeners)listener();}

  private save(){sessionStorage.setItem(this.storageKey,JSON.stringify([...this.pending.values()]));}

  submit(op:Operation){

    this.pending.set(op.opId,op);

    try{this.save();}catch {this.error='Tab storage is full. Keep this tab open until all edits are synced.';}

    this.board.apply(op);

    if(this.ready)this.status='Syncing';

    this.notify();

  }

  private send(value:unknown){

    try{this.socket?.send(JSON.stringify(value));}catch{this.socket?.close();}

  }

  start=()=>{

    if(this.stopped||this.socket&&(this.socket.readyState===WebSocket.OPEN||this.socket.readyState===WebSocket.CONNECTING))return;

    if(!navigator.onLine){this.status='Offline';this.notify();return;}

    this.status=this.attempts?'Reconnecting':'Connecting';this.notify();

    const socket=new WebSocket(this.url);this.socket=socket;this.ready=false;

    socket.onopen=()=>{
      this.sent.clear();clearInterval(this.pump);
      this.pump=setInterval(()=>{if(this.ready&&socket.readyState===WebSocket.OPEN){const next=[...this.pending.values()].find(op=>!this.sent.has(op.opId));if(next){this.sent.add(next.opId);this.send({type:'OPERATION',operation:next});}}},20);

      if(this.socket!==socket)return;

      this.lastReceived=Date.now();

      this.send({type:'SYNC_REQUEST',boardId:this.board.boardId,editToken:this.token,sinceSequence:this.sequence,gcVersion:this.gcVersion});
      clearInterval(this.ackTimer);this.lastAck=-1;
      this.ackTimer=setInterval(()=>this.acknowledge(),1000);

      clearInterval(this.heartbeat);

      this.heartbeat=setInterval(()=>{if(Date.now()-this.lastReceived>25000)socket.close();else if(this.ready)this.send({type:'PING'});},10000);

    };

    socket.onmessage=event=>{

      if(this.socket!==socket)return;

      this.lastReceived=Date.now();

      try {

        const message=JSON.parse(event.data);

        switch(message.type){

          case 'SYNC_SNAPSHOT': {
            const snapshot=message.snapshot as Snapshot;
            if(snapshot.boardId!==this.board.boardId||!Number.isSafeInteger(snapshot.sequenceNumber)||snapshot.sequenceNumber<this.sequence)throw Error('Invalid sync snapshot');
            this.board.replaceSnapshot(snapshot.elements);
            for(const element of snapshot.elements){
              if(element.createdHlc)this.clock.tick(element.createdHlc);
              if(element.removedHlc)this.clock.tick(element.removedHlc);
              for(const register of Object.values(element.fields))if(register)this.clock.tick(register.hlc);
            }
            for(const op of this.pending.values())this.board.apply(op);
            this.sequence=snapshot.sequenceNumber;this.snapshotFloor=this.sequence;this.gcVersion=snapshot.gcVersion??0;this.received.clear();
            if(this.stopAfterSnapshot){this.stopped=true;this.ready=false;this.status='Sync error';socket.close();}else if(this.ready)this.status=this.pending.size?'Syncing':'Synced';
            break;
          }
          case 'OPERATION_REJECTED':{
            for(const [id,op] of this.pending)if(id===message.opId||(message.code==='RETIRED_ELEMENT'&&op.elementId===message.elementId)){this.pending.delete(id);this.sent.delete(id);}
            this.save();this.error=message.message;
            if(message.code==='CLOCK_SKEW'){this.stopAfterSnapshot=true;this.ready=false;}
            break;
          }
          case 'OPERATION':{

            const op=message.operation as Operation;

            const seq=message.sequenceNumber as number;

            if(!Number.isSafeInteger(seq)||seq<1)throw Error('Invalid server sequence');

            this.clock.tick(op.hlc);if(seq>this.snapshotFloor)this.board.apply(op);

            this.pending.delete(op.opId);this.sent.delete(op.opId);this.save();

            if(seq>this.sequence)this.received.add(seq);

            // Advance only across a contiguous committed prefix, never just max(received).

            while(this.received.delete(this.sequence+1))this.sequence++;

            if(this.ready)this.status=this.pending.size?'Syncing':'Synced';

            break;

          }

          case 'SYNC_COMPLETE':

            if(this.sequence!==message.sequenceNumber)throw Error('Incomplete replay');

            this.ready=true;this.attempts=0;

            // The paced outbox pump sends pending operations after replay completes.

            this.status=this.pending.size?'Syncing':'Synced';

            this.send({type:'PRESENCE',presence:this.ownPresence});this.acknowledge();

            break;

          case 'PRESENCE':this.peers.set(message.presence.sessionId,message.presence);break;

          case 'PRESENCE_LEFT':this.peers.delete(message.sessionId);break;

          case 'ERROR':this.error=message.message;break;

          case 'PONG':break;

          default:throw Error('Unknown server message');

        }

      }catch(error){this.error=String(error);this.status='Sync error';this.stopped=true;socket.close();}

      this.notify();

    };

    socket.onclose=event=>{

      if(this.socket!==socket)return;

      this.ready=false;this.peers.clear();clearInterval(this.heartbeat);clearInterval(this.pump);clearInterval(this.ackTimer);this.socket=undefined;

      if(event.code===4001||event.code===4002||event.code===4008){this.stopped=true;this.status=event.code===4001?'Link expired':event.code===4008?'Rate limited':'Sync error';this.error=event.reason;}

      if(!this.stopped){this.status=navigator.onLine?'Reconnecting':'Offline';this.attempts++;this.retry=setTimeout(this.start,Math.min(5000,300*2**Math.min(this.attempts,4)));}

      this.notify();

    };

    socket.onerror=()=>socket.close();

  };

  private acknowledge(){if(this.ready&&this.sequence!==this.lastAck){this.lastAck=this.sequence;this.send({type:'ACK',sequenceNumber:this.sequence});}}

  get identity(){return this.ownPresence;}
  setName(name:string){this.ownPresence={...this.ownPresence,name:name.trim().slice(0,40)||'Guest'};this.presence(this.ownPresence.x,this.ownPresence.y,this.ownPresence.selection,true);this.notify();}

  presence(x:number,y:number,selection:string,force=false){

    this.ownPresence={...this.ownPresence,x,y,selection};

    if(this.ready&&(force||Date.now()-this.lastPresence>50)){this.lastPresence=Date.now();this.send({type:'PRESENCE',presence:this.ownPresence});}

  }

  private online=()=>{clearTimeout(this.retry);this.start();};

  private offline=()=>{this.status='Offline';this.socket?.close();this.notify();};

  dispose(){this.stopped=true;clearInterval(this.ackTimer);clearInterval(this.pump);clearTimeout(this.retry);clearInterval(this.heartbeat);window.removeEventListener('online',this.online);window.removeEventListener('offline',this.offline);this.socket?.close();}

}

export async function openBoard(clock:HybridLogicalClock){

  const query=new URLSearchParams(location.search);

  let id=query.get('board');

  let token=new URLSearchParams(location.hash.slice(1)).get('token');

  if(!id){

    const response=await fetch('/api/v1/boards',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:'Untitled board'})});

    if(!response.ok)throw Error('Could not create a board. Check that the Weave server is running.');

    const created=await response.json();id=created.id;token=created.editToken;

    history.replaceState(null,'',`?board=${encodeURIComponent(id!)}#token=${encodeURIComponent(token!)}`);

  }

  if(!token)throw Error('This board needs its complete invite link, including the edit token.');

  const response=await fetch(`/api/v1/boards/${encodeURIComponent(id!)}/snapshot`,{headers:{Authorization:`Bearer ${token}`}});

  if(!response.ok)throw Error(response.status===401?'This invite link is invalid or expired.':'Could not load the board. Check the server and try again.');

  const snapshot=await response.json() as Snapshot;

  const board=OperationApplier.fromSnapshot(snapshot.boardId,snapshot.elements);

  for(const element of snapshot.elements){

    if(element.createdHlc)clock.tick(element.createdHlc);

    if(element.removedHlc)clock.tick(element.removedHlc);

    for(const register of Object.values(element.fields))if(register)clock.tick(register.hlc);

  }

  const url=new URL('/ws',location.href);url.protocol=location.protocol==='https:'?'wss:':'ws:';

  const connection=new BoardConnection(board,clock,token,snapshot.sequenceNumber,url.toString());connection.gcVersion=snapshot.gcVersion??0;
  return {board,name:snapshot.name,connection};

}

