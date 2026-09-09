import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {OperationApplier,type Element,type Operation} from '../src/crdt/core';
const cases=JSON.parse(readFileSync(new URL('../../server/target/convergence-cases.json',import.meta.url),'utf8')) as {seed:number;boardId:string;operations:Operation[];expected:Element[]}[];
function canonical(value:unknown):unknown {
 if(Array.isArray(value))return value.map(canonical);
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([,v])=>v!==undefined).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,v])=>[k,canonical(v)]));
 return value;
}
function state(elements:Element[]){return JSON.stringify(canonical(elements.map(e=>({...e,type:e.type??null,createdHlc:e.createdHlc??null,removedHlc:e.removedHlc??null}))));}
test('Java-generated traces produce the same full TypeScript snapshots',()=>{
 assert.equal(cases.length,24,'Run the Maven convergence test first');
 for(const trace of cases){
  for(const operations of [trace.operations,[...trace.operations].reverse()]){
   const board=new OperationApplier(trace.boardId);
   operations.forEach(op=>board.apply(op));
   assert.equal(state(board.snapshot()),state(trace.expected),`Java/TypeScript mismatch; seed=${trace.seed}`);
  }
 }
});
