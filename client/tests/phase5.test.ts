import test from 'node:test';
import assert from 'node:assert/strict';
import {BoardHistory} from '../src/history/history';
import {boardSvg,exportBounds} from '../src/canvas/export';
import {HybridLogicalClock,OperationApplier,type Operation} from '../src/crdt/core';

test('history scrubs backward across checkpoints, retains edits before deletion, and never changes live state',()=>{
  const boardId=crypto.randomUUID(),elementId=crypto.randomUUID(),clock=new HybridLogicalClock(crypto.randomUUID());
  const op=(body:object)=>({opId:crypto.randomUUID(),boardId,elementId,hlc:clock.tick(),...body}) as Operation;
  const operations:Operation[]=[op({type:'ELEMENT_CREATED',elementType:'RECTANGLE',fields:{x:0,y:0,width:80,height:50,color:'#425eeb'}})];
  for(let i=1;i<=600;i++)operations.push(op({type:'FIELD_UPDATED',field:'x',value:i}));
  operations.push(op({type:'ELEMENT_REMOVED'}),op({type:'FIELD_UPDATED',field:'color',value:'#222a35'}));
  const live=new OperationApplier(boardId);operations.forEach(o=>live.apply(o));const saved=live.snapshot();
  const history=new BoardHistory(boardId,operations.map((operation,i)=>({sequenceNumber:i+1,operation})));
  assert.equal(history.at(603).length,0);assert.equal(history.at(550)[0].fields.x?.value,549);
  assert.equal(history.at(1)[0].fields.x?.value,0);assert.equal(history.at(601)[0].fields.x?.value,600);
  assert.equal(history.at(0).length,0);assert.deepEqual(live.snapshot(),saved);
});

test('SVG escapes text and attributes and exports offscreen geometry',()=>{
  const boardId=crypto.randomUUID(),clock=new HybridLogicalClock(crypto.randomUUID()),board=new OperationApplier(boardId);
  board.apply({opId:crypto.randomUUID(),boardId,elementId:crypto.randomUUID(),hlc:clock.tick(),type:'ELEMENT_CREATED',elementType:'TEXT',fields:{x:-500,y:2000,width:300,height:60,text:'<script>alert("x")</script>\nA & B',color:'#425eeb'}});
  const svg=boardSvg(board.live());assert.ok(!svg.includes('<script>'));assert.ok(svg.includes('&lt;script&gt;'));assert.ok(svg.includes('A &amp; B'));assert.ok(svg.includes('y="2030"'));
  const box=exportBounds(board.live());assert.ok(box.x<-500);assert.ok(box.y+box.height>2060);assert.ok(!svg.includes('Guest'));
});
