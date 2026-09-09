import {test} from 'node:test';
import assert from 'node:assert/strict';
import {BoardConnection} from '../src/sync/connection';
import {HybridLogicalClock,OperationApplier,type Operation} from '../src/crdt/core';
const replica='00000000-0000-0000-0000-000000000001';
class FakeSocket {
 static OPEN=1;static CONNECTING=0;static latest:FakeSocket;
 readyState=1;onopen?:()=>void;onmessage?:(event:{data:string})=>void;onclose?:(event:{code:number;reason:string})=>void;onerror?:()=>void;
 messages:unknown[]=[];
 constructor(_url:string){FakeSocket.latest=this;}
 send(data:string){this.messages.push(JSON.parse(data));}
 receive(data:unknown){this.onmessage?.({data:JSON.stringify(data)});}
 close(){this.readyState=3;this.onclose?.({code:1000,reason:''});}
}
test('compacted reconnect rebases snapshot, preserves offline outbox and never resurrects tombstones',()=>{
 const names=['window','navigator','sessionStorage','WebSocket'] as const;
 const saved=Object.fromEntries(names.map(name=>[name,Object.getOwnPropertyDescriptor(globalThis,name)]));
 const storage=new Map<string,string>();
 const values={window:new EventTarget(),navigator:{onLine:true},sessionStorage:{getItem:(key:string)=>storage.get(key)??null,setItem:(key:string,value:string)=>storage.set(key,value)},WebSocket:FakeSocket};
 for(const name of names)Object.defineProperty(globalThis,name,{value:values[name],configurable:true});
 let connection:BoardConnection|undefined;
 try{
  const hlc=(time:number)=>({physicalTime:time,logicalCounter:0,replicaId:replica});
  const create:Operation={opId:'create',boardId:'board',elementId:'shape',hlc:hlc(10),type:'ELEMENT_CREATED',elementType:'RECTANGLE',fields:{x:0}};
  const removal:Operation={opId:'remove',boardId:'board',elementId:'shape',hlc:hlc(20),type:'ELEMENT_REMOVED'};
  const update:Operation={opId:'offline-edit',boardId:'board',elementId:'shape',hlc:hlc(30),type:'FIELD_UPDATED',field:'x',value:90};
  const newShape:Operation={...create,opId:'offline-create',elementId:'new-shape',hlc:hlc(40)};
  const board=new OperationApplier('board');board.apply(create);
  connection=new BoardConnection(board,new HybridLogicalClock(replica,()=>1000),'token',1,'ws://test');
  connection.submit(update);connection.submit(newShape);connection.start();const ws=FakeSocket.latest;ws.onopen?.();
  const server=new OperationApplier('board');server.apply(create);server.apply(removal);
  ws.receive({type:'SYNC_SNAPSHOT',snapshot:{boardId:'board',name:'Test',sequenceNumber:10,elements:server.snapshot()}});
  assert.equal(connection.sequence,10);assert.equal(connection.pending.size,2);assert.deepEqual(board.live().map(e=>e.id),['new-shape']);
  assert.equal(board.snapshot().find(e=>e.id==='shape')!.fields.x!.value,90);
  ws.receive({type:'OPERATION',sequenceNumber:11,operation:{...update,opId:'tail',hlc:hlc(50),field:'color',value:'#425eeb'}});
  ws.receive({type:'SYNC_COMPLETE',sequenceNumber:11,sessionId:'session'});
  assert.equal(connection.status,'Syncing');assert.equal(connection.sequence,11);
  ws.receive({type:'OPERATION',sequenceNumber:3,operation:update});
  assert.equal(connection.sequence,11);assert.equal(connection.pending.size,1);
  // GC at the same durable sequence must replace the old projection.
  ws.receive({type:'OPERATION_REJECTED',opId:newShape.opId,elementId:newShape.elementId,code:'RETIRED_ELEMENT',message:'Deleted while away'});
  ws.receive({type:'SYNC_SNAPSHOT',snapshot:{boardId:'board',name:'Test',sequenceNumber:11,gcVersion:1,elements:[]}});
  assert.equal(connection.pending.size,0);assert.equal(connection.gcVersion,1);assert.equal(connection.status,'Synced');assert.equal(board.snapshot().length,0);
  ws.receive({type:'OPERATION',sequenceNumber:1,operation:create});
  assert.equal(board.snapshot().length,0,'old creation ACK must not resurrect a collected shape');
  connection.submit(newShape);
  ws.onclose?.({code:4008,reason:'Operation rate exceeded; queued edits retained'});
  assert.equal(connection.status,'Rate limited');assert.equal(connection.pending.size,1);
 }finally{connection?.dispose();for(const name of names){const descriptor=saved[name];if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}}
});
