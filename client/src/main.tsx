import React, {useEffect,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {HybridLogicalClock,OperationApplier,type Element,type Field,type Kind,type Operation,type Value} from './crdt/core';
import {BoardConnection,openBoard} from './sync/connection';
import {paint,bounds,number,type Box} from './canvas/render';
import {exportBoard} from './canvas/export';
import {loadHistory,type BoardHistory} from './history/history';
import {NaiveConnection,runComparison} from './sync/naive';
import './style.css';
import QRCode from 'qrcode';
let boardId:string;
let board:OperationApplier;
let connection:BoardConnection;
let boardName:string;
const clock=new HybridLogicalClock(crypto.randomUUID());
type Tool='SELECT'|Kind;
function App(){
 const canvas=useRef<HTMLCanvasElement>(null),[revision,setRevision]=useState(0),[tool,setTool]=useState<Tool>('SELECT'),[selected,setSelected]=useState<string>(),[color,setColor]=useState('#425eeb'),[stroke,setStroke]=useState(2),[text,setText]=useState('Hello, Weave'),[size,setSize]=useState({width:1000,height:700});
 const [invite,setInvite]=useState<{url:string;qr:string}>(),[inviting,setInviting]=useState(false);
 useEffect(()=>{if(!invite)return;const listener=(event:KeyboardEvent)=>{if(event.key==='Escape')setInvite(undefined);if(event.key==='Tab'){const items=[...document.querySelectorAll<HTMLElement>('.invite-dialog button,.invite-dialog input')];const first=items[0],last=items[items.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}};window.addEventListener('keydown',listener);return()=>window.removeEventListener('keydown',listener);},[invite]);
 const created=useRef<string[]>([]);
 const gesture=useRef<{start:[number,number];last:[number,number];points:[number,number][];id?:string;original?:Box;resize?:boolean;kind?:Kind}|undefined>(undefined);
 const [draft,setDraft]=useState<Element>();
 const [historyData,setHistoryData]=useState<BoardHistory>(),[historyPosition,setHistoryPosition]=useState(0),[historyLoading,setHistoryLoading]=useState(''),[notice,setNotice]=useState(''),[mode,setMode]=useState<'crdt'|'naive'>('crdt'),[reverse,setReverse]=useState(false),[demoBusy,setDemoBusy]=useState(false);
 const historyRequest=useRef<AbortController|undefined>(undefined),naive=useRef<NaiveConnection|undefined>(undefined);
 const readonly=!!historyData||!!historyLoading||demoBusy;
 const activeBoard=mode==='naive'&&naive.current?naive.current.board:board;
 const elements=historyData?historyData.at(historyPosition):activeBoard.live(),selection=readonly?undefined:elements.find(e=>e.id===selected);
 const refresh=()=>setRevision(r=>r+1);
 useEffect(()=>{const unsubscribe=connection.subscribe(refresh);connection.start();return unsubscribe;},[]);
 function apply(op:Operation){if(readonly)return;if(mode==='naive')naive.current?.submit(op);else connection.submit(op);refresh();}
 function update(id:string,field:Exclude<Field,'points'>,value:Value){apply({opId:crypto.randomUUID(),boardId,elementId:id,hlc:clock.tick(),type:'FIELD_UPDATED',field,value});}
 function remove(id:string){apply({opId:crypto.randomUUID(),boardId,elementId:id,hlc:clock.tick(),type:'ELEMENT_REMOVED'});setSelected(undefined);}
 function undo(){const live=new Set(activeBoard.live().map(e=>e.id));let id;while((id=created.current.pop()))if(live.has(id)){remove(id);break;}}
 useEffect(()=>{const el=canvas.current!;const resize=new ResizeObserver(([entry])=>setSize({width:entry.contentRect.width,height:entry.contentRect.height}));resize.observe(el);return()=>resize.disconnect();},[]);
 useEffect(()=>{
  const el=canvas.current!,ratio=devicePixelRatio||1;el.width=Math.round(size.width*ratio);el.height=Math.round(size.height*ratio);const ctx=el.getContext('2d')!;ctx.scale(ratio,ratio);ctx.clearRect(0,0,size.width,size.height);
  elements.forEach(e=>paint(ctx,e));if(draft)paint(ctx,draft);
  for(const peer of (mode==='crdt'&&!historyData?connection.peers.values():[])){
   ctx.fillStyle=peer.color;ctx.beginPath();ctx.moveTo(peer.x,peer.y);ctx.lineTo(peer.x+5,peer.y+17);ctx.lineTo(peer.x+10,peer.y+10);ctx.closePath();ctx.fill();ctx.font='14px system-ui';ctx.textBaseline='top';ctx.fillText(peer.name,peer.x+14,peer.y+12);
   const chosen=elements.find(e=>e.id===peer.selection);if(chosen){const b=bounds(chosen);ctx.strokeStyle=peer.color;ctx.setLineDash([3,3]);ctx.strokeRect(b.x-3,b.y-3,b.width+6,b.height+6);ctx.setLineDash([]);}
  }
  if(selection){const b=bounds(selection);ctx.strokeStyle='#425eeb';ctx.lineWidth=1;ctx.setLineDash([5,4]);ctx.strokeRect(b.x-5,b.y-5,b.width+10,b.height+10);ctx.setLineDash([]);ctx.fillStyle='white';ctx.fillRect(b.x+b.width-5,b.y+b.height-5,10,10);ctx.strokeRect(b.x+b.width-5,b.y+b.height-5,10,10);}
 },[revision,draft,selected,size,historyData,historyPosition,mode]);
 useEffect(()=>{const key=(e:KeyboardEvent)=>{if((e.target as HTMLElement).matches('input,textarea,select')||(readonly&&e.key!=='Escape'))return;if(e.key==='Delete'||e.key==='Backspace'){e.preventDefault();if(selected)remove(selected);}if((e.ctrlKey||e.metaKey)&&e.key==='z'){e.preventDefault();undo();}if(e.key==='Escape'){gesture.current=undefined;setDraft(undefined);setSelected(undefined);setTool('SELECT');}};window.addEventListener('keydown',key);return()=>window.removeEventListener('keydown',key);},[selected,readonly,mode]);
 const point=(e:React.PointerEvent<HTMLCanvasElement>):[number,number]=>{const r=e.currentTarget.getBoundingClientRect();return[e.clientX-r.left,e.clientY-r.top];};
 function makeDraft(g:NonNullable<typeof gesture.current>):Element {
  let xs=[g.start[0],g.last[0]],ys=[g.start[1],g.last[1]];
  if(g.kind==='FREEFORM_STROKE'){xs=g.points.map(p=>p[0]);ys=g.points.map(p=>p[1]);}
  const x=Math.min(...xs),y=Math.min(...ys),width=Math.max(1,Math.max(...xs)-x),height=Math.max(1,Math.max(...ys)-y);
  const values:Partial<Record<Field,Value>>={x,y,width,height,color,strokeWidth:stroke};
  if(g.kind==='FREEFORM_STROKE')values.points=g.points.map(([px,py])=>[(px-x)/width,(py-y)/height]);
  if(g.kind==='TEXT'){values.x=g.start[0];values.y=g.start[1];values.text=text.trim()||'Text';const ctx=canvas.current!.getContext('2d')!;ctx.font='24px system-ui';values.width=Math.max(20,ctx.measureText(String(values.text)).width);values.height=30;}
  const hlc={physicalTime:0,logicalCounter:0,replicaId:clock.replicaId};
  return {id:'draft',boardId,type:g.kind,createdHlc:hlc,fields:Object.fromEntries(Object.entries(values).map(([k,value])=>[k,{value,hlc}]))};
 }
 function down(e:React.PointerEvent<HTMLCanvasElement>){if(e.button!==0||readonly)return;const p=point(e);e.currentTarget.setPointerCapture(e.pointerId);
  if(tool==='SELECT'){
   const b=selection&&bounds(selection),resize=!!b&&Math.hypot(p[0]-b.x-b.width,p[1]-b.y-b.height)<14;
   const hit=resize?selection:[...elements].reverse().find(el=>{const r=bounds(el);return p[0]>=r.x-6&&p[0]<=r.x+r.width+6&&p[1]>=r.y-6&&p[1]<=r.y+r.height+6;});
   setSelected(hit?.id);gesture.current={start:p,last:p,points:[p],id:hit?.id,original:hit&&bounds(hit),resize};
   if(hit){setColor(String(hit.fields.color?.value??color));setStroke(number(hit,'strokeWidth',2));}
  }else{setSelected(undefined);gesture.current={start:p,last:p,points:[p],kind:tool};setDraft(makeDraft(gesture.current));}
 }
 function move(e:React.PointerEvent<HTMLCanvasElement>){const p=point(e);if(mode==='crdt'&&!historyData)connection.presence(p[0],p[1],selected??'');if(readonly)return;const g=gesture.current;if(!g)return;g.last=point(e);
  if(g.id&&g.original){const dx=g.last[0]-g.start[0],dy=g.last[1]-g.start[1];if(g.resize){update(g.id,'width',Math.max(8,g.original.width+dx));update(g.id,'height',Math.max(8,g.original.height+dy));}else{update(g.id,'x',g.original.x+dx);update(g.id,'y',g.original.y+dy);}}
  else if(g.kind){g.points.push(g.last);setDraft(makeDraft(g));}
 }
 function up(e:React.PointerEvent<HTMLCanvasElement>){if(readonly)return;const g=gesture.current;if(!g)return;g.last=point(e);if(g.kind){g.points.push(g.last);const d=makeDraft(g);if(g.kind==='TEXT'||Math.hypot(g.last[0]-g.start[0],g.last[1]-g.start[1])>2||g.points.length>3){const id=crypto.randomUUID();apply({opId:crypto.randomUUID(),boardId,elementId:id,hlc:clock.tick(),type:'ELEMENT_CREATED',elementType:g.kind,fields:Object.fromEntries(Object.entries(d.fields).map(([k,r])=>[k,r!.value]))});created.current.push(id);setSelected(id);setTool('SELECT');}}gesture.current=undefined;setDraft(undefined);if(e.currentTarget.hasPointerCapture(e.pointerId))e.currentTarget.releasePointerCapture(e.pointerId);}
 const tools:[Tool,string,string][]=[['SELECT','↖','Select'],['RECTANGLE','□','Rectangle'],['ELLIPSE','○','Ellipse'],['FREEFORM_STROKE','✎','Pen'],['TEXT','T','Text']];
 async function showHistory(){
  const request=new AbortController();historyRequest.current?.abort();historyRequest.current=request;setHistoryLoading('Loading history…');setNotice('');setSelected(undefined);gesture.current=undefined;setDraft(undefined);
  try{const result=await loadHistory(boardId,connection.token,request.signal,(loaded,total)=>setHistoryLoading(`Loading history ${loaded.toLocaleString()} / ${total.toLocaleString()}`));if(request.signal.aborted)return;setHistoryData(result);setHistoryPosition(result.operations.length);}
  catch(error){if(!request.signal.aborted)setNotice(String(error));}finally{if(historyRequest.current===request)setHistoryLoading('');}
 }
 function goLive(){historyRequest.current?.abort();setHistoryLoading('');setHistoryData(undefined);setSelected(undefined);}
 async function toggleMode(){
  setNotice('');setSelected(undefined);gesture.current=undefined;setDraft(undefined);created.current=[];goLive();
  if(mode==='naive'){naive.current?.dispose();naive.current=undefined;setMode('crdt');return;}
  setDemoBusy(true);const demo=new NaiveConnection(boardId,connection.token,refresh,clock);
  try{await demo.start();naive.current=demo;setMode('naive');}catch(error){demo.dispose();setNotice(String(error));}finally{setDemoBusy(false);}
 }
 async function compare(){setDemoBusy(true);setSelected(undefined);try{setNotice(await runComparison(connection,mode==='naive'?naive.current:undefined,clock,reverse));}catch(error){setNotice(String(error));}finally{setDemoBusy(false);}}
 async function save(format:'PNG'|'SVG'){try{await exportBoard(elements,format,boardName+(historyData?` at ${historyPosition}`:''));setNotice(`${format} exported.`);}catch(error){setNotice(String(error));}}
 async function showInvite(){
  setInviting(true);try{
   const response=await fetch(`/api/v1/boards/${boardId}/invites`,{method:'POST',headers:{Authorization:`Bearer ${connection.token}`}});
   if(!response.ok)throw Error('Could not create an invite. Check your connection and link expiry.');
   const data=await response.json(),url=new URL(location.href);url.search=`?board=${boardId}`;url.hash=`token=${encodeURIComponent(data.editToken)}`;
   setInvite({url:url.href,qr:await QRCode.toDataURL(url.href,{width:320,margin:4,errorCorrectionLevel:'M'})});
  }catch(error){setNotice(String(error));}finally{setInviting(false);}
 }
 const operation=historyData?.operations[historyPosition-1];
 return <main>
  <header><div className="brand"><span className="mark">w</span><strong>weave</strong><span className="divider"/><span>{boardName}</span></div>
   <div className="connection"><div className="avatars" aria-label="People on this board"><span className="avatar" style={{background:connection.identity.color}} title={connection.identity.name+' (you)'}>You</span>{[...connection.peers.values()].slice(0,5).map(peer=><span className="avatar" key={peer.sessionId} style={{background:peer.color}} title={peer.name}>{peer.name.slice(0,2)}</span>)}</div><span className="peer-count">{connection.peers.size+1} here</span>
    <button className="invite" disabled={inviting} onClick={showInvite}>{inviting?'Creating invite…':'Invite to board'}</button>
    <span className="local" data-state={connection.status} role="status"><i/>{connection.status}{connection.pending.size>0?` · ${connection.pending.size} queued`:''}</span></div></header>
  <nav className="board-actions" aria-label="Board controls"><label className="name-field">Your name <input aria-label="Your name" maxLength={40} defaultValue={connection.identity.name} onBlur={e=>connection.setName(e.target.value)} onKeyDown={e=>{if(e.key==='Enter')e.currentTarget.blur();}}/></label><div className="actions"><button aria-pressed={!!historyData||!!historyLoading} disabled={mode==='naive'||demoBusy} onClick={()=>historyData||historyLoading?goLive():void showHistory()}>History</button><button onClick={()=>save('PNG')}>Export PNG</button><button onClick={()=>save('SVG')}>Export SVG</button><button aria-pressed={mode==='naive'} disabled={demoBusy} onClick={toggleMode}>Naive demo</button></div></nav>
  <section className="comparison-strip" aria-label="Concurrent edit comparison"><strong>{mode==='naive'?'Naive · whole-board replacement':'CRDT · per-field merge'}</strong><span>{mode==='naive'?'Temporary demo canvas. Its edits can overwrite each other.':'Try two edits from the same starting state.'}</span><label>Arrival order <select aria-label="Comparison arrival order" value={reverse?'color-first':'move-first'} onChange={e=>setReverse(e.target.value==='color-first')}><option value="move-first">Move → color</option><option value="color-first">Color → move</option></select></label><button disabled={readonly||connection.status!=='Synced'||!!naive.current?.pending} onClick={compare}>Run concurrent edits</button>{mode==='naive'&&<button onClick={toggleMode}>Return to CRDT</button>}</section>
  <section className={'workspace'+(readonly?' viewing-history':'')}>
   {(connection.error||naive.current?.error)&&<div className="sync-error" role="alert">{connection.error||naive.current?.error}</div>}
   <aside className="toolbar" aria-label="Drawing tools">{tools.map(([id,icon,label])=><button key={id} disabled={readonly} title={label} aria-label={label} aria-pressed={tool===id} className={tool===id?'active':''} onClick={()=>{setTool(id);setSelected(undefined);}}><span>{icon}</span><small>{label}</small></button>)}<div className="rule"/><button title="Undo last creation (Ctrl+Z)" aria-label="Undo last creation" disabled={readonly||!elements.length} onClick={undo}><span>↶</span><small>Undo</small></button></aside>
   {!readonly&&<div className="styles"><strong>{selection?'Shape style':'Drawing style'}</strong><label>Color <input aria-label="Color" type="color" value={color} onChange={e=>{setColor(e.target.value);if(selected)update(selected,'color',e.target.value);}}/></label><div className="swatches">{['#425eeb','#222a35','#e06c48','#32a080','#a169c7'].map(c=><button aria-label={'Use '+c} aria-pressed={color===c} key={c} style={{background:c}} onClick={()=>{setColor(c);if(selected)update(selected,'color',c);}}/>)}</div><label>Stroke <select aria-label="Stroke width" value={stroke} onChange={e=>{setStroke(+e.target.value);if(selected)update(selected,'strokeWidth',+e.target.value);}}><option value={1}>Thin</option><option value={2}>Regular</option><option value={4}>Bold</option><option value={8}>Heavy</option></select></label>{(tool==='TEXT'||selection?.type==='TEXT')&&<label className="text-label">Text<input aria-label="Text content" value={selection?.type==='TEXT'?String(selection.fields.text?.value??''):text} onChange={e=>{setText(e.target.value);if(selection?.type==='TEXT'){update(selection.id,'text',e.target.value);const ctx=canvas.current!.getContext('2d')!;ctx.font='24px system-ui';update(selection.id,'width',Math.max(20,ctx.measureText(e.target.value).width));}}}/></label>}{selection&&<button className="delete" onClick={()=>remove(selection.id)}>Delete shape</button>}</div>}
   <canvas ref={canvas} aria-label="Whiteboard drawing canvas. Choose a tool then drag to draw. Select a shape to move or resize it." style={{cursor:readonly?'default':tool==='SELECT'?'default':'crosshair'}} onPointerLeave={()=>connection.presence(-1000,-1000,'',true)} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={()=>{gesture.current=undefined;setDraft(undefined);}}/>
   {!elements.length&&!draft&&<div className="empty"><div>{historyData?'Before the first mark.':'Every idea starts somewhere.'}</div><p>{historyData?'Move the slider forward to replay this board.':'Pick a tool and make your first mark.'}</p></div>}
   <div className="hint">{historyData?'Viewing saved history · Live edits continue in the background':tool==='SELECT'?'Drag to move · Corner handle to resize · Delete to remove':tool==='TEXT'?'Enter text in the style panel, then click the board':'Click and drag to draw'}</div>
  </section>
  {(historyData||historyLoading)&&<section className="history-bar" aria-label="Board history"><div><strong>{historyLoading||'History'}</strong><span>{historyData?`Operation ${historyPosition.toLocaleString()} of ${historyData.operations.length.toLocaleString()}`:'Fetching saved operations…'}</span><span>{operation?new Date(operation.operation.hlc.physicalTime).toLocaleString():'Board start'}</span></div>{historyData&&<input type="range" aria-label="History position" min={0} max={historyData.operations.length} value={historyPosition} onChange={e=>setHistoryPosition(+e.target.value)}/>}<button onClick={goLive}>Back to live{historyData&&connection.sequence>historyData.operations.length?` · ${connection.sequence-historyData.operations.length} new`:''}</button></section>}
  {notice&&<div className="notice" role="status">{notice}<button aria-label="Dismiss notice" onClick={()=>setNotice('')}>×</button></div>}
  {invite&&<div className="invite-backdrop" onClick={()=>setInvite(undefined)}><section className="invite-dialog" role="dialog" aria-modal="true" aria-label="Invite to this board" onClick={e=>e.stopPropagation()}><button className="invite-close" autoFocus aria-label="Close invite" onClick={()=>setInvite(undefined)}>×</button><h2>Make something together.</h2><p>Scan to open this board. Anyone with this link can edit.</p><img src={invite.qr} alt="QR code for a fresh board edit invite" width={320} height={320}/>{['localhost','127.0.0.1','::1'].includes(location.hostname)&&<p className="invite-local">This is a local preview. A phone can join after the board is deployed to a public address.</p>}<button onClick={async()=>{try{await navigator.clipboard.writeText(invite.url);setNotice('Fresh invite link copied.');}catch{setNotice('Select and copy the invite link below.');}}}>Copy invite link</button><input aria-label="Board invite URL" readOnly value={invite.url} onFocus={e=>e.target.select()}/></section></div>}
  <footer><span>{mode==='naive'?'Naive demo · temporary edits':historyData?'History · read only':'Shared whiteboard'}</span><span>{elements.length} shapes <b>·</b> {connection.sequence} saved operations <b>·</b> {mode==='naive'?(naive.current?.pending?'Sending demo snapshot…':'Demo uses whole snapshots'):connection.pending.size?'Editing locally, awaiting sync':'Changes saved to this board'}</span></footer>
 </main>;
}
const root=createRoot(document.getElementById('root')!);
root.render(<div className="startup" role="status">Opening your board…</div>);
openBoard(clock).then(result=>{board=result.board;boardId=board.boardId;connection=result.connection;boardName=result.name;root.render(<App/>);}).catch(error=>root.render(<div className="startup" role="alert"><h1>Could not open this board</h1><p>{String(error.message??error)}</p><button onClick={()=>location.reload()}>Try again</button></div>));
