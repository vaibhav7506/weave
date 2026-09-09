import {test} from 'node:test';

import assert from 'node:assert/strict';

import {HybridLogicalClock,FieldRegister,OperationApplier,compare,type Operation,type Stamp} from './core';

const a='00000000-0000-0000-0000-000000000001',b='ffffffff-ffff-ffff-ffff-ffffffffffff';

const stamp=(p:number,l=0,id=a):Stamp=>({physicalTime:p,logicalCounter:l,replicaId:id});

const create:Operation={opId:'create',boardId:'board',elementId:'shape',hlc:stamp(10),type:'ELEMENT_CREATED',elementType:'RECTANGLE',fields:{x:0,y:0,color:'blue'}};

const update=(id:string,field:'x'|'color',value:number|string,hlc:Stamp):Operation=>({opId:id,boardId:'board',elementId:'shape',type:'FIELD_UPDATED',field,value,hlc});

const ops:Operation[]=[create,update('move','x',42,stamp(20)),update('color','color','red',stamp(20,0,b)),update('old','x',9,stamp(15)),update('tie','x',80,stamp(20,0,b))];

function permutations<T>(xs:T[]):T[][]{return xs.length?xs.flatMap((x,i)=>permutations(xs.filter((_,j)=>i!==j)).map(rest=>[x,...rest])):[[]];}

const project=(operations:Operation[])=>{const p=new OperationApplier('board');operations.forEach(op=>p.apply(op));return p;};

test('all 120 deliveries converge; moving and recoloring both survive',()=>{const expected=project(ops).snapshot();for(const order of permutations(ops)){const p=project(order);assert.deepEqual(p.snapshot(),expected);assert.equal(p.live()[0].fields.x?.value,80);assert.equal(p.live()[0].fields.color?.value,'red');}});

test('remove before create and newer edits never resurrect; duplicate replay is idempotent',()=>{const remove:Operation={opId:'remove',boardId:'board',elementId:'shape',type:'ELEMENT_REMOVED',hlc:stamp(5)};for(const order of permutations([create,remove,ops[1]])){const p=project([...order,...order]);assert.equal(p.live().length,0);assert.equal(p.operationCount,3);assert.equal(p.snapshot()[0].fields.x?.value,42);assert.equal(p.snapshot()[0].removedHlc?.physicalTime,5);}});

test('HLC ticks, rollback, and every remote-merge branch',()=>{let now=100;const c=new HybridLogicalClock(a,()=>now);assert.deepEqual(c.tick(),stamp(100));assert.deepEqual(c.tick(),stamp(100,1));now=50;assert.deepEqual(c.tick(),stamp(100,2));assert.deepEqual(c.tick(stamp(100,8,b)),stamp(100,9));assert.deepEqual(c.tick(stamp(200,3,b)),stamp(200,4));assert.deepEqual(c.tick(stamp(150,99,b)),stamp(200,5));now=300;assert.deepEqual(c.tick(stamp(250,99,b)),stamp(300));assert.ok(compare(stamp(1,0,b),stamp(1))>0);});

test('register merge retains HLC-later value',()=>{const x=new FieldRegister('new',stamp(20)),y=new FieldRegister('old',stamp(10));assert.equal(x.mergeWith(y),x);assert.equal(y.mergeWith(x),x);});

test('immutable stroke points, defensive snapshots, wrong-board rejection',()=>{const p=project([{...create,elementType:'FREEFORM_STROKE',fields:{points:[[0,0],[1,1]],color:'blue'}}]);const copy=p.snapshot();(copy[0].fields.points!.value as unknown as number[][])[0][0]=99;assert.deepEqual(p.snapshot()[0].fields.points?.value,[[0,0],[1,1]]);assert.throws(()=>p.apply({...create,boardId:'other'}));});

test('1200 seeded operation sets, four shuffled replicas each',()=>{let seed=713;const rand=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/2**32;};for(let run=0;run<1200;run++){const stream:Operation[]=[create];for(let i=0;i<40;i++)stream.push(update('u'+i,rand()<.5?'x':'color',i,stamp(11+Math.floor(rand()*20),i,rand()<.5?a:b)));if(run%3===0)stream.push({opId:'remove',boardId:'board',elementId:'shape',type:'ELEMENT_REMOVED',hlc:stamp(1)});const expected=project(stream).snapshot();for(let replica=0;replica<4;replica++){const shuffled=[...stream,...stream.slice(0,5)];for(let i=shuffled.length-1;i>0;i--){const j=Math.floor(rand()*(i+1));[shuffled[i],shuffled[j]]=[shuffled[j],shuffled[i]];}assert.equal(JSON.stringify(project(shuffled).snapshot()),JSON.stringify(expected),`run ${run}, replica ${replica}`);}}});




test('snapshot hydration restores register merging and retains invisible tombstones',()=>{
 const removed:Operation={opId:'remove',boardId:'board',elementId:'shape',type:'ELEMENT_REMOVED',hlc:stamp(5)};
 const snapshot=JSON.parse(JSON.stringify(project([create,removed]).snapshot()));
 const hydrated=OperationApplier.fromSnapshot('board',snapshot);
 hydrated.apply(update('new','x',90,stamp(30)));
 assert.equal(hydrated.live().length,0);
 assert.equal(hydrated.snapshot()[0].fields.x?.value,90);
 const visible=OperationApplier.fromSnapshot('board',JSON.parse(JSON.stringify(project([create]).snapshot())));
 visible.apply(update('new','x',90,stamp(30)));
 assert.equal(visible.live()[0].fields.x?.value,90);
 visible.apply(update('old','x',1,stamp(2)));
 assert.equal(visible.live()[0].fields.x?.value,90);
});

