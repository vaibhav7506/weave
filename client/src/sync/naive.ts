import {OperationApplier,type Element,type Operation,HybridLogicalClock} from '../crdt/core';
import type {BoardConnection} from './connection';

/** Deliberately sends stale WHOLE snapshots. Never submits demo edits to the real log. */
export class NaiveConnection {
  readonly board:OperationApplier;
  pending=0;error='';
  private timer?:ReturnType<typeof setTimeout>;
  private stopped=false;
  private chain:Promise<unknown>=Promise.resolve();
  private generation=0;
  private url:string;
  private headers:Record<string,string>;
  constructor(boardId:string,token:string,private changed:()=>void,private clock?:HybridLogicalClock){
    this.board=new OperationApplier(boardId);this.url=`/api/v1/boards/${boardId}/comparison/naive`;
    this.headers={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
  }
  private async request(method='GET',elements?:Element[]){
    const response=await fetch(this.url,{method,headers:this.headers,body:elements?JSON.stringify({elements}):undefined});
    if(!response.ok)throw Error('Demo sync failed. Reopen the demo when online.');
    const result=await response.json() as {elements:Element[]};
    for(const element of result.elements){if(element.createdHlc)this.clock?.tick(element.createdHlc);if(element.removedHlc)this.clock?.tick(element.removedHlc);for(const register of Object.values(element.fields))if(register)this.clock?.tick(register.hlc);}
    return result;
  }
  async start(){const result=await this.request();this.board.replaceSnapshot(result.elements);this.changed();this.poll();}
  private poll=async()=>{
    if(this.stopped)return;
    const generation=this.generation;
    try{if(!this.pending){const result=await this.request();if(!this.stopped&&!this.pending&&generation===this.generation){this.board.replaceSnapshot(result.elements);this.error='';this.changed();}}}
    catch(error){this.error=String(error);this.changed();}
    if(!this.stopped)this.timer=setTimeout(this.poll,750);
  };
  replace(elements:Element[]){
    this.generation++;this.pending++;this.board.replaceSnapshot(elements);this.changed();
    const work=this.chain.then(async()=>{await this.request('PUT',elements);this.error='';}).catch(error=>{this.error=String(error);throw error;}).finally(()=>{this.pending--;this.changed();});
    this.chain=work.catch(()=>{});return work;
  }
  submit(op:Operation){this.board.apply(op);void this.replace(this.board.snapshot()).catch(()=>{});}
  dispose(){this.stopped=true;clearTimeout(this.timer);}
}

export async function runComparison(connection:BoardConnection,naive:NaiveConnection|undefined,clock:HybridLogicalClock,reverse:boolean) {
  const boardId=connection.board.boardId,elementId=crypto.randomUUID();
  const create:Operation={opId:crypto.randomUUID(),boardId,elementId,hlc:clock.tick(),type:'ELEMENT_CREATED',elementType:'RECTANGLE',fields:{x:240,y:200,width:150,height:90,color:'#425eeb',strokeWidth:4}};
  const move:Operation={opId:crypto.randomUUID(),boardId,elementId,hlc:clock.tick(),type:'FIELD_UPDATED',field:'x',value:440};
  const recolor:Operation={opId:crypto.randomUUID(),boardId,elementId,hlc:clock.tick(),type:'FIELD_UPDATED',field:'color',value:'#e06c48'};
  const edits=reverse?[recolor,move]:[move,recolor];
  if(naive){
    const base=OperationApplier.fromSnapshot(boardId,naive.board.snapshot());base.apply(create);
    await naive.replace(base.snapshot());
    // Both editors start at the SAME version, before either independent edit arrives.
    for(const edit of edits){const replica=OperationApplier.fromSnapshot(boardId,base.snapshot());replica.apply(edit);await naive.replace(replica.snapshot());}
  }else {connection.submit(create);edits.forEach(op=>connection.submit(op));}
  return naive?(reverse?'Naive: the move survived; the color change was lost.':'Naive: the color survived; the move was lost.'):'CRDT: the move and color change both survived.';
}
