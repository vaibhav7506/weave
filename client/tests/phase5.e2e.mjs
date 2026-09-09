import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const base=process.env.WEAVE_PREVIEW_URL??'http://127.0.0.1:5187';
const browser=await chromium.launch({headless:true}),errors=[];
const a=await browser.newPage({viewport:{width:1280,height:900}}),b=await browser.newPage({viewport:{width:1280,height:900}});
for(const page of [a,b])page.on('pageerror',error=>errors.push(String(error)));
const synced=page=>page.getByRole('status').filter({hasText:/^Synced/}).waitFor({timeout:30000});
const notice=(page,text)=>page.getByRole('status').filter({hasText:text}).waitFor();
async function at(page,value){await page.getByRole('slider',{name:'History position'}).fill(String(value));}
try{
  await a.goto(base);await synced(a);await b.goto(a.url());await synced(b);
  await a.getByLabel('Your name',{exact:true}).fill('Ada');await a.getByLabel('Your name',{exact:true}).press('Enter');await b.getByTitle('Ada',{exact:true}).waitFor();
  await a.getByRole('button',{name:'Run concurrent edits',exact:true}).click();await synced(a);await notice(a,'CRDT: the move and color change both survived.');
  await a.getByRole('button',{name:'History',exact:true}).click();await a.getByRole('slider').waitFor();await at(a,1);
  assert.equal(await a.getByRole('button',{name:'Rectangle',exact:true}).isDisabled(),true);
  const historyPixels=await a.locator('canvas').evaluate(c=>c.toDataURL());
  await b.getByRole('button',{name:'Run concurrent edits',exact:true}).click();await synced(b);await a.getByRole('button',{name:/Back to live · 3 new/}).waitFor();
  assert.equal(await a.locator('canvas').evaluate(c=>c.toDataURL()),historyPixels,'Live edits must not change the viewed history');
  await at(a,0);assert.ok(await a.getByText('Before the first mark.').isVisible());await a.getByRole('button',{name:/Back to live/}).click();
  for(const format of ['PNG','SVG']){
    const [download]=await Promise.all([a.waitForEvent('download'),a.getByRole('button',{name:'Export '+format,exact:true}).click()]);
    await fs.mkdir('test-results',{recursive:true});const path='test-results/export.'+format.toLowerCase();await download.saveAs(path);const data=await fs.readFile(path);assert.ok(data.length>100);if(format==='PNG')assert.equal(data.subarray(1,4).toString(),'PNG');else{assert.ok(data.toString().includes('<svg'));assert.ok(!data.toString().includes('Ada'));}
  }
  const parsed=new URL(a.url()),id=parsed.searchParams.get('board'),token=new URLSearchParams(parsed.hash.slice(1)).get('token'),headers={Authorization:'Bearer '+token};
  const before=await (await fetch(`${base}/api/v1/boards/${id}/snapshot`,{headers})).json();
  await a.getByRole('button',{name:'Naive demo',exact:true}).click();await a.getByText('Naive · whole-board replacement',{exact:true}).waitFor();
  await a.getByRole('button',{name:'Run concurrent edits',exact:true}).click();await notice(a,'Naive: the color survived; the move was lost.');
  await a.getByLabel('Comparison arrival order').selectOption('color-first');await a.getByRole('button',{name:'Run concurrent edits',exact:true}).click();await notice(a,'Naive: the move survived; the color change was lost.');
  await b.getByRole('button',{name:'Naive demo',exact:true}).click();await b.getByText('Naive · whole-board replacement',{exact:true}).waitFor();
  await a.keyboard.press('Escape');await b.keyboard.press('Escape');await a.mouse.move(1,1);await b.mouse.move(1,1);
  assert.equal(await a.locator('canvas').evaluate(c=>c.toDataURL()),await b.locator('canvas').evaluate(c=>c.toDataURL()),'Both browsers see the same naive snapshots');
  const after=await (await fetch(`${base}/api/v1/boards/${id}/snapshot`,{headers})).json();assert.deepEqual(after,before,'Naive demo must not edit real board');
  await a.getByRole('button',{name:'Return to CRDT',exact:true}).click();await a.getByText('CRDT · per-field merge',{exact:true}).waitFor();
  await a.screenshot({path:'test-results/phase5-app.png'});
  await a.setViewportSize({width:390,height:844});assert.ok(await a.getByRole('button',{name:'History',exact:true}).isVisible());assert.equal(await a.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'No mobile horizontal overflow');
  await a.screenshot({path:'test-results/phase5-mobile.png',fullPage:true});assert.deepEqual(errors,[]);
  console.log('PASS: names, history isolation and live return, PNG/SVG downloads, real naive transport in both orders, two-browser demo, durable-board isolation, mobile layout');
}finally{await browser.close();}
