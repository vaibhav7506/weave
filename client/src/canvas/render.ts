import type {Element,Field} from '../crdt/core';
export type Box={x:number;y:number;width:number;height:number};
export const number=(e:Element,f:Field, fallback=0)=>Number(e.fields[f]?.value??fallback);
export const bounds=(e:Element):Box=>({x:number(e,'x'),y:number(e,'y'),width:number(e,'width',100),height:number(e,'height',60)});
export function paint(ctx:CanvasRenderingContext2D,e:Element) {
 const b=bounds(e), color=String(e.fields.color?.value??'#425eeb');
 ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=number(e,'strokeWidth',2);ctx.lineCap='round';ctx.lineJoin='round';
 if(e.type==='TEXT') {ctx.font='24px system-ui';ctx.textBaseline='top';String(e.fields.text?.value??'Text').split('\n').forEach((line,i)=>ctx.fillText(line,b.x,b.y+i*30));return;}
 ctx.beginPath();
 if(e.type==='RECTANGLE')ctx.rect(b.x,b.y,b.width,b.height);
 if(e.type==='ELLIPSE')ctx.ellipse(b.x+b.width/2,b.y+b.height/2,Math.max(.5,b.width/2),Math.max(.5,b.height/2),0,0,Math.PI*2);
 if(e.type==='FREEFORM_STROKE') {
  const pts=e.fields.points?.value as readonly (readonly [number,number])[]??[];
  pts.forEach(([x,y],i)=>{const px=b.x+x*b.width,py=b.y+y*b.height;i?ctx.lineTo(px,py):ctx.moveTo(px,py);});
 } else {ctx.globalAlpha=.07;ctx.fill();ctx.globalAlpha=1;}
 ctx.stroke();
}
