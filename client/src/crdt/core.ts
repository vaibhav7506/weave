export type Stamp = Readonly<{physicalTime:number; logicalCounter:number; replicaId:string}>;

export type Kind = 'RECTANGLE'|'ELLIPSE'|'FREEFORM_STROKE'|'TEXT';

export type Value = number|string|readonly (readonly [number,number])[];

export type Field = 'x'|'y'|'width'|'height'|'color'|'strokeWidth'|'text'|'points';

const uuidPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function validateStamp(s:Stamp) {if(!Number.isSafeInteger(s.physicalTime)||s.physicalTime<0||!Number.isInteger(s.logicalCounter)||s.logicalCounter<0||s.logicalCounter>2147483647||!uuidPattern.test(s.replicaId))throw Error('Invalid clock timestamp');}

export const compare = (a:Stamp,b:Stamp) => a.physicalTime-b.physicalTime || a.logicalCounter-b.logicalCounter || (a.replicaId<b.replicaId?-1:a.replicaId>b.replicaId?1:0);

export class HybridLogicalClock {

  private physicalTime=0; private logicalCounter=0;

  constructor(readonly replicaId:string, private now:()=>number=Date.now) {

    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(replicaId)) throw Error('Replica ID must be a canonical lowercase UUID');

  }

  tick(remote?:Stamp):Stamp {

    if(remote)validateStamp(remote);

    const previous=this.physicalTime, p=Math.max(previous,this.now(),remote?.physicalTime??0);

    const l=remote && p===previous && p===remote.physicalTime ? Math.max(this.logicalCounter,remote.logicalCounter)+1 : p===previous ? this.logicalCounter+1 : remote && p===remote.physicalTime ? remote.logicalCounter+1 : 0;

    if(!Number.isSafeInteger(p)||p<0||l>2147483647) throw Error('Clock overflow');

    this.physicalTime=p; this.logicalCounter=l;

    return Object.freeze({physicalTime:p,logicalCounter:l,replicaId:this.replicaId});

  }

}

export class FieldRegister<T> {

  constructor(readonly value:T, readonly hlc:Stamp) {}

  mergeWith(remote:FieldRegister<T>) {return compare(remote.hlc,this.hlc)>0?remote:this;}

}

export type Operation = Readonly<{opId:string;boardId:string;elementId:string;hlc:Stamp} & (

  {type:'ELEMENT_CREATED';elementType:Kind;fields:Partial<Record<Field,Value>>} |

  {type:'FIELD_UPDATED';field:Exclude<Field,'points'>;value:Value} |

  {type:'ELEMENT_REMOVED'})>;

export type Element = {id:string;boardId:string;type?:Kind;createdHlc?:Stamp;removedHlc?:Stamp;fields:Partial<Record<Field,FieldRegister<Value>>>};

export class OperationApplier {

  private elements = new Map<string,Element>();

  private operations = new Map<string,Operation>();

  constructor(readonly boardId:string) {}

  static fromSnapshot(boardId:string,elements:Element[]):OperationApplier {

    const board=new OperationApplier(boardId);

    for(const source of elements){

      if(source.boardId!==boardId)throw Error('Wrong board in snapshot');

      const e=structuredClone(source);

      // JSON snapshots contain data objects; restore register methods before future merges.

      for(const [field,register] of Object.entries(e.fields))if(register)e.fields[field as Field]=new FieldRegister(register.value,register.hlc);

      board.elements.set(e.id,e);

    }

    return board;

  }

  replaceSnapshot(elements:Element[]) {
    const hydrated=OperationApplier.fromSnapshot(this.boardId,elements);
    this.elements=hydrated.elements;this.operations.clear();
  }
  apply(input:Operation) {

    validateStamp(input.hlc);

    if(input.type==='FIELD_UPDATED' && !['x','y','width','height','color','strokeWidth','text'].includes(input.field))throw Error('Invalid or immutable field');

    if(input.boardId!==this.boardId) throw Error('Wrong board');

    if(this.operations.has(input.opId)) return;

    // Own incoming data: later caller mutation must not change a projection.

    const op=structuredClone(input);

    this.operations.set(op.opId,op);

    const e=this.elements.get(op.elementId)??{id:op.elementId,boardId:this.boardId,fields:{}};

    this.elements.set(e.id,e);

    if(op.type==='ELEMENT_REMOVED') {

      if(!e.removedHlc || compare(op.hlc,e.removedHlc)>0) e.removedHlc=op.hlc;

    } else if(op.type==='ELEMENT_CREATED') {

      // IDs denote a single creation. If reused, the earliest creation owns identity/points.

      if(!e.createdHlc || compare(op.hlc,e.createdHlc)<0) {

        e.type=op.elementType;e.createdHlc=op.hlc;

        delete e.fields.points;

        if(op.elementType==='FREEFORM_STROKE' && op.fields.points) e.fields.points=new FieldRegister(op.fields.points,op.hlc);

      }

      for(const [field,value] of Object.entries(op.fields)) if(field!=='points') this.merge(e,field as Field,value,op.hlc);

    } else this.merge(e,op.field,op.value,op.hlc);

  }

  private merge(e:Element,field:Field,value:Value,hlc:Stamp) {

    const next=new FieldRegister(value,hlc); e.fields[field]=e.fields[field]?.mergeWith(next)??next;

  }

  snapshot():Element[] {return structuredClone([...this.elements.values()].sort((a,b)=>a.id<b.id?-1:a.id>b.id?1:0).map(e=>({id:e.id,boardId:e.boardId,type:e.type,createdHlc:e.createdHlc,removedHlc:e.removedHlc,fields:Object.fromEntries(Object.entries(e.fields).sort(([a],[b])=>a<b?-1:a>b?1:0))})));}

  live():Element[] {return this.snapshot().filter(e=>e.createdHlc&&!e.removedHlc);}

  get operationCount(){return this.operations.size;}

}

