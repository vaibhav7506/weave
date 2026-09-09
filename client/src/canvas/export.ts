import type {Element} from '../crdt/core';
import {paint,bounds,number} from './render';

const escape=(value:unknown)=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]!));
export function exportBounds(elements:Element[]) {
  let left=0,top=0,right=640,bottom=480;
  if(elements.length){left=Infinity;top=Infinity;right=-Infinity;bottom=-Infinity;}
  for(const e of elements){const b=bounds(e),pad=number(e,'strokeWidth',2)/2+24;
    // Measure text independently of its selection box, which may have been resized.
    let width=b.width,height=b.height;
    if(e.type==='TEXT'){
      const lines=String(e.fields.text?.value??'Text').split('\n');height=Math.max(height,lines.length*30);
      if(typeof document!=='undefined'){const ctx=document.createElement('canvas').getContext('2d')!;ctx.font='24px system-ui';width=Math.max(width,...lines.map(line=>ctx.measureText(line).width));}
    }
    left=Math.min(left,b.x-pad);top=Math.min(top,b.y-pad);right=Math.max(right,b.x+width+pad);bottom=Math.max(bottom,b.y+height+pad);
  }
  return {x:Math.floor(left),y:Math.floor(top),width:Math.ceil(right-left),height:Math.ceil(bottom-top)};
}
export function boardSvg(elements:Element[]) {
  const b=exportBounds(elements);
  const shapes=elements.map(e=>{
    const r=bounds(e),color=escape(e.fields.color?.value??'#425eeb');
    const style=`stroke="${color}" stroke-width="${number(e,'strokeWidth',2)}" stroke-linecap="round" stroke-linejoin="round" fill="${color}" fill-opacity="0.07"`;
    if(e.type==='RECTANGLE')return `<rect x="${r.x}" y="${r.y}" width="${r.width}" height="${r.height}" ${style}/>`;
    if(e.type==='ELLIPSE')return `<ellipse cx="${r.x+r.width/2}" cy="${r.y+r.height/2}" rx="${Math.max(.5,r.width/2)}" ry="${Math.max(.5,r.height/2)}" ${style}/>`;
    if(e.type==='FREEFORM_STROKE')return `<polyline points="${(e.fields.points?.value as [number,number][]??[]).map(([x,y])=>`${r.x+x*r.width},${r.y+y*r.height}`).join(' ')}" stroke="${color}" stroke-width="${number(e,'strokeWidth',2)}" stroke-linecap="round" stroke-linejoin="round" fill="none"/>`;
    if(e.type==='TEXT')return `<text fill="${color}" font-family="system-ui,sans-serif" font-size="24" dominant-baseline="text-before-edge">${String(e.fields.text?.value??'Text').split('\n').map((line,i)=>`<tspan x="${r.x}" y="${r.y+i*30}" xml:space="preserve">${escape(line)}</tspan>`).join('')}</text>`;
    return '';
  }).join('\n');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${b.width}" height="${b.height}" viewBox="${b.x} ${b.y} ${b.width} ${b.height}"><rect x="${b.x}" y="${b.y}" width="${b.width}" height="${b.height}" fill="white"/>${shapes}</svg>`;
}
export async function exportBoard(elements:Element[],format:'PNG'|'SVG',name:string) {
  let blob:Blob;
  if(format==='SVG')blob=new Blob([boardSvg(elements)],{type:'image/svg+xml'});
  else {
    const b=exportBounds(elements),scale=Math.min(2,8192/b.width,8192/b.height,Math.sqrt(16000000/(b.width*b.height)));
    const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.ceil(b.width*scale));canvas.height=Math.max(1,Math.ceil(b.height*scale));
    const ctx=canvas.getContext('2d')!;ctx.fillStyle='white';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.scale(scale,scale);ctx.translate(-b.x,-b.y);elements.forEach(e=>paint(ctx,e));
    blob=await new Promise<Blob>((resolve,reject)=>canvas.toBlob(value=>value?resolve(value):reject(Error('PNG export failed')),'image/png'));
  }
  const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=(name.replace(/[^a-z0-9 _-]/gi,'').trim()||'Weave')+'.'+format.toLowerCase();link.click();setTimeout(()=>URL.revokeObjectURL(url),10000);
}
