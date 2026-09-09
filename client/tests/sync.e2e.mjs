import {chromium} from 'playwright';

import assert from 'node:assert/strict';

import fs from 'node:fs/promises';

async function snapshotFor(invite){const parsed=new URL(invite),id=parsed.searchParams.get('board'),token=new URLSearchParams(parsed.hash.slice(1)).get('token');const response=await fetch(base+'/api/v1/boards/'+id+'/snapshot',{headers:{Authorization:'Bearer '+token}});assert.equal(response.status,200);return response.json();}

const base=process.env.WEAVE_PREVIEW_URL??'http://127.0.0.1:5187';

const browser=await chromium.launch({headless:true});

const errors=[];

const contexts=[];

async function page(){const context=await browser.newContext({viewport:{width:1280,height:900},deviceScaleFactor:1});contexts.push(context);const page=await context.newPage();page.on('pageerror',e=>errors.push(String(e)));return page;}

async function synced(page){await page.getByRole('status').filter({hasText:/^Synced/}).waitFor({timeout:20000});}

async function drag(page,tool,x,y,endX,endY){await page.getByRole('button',{name:tool,exact:true}).click();const box=await page.locator('canvas').boundingBox();await page.mouse.move(box.x+x,box.y+y);await page.mouse.down();await page.mouse.move(box.x+endX,box.y+endY,{steps:8});await page.mouse.up();}

async function settle(page){await page.keyboard.press('Escape');await page.mouse.move(2,2);await synced(page);}

async function samePixels(a,b,label){

  await settle(a);await settle(b);

  const deadline=Date.now()+10000;let left,right;

  do{left=Buffer.from((await a.locator('canvas').evaluate(canvas=>canvas.toDataURL())).split(',')[1],'base64');right=Buffer.from((await b.locator('canvas').evaluate(canvas=>canvas.toDataURL())).split(',')[1],'base64');if(left.equals(right))break;}while(Date.now()<deadline);

  await fs.mkdir('test-results',{recursive:true});

  if(!left.equals(right)){await fs.writeFile('test-results/failure-a.png',left);await fs.writeFile('test-results/failure-b.png',right);}

  assert.ok(left.equals(right),label+' canvases must be pixel-identical');

  await fs.mkdir('test-results',{recursive:true});await fs.writeFile('test-results/'+label+'.png',left);

  console.log('PASS:',label,'— pixel-identical canvas output');

}

try {

 const a=await page(),b=await page();

 await a.goto(base);await synced(a);const invite=a.url();

 await b.goto(invite);await synced(b);

 await a.getByText('2 here',{exact:true}).waitFor();

 await drag(a,'Rectangle',250,230,400,330);await synced(a);

 await samePixels(a,b,'initial-broadcast');

 const originalId=(await snapshotFor(invite)).elements[0].id;

 // Same rectangle, independent fields, concurrent UI activity.

 const ab=await a.locator('canvas').boundingBox(),bb=await b.locator('canvas').boundingBox();

 await b.mouse.click(bb.x+300,bb.y+280);

 await Promise.all([

  (async()=>{await a.mouse.move(ab.x+300,ab.y+280);await a.mouse.down();await a.mouse.move(ab.x+470,ab.y+350,{steps:10});await a.mouse.up();})(),

  b.getByRole('button',{name:'Use #e06c48',exact:true}).click()

 ]);

 await samePixels(a,b,'concurrent-move-and-color');

 // Partition A, continue drawing in BOTH clients, then reconnect.

 await a.context().setOffline(true);

 await a.getByRole('status').filter({hasText:/Offline/}).waitFor();

 await drag(a,'Ellipse',250,420,390,520);

 await a.getByRole('status').filter({hasText:/queued/}).waitFor();

 await drag(b,'Rectangle',570,410,730,530);await synced(b);

 await a.context().setOffline(false);

 await samePixels(a,b,'offline-reconnect');

 // Persisted snapshot and retained HLCs must merge after a fresh page load.

 await b.reload();await synced(b);

 await samePixels(a,b,'snapshot-reload');

 await drag(b,'Ellipse',650,200,760,280);await samePixels(a,b,'edit-after-snapshot');

 const parsed=new URL(invite),id=parsed.searchParams.get('board'),token=new URLSearchParams(parsed.hash.slice(1)).get('token');

 const response=await fetch(base+'/api/v1/boards/'+id+'/snapshot',{headers:{Authorization:'Bearer '+token}});assert.equal(response.status,200);const snapshot=await response.json();

 const live=snapshot.elements.filter(e=>e.createdHlc&&!e.removedHlc);assert.equal(live.length,4);

 const moved=live.find(e=>e.id===originalId);assert.ok(moved);assert.equal(moved.fields.color.value,'#e06c48');assert.equal(moved.fields.x.value,420);assert.equal(moved.fields.y.value,300);

 assert.deepEqual(errors,[],'No browser runtime errors');

 await a.screenshot({path:'test-results/phase2-app.png'});

 await fs.writeFile('test-results/phase2-result.json',JSON.stringify({passed:true,scenarios:['initial broadcast','concurrent move and color','offline edits on both sides','reconnect','snapshot reload','edit after snapshot'],liveShapes:live.length,savedOperations:snapshot.sequenceNumber,browserErrors:errors},null,2));

 console.log('PASS: persistent snapshot has four shapes and preserves concurrent position + color; no runtime errors');

} finally {for(const context of contexts)await context.close();await browser.close();}

