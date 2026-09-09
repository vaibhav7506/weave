import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {HybridLogicalClock,OperationApplier,type Operation} from '../src/crdt/core';

const base=process.env.WEAVE_LOAD_URL??'http://127.0.0.1:8080';
const counts=(process.env.WEAVE_LOAD_CLIENTS??'5,10,20,40').split(',').map(Number);
const rounds=Number(process.env.WEAVE_LOAD_ROUNDS??3),ticks=Number(process.env.WEAVE_LOAD_TICKS??15);
const interval=200;
const sleep=(ms:number)=>new Promise(resolve=>setTimeout(resolve,ms));
const quantile=(values:number[],p:number)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.max(0,Math.ceil(p*sorted.length)-1)]??0;};
async function until(condition:()=>boolean,errors:string[],timeout=120000){const deadline=Date.now()+timeout;while(!condition()){if(errors.length)throw Error(errors.join('; '));if(Date.now()>deadline)throw Error('Convergence timed out');await sleep(10);}}
const canonical=(board:OperationApplier)=>JSON.stringify(board.snapshot().map(e=>({...e,type:e.type??null,createdHlc:e.createdHlc??null,removedHlc:e.removedHlc??null})));
const results:object[]=[];
for(const count of counts)for(let round=1;round<=rounds;round++){
  const response=await fetch(base+'/api/v1/boards',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:`Load ${count} editors / round ${round}`})});assert.equal(response.status,201);
  const invite=await response.json() as {id:string;editToken:string};
  const sent=new Map<string,{time:number;sender:number;received:number}>(),samples:number[]=[],allRecipients:number[]=[],errors:string[]=[];
  let lastReceived=0,closing=false;
  const clients=Array.from({length:count},(_,i)=>{
    const ws=new WebSocket(base.replace(/^http/,'ws')+'/ws');
    const client={ws,ready:false,board:new OperationApplier(invite.id),seen:new Set<string>(),clock:new HybridLogicalClock(crypto.randomUUID()),elementId:crypto.randomUUID()};
    ws.onopen=()=>ws.send(JSON.stringify({type:'SYNC_REQUEST',boardId:invite.id,editToken:invite.editToken,sinceSequence:0}));
    ws.onmessage=event=>{const message=JSON.parse(String(event.data));
      if(message.type==='SYNC_COMPLETE')client.ready=true;
      if(message.type==='OPERATION'){
        const op=message.operation as Operation;client.clock.tick(op.hlc);client.board.apply(op);
        if(!client.seen.has(op.opId)){
          client.seen.add(op.opId);const entry=sent.get(op.opId);
          if(entry){const now=performance.now();lastReceived=now;if(entry.sender!==i)samples.push(now-entry.time);if(++entry.received===count)allRecipients.push(now-entry.time);}
        }
      }
      if(message.type==='ERROR')errors.push(message.message);
    };
    ws.onerror=()=>{if(!closing)errors.push(`Client ${i} socket error`);};
    ws.onclose=e=>{if(!closing)errors.push(`Client ${i} closed ${e.code}: ${e.reason}`);};return client;
  });
  const heartbeat=setInterval(()=>clients.forEach(c=>{if(c.ready&&c.ws.readyState===WebSocket.OPEN)c.ws.send(JSON.stringify({type:'PING'}));}),10000);
  try{
    await until(()=>clients.every(c=>c.ready),errors);
    // Initial creations are excluded: every measured edit is a small field update.
    clients.forEach(c=>c.ws.send(JSON.stringify({type:'OPERATION',operation:{opId:crypto.randomUUID(),boardId:invite.id,elementId:c.elementId,hlc:c.clock.tick(),type:'ELEMENT_CREATED',elementType:'RECTANGLE',fields:{x:0,y:0,width:80,height:40,color:'#425eeb'}}})));
    await until(()=>clients.every(c=>c.seen.size===count),errors);await sleep(500);
    const start=performance.now();let lastSent=start;
    for(let tick=0;tick<ticks;tick++){
      await sleep(Math.max(0,start+tick*interval-performance.now()));
      clients.forEach((c,i)=>{
        const op:Operation={opId:crypto.randomUUID(),boardId:invite.id,elementId:c.elementId,hlc:c.clock.tick(),type:'FIELD_UPDATED',field:'x',value:tick*10+i};
        lastSent=performance.now();sent.set(op.opId,{time:lastSent,sender:i,received:0});c.ws.send(JSON.stringify({type:'OPERATION',operation:op}));
      });
    }
    await until(()=>[...sent.values()].every(entry=>entry.received===count),errors);
    const expected=canonical(clients[0].board);clients.forEach(c=>assert.equal(canonical(c.board),expected));
    const persisted=await fetch(base+`/api/v1/boards/${invite.id}/snapshot`,{headers:{Authorization:`Bearer ${invite.editToken}`}});assert.equal(persisted.status,200);
    const snapshot=await persisted.json() as {elements:Parameters<typeof OperationApplier.fromSnapshot>[1];sequenceNumber:number};
    assert.equal(canonical(OperationApplier.fromSnapshot(invite.id,snapshot.elements)),expected);assert.equal(snapshot.sequenceNumber,count*(ticks+1));
    const result={clients:count,round,operations:sent.size,remoteDeliveries:samples.length,offeredOpsPerSecond:count*1000/interval,sendDurationMs:lastSent-start,broadcastP50Ms:quantile(samples,.5),broadcastP95Ms:quantile(samples,.95),broadcastP99Ms:quantile(samples,.99),allRecipientsP95Ms:quantile(allRecipients,.95),convergenceAfterLastSendMs:Math.max(0,lastReceived-lastSent),equalClientAndDatabaseSnapshots:true};
    results.push(result);await fs.mkdir('test-results',{recursive:true});await fs.writeFile('test-results/phase5-load-progress.json',JSON.stringify(results,null,2));console.log(JSON.stringify(result));
  }finally{closing=true;clearInterval(heartbeat);clients.forEach(c=>c.ws.close());}
}
await fs.mkdir('test-results',{recursive:true});
await fs.writeFile('test-results/phase5-load.json',JSON.stringify({runAt:new Date().toISOString(),environment:{platform:os.platform(),release:os.release(),cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,memoryGiB:os.totalmem()/1024**3,node:process.version,target:base},method:{rounds,ticks,intervalMs:interval,description:'Single server, one fresh board per round, Node raw WebSocket clients and server on same host. Five field updates/sec/editor in synchronized bursts. Initial creates and 500ms warm-up excluded. Latency is monotonic send to remote client apply, including DB commit; sender acknowledgments excluded. Final states compared on every client and PostgreSQL. No browser render or internet latency.'},results},null,2)+'\n');
