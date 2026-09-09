import {OperationApplier,type Element,type Operation} from '../crdt/core';

export type LoggedOperation={sequenceNumber:number;operation:Operation};
export class BoardHistory {
  private checkpoints=new Map<number,Element[]>();
  private projection:OperationApplier;
  private cursor=0;
  constructor(readonly boardId:string,readonly operations:LoggedOperation[]) {
    this.projection=new OperationApplier(boardId);this.checkpoints.set(0,[]);
  }
  at(sequence:number):Element[] {
    if(!Number.isInteger(sequence)||sequence<0||sequence>this.operations.length)throw Error('Invalid history position');
    if(sequence<this.cursor){
      const checkpoint=Math.floor(sequence/500)*500;
      this.projection=OperationApplier.fromSnapshot(this.boardId,this.checkpoints.get(checkpoint)??[]);
      this.cursor=this.checkpoints.has(checkpoint)?checkpoint:0;
    }
    while(this.cursor<sequence){this.projection.apply(this.operations[this.cursor++].operation);if(this.cursor%500===0){this.checkpoints.set(this.cursor,this.projection.snapshot());if(this.checkpoints.size>9)this.checkpoints.delete([...this.checkpoints.keys()].find(key=>key!==0)!);}}
    return this.projection.live();
  }
}

export async function loadHistory(boardId:string,token:string,signal:AbortSignal,onProgress:(loaded:number,total:number)=>void) {
  const operations:LoggedOperation[]=[];let through:number|undefined;
  do {
    const query=new URLSearchParams({after:String(operations.length),limit:'1000'});
    if(through!==undefined)query.set('through',String(through));
    const response=await fetch(`/api/v1/boards/${boardId}/history?${query}`,{headers:{Authorization:`Bearer ${token}`},signal});
    if(!response.ok)throw Error(response.status===401?'The invite link has expired.':'Could not load history. Try again when online.');
    const page=await response.json();through??=page.through;
    if(page.through!==through||!Array.isArray(page.operations))throw Error('Invalid history response');
    for(const row of page.operations){if(row.sequenceNumber!==operations.length+1||row.operation.boardId!==boardId)throw Error('History contains a gap');operations.push(row);}
    if(page.nextSequence!==operations.length||(!page.operations.length&&operations.length<through!))throw Error('Incomplete history');
    onProgress(operations.length,through!);
  }while(operations.length<through!);
  return new BoardHistory(boardId,operations);
}
