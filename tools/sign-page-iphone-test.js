// Signs a sample quote on the signing page in an iPhone (Safari/WebKit) browser, with the service mocked.
const { webkit, devices } = require('playwright');
(async () => {
  const b = await webkit.launch();
  const ctx = await b.newContext({ ...devices['iPhone 13'] });
  const page = await ctx.newPage();
  const payload = {
    business:{name:"Zimdars Electric LLC",phone:"(315) 555-0100",email:"jeff@example.com",address:"6221 State Route 26, Rome, NY 13440",license:"",insurer:""},
    customer:{name:"Pat Sample",phone:"(315) 555-0199",address:"12 Main St, Ilion, NY"},
    quote:{number:"Q-0007",date:Date.now(),validUntil:Date.now()+30*864e5,title:"Add 2 kitchen GFCI circuits",site:"12 Main St, Ilion, NY",jobType:"INSTALL",scheduled:"Nov 5 – Nov 6, 2026",notes:"Run two new 20A circuits from the panel to the kitchen counter.\nInstall 2 GFCI receptacles."},
    lines:[{name:"12/2 NM-B cable w/ground",qty:80,unit:"ft",price:0.62,total:49.6},{name:"20A GFCI receptacle",qty:2,unit:"ea",price:26.4,total:52.8},{name:"20A 1-pole breaker",qty:2,unit:"ea",price:12.6,total:25.2}],
    labor:{hours:5,rate:95,amount:475},
    totals:{materials:127.6,labor:475,subtotal:602.6,taxRate:8.75,tax:0,total:602.6},
    terms:{payment:"Payment due when the job is complete.",warranty:"1 year on workmanship."},
    notices:["Any payment made before the work is finished will be deposited in an escrow account as required by NY Lien Law §71-a(4)."]
  };
  let signed = null;
  await page.route('https://dmaslbfwpsfnnnyushbc.supabase.co/**', async r => {
    if (r.request().method()==='GET') return r.fulfill({json:{status:'sent',quote_number:'Q-0007',payload,agree_text:'I have read this quote and agree to the work, price and terms above. I agree to sign electronically, and my electronic signature is as binding as signing on paper.'}});
    signed = JSON.parse(r.request().postData());
    return r.fulfill({json:{ok:true,signed_at:new Date().toISOString()}});
  });
  page.on('pageerror', e => console.log('PAGEERROR', e.message));
  await page.goto('file://' + require('path').resolve(__dirname, '../docs/sign/index.html') + '?t=abcdefghijklmnopqrstuvwx');
  await page.waitForSelector('canvas#pad');
  await page.screenshot({path:require('path').join(__dirname,'out','ios_1.png')});
  await page.click('.btn'); console.log('err1:', await page.textContent('.err'));
  await page.fill('#nm','Pat Sample'); await page.check('#ag');
  const box = await page.locator('#pad').boundingBox();
  await page.mouse.move(box.x+30, box.y+120); await page.mouse.down();
  for (let i=0;i<30;i++) await page.mouse.move(box.x+30+i*8, box.y+120 - Math.sin(i/3)*40);
  await page.mouse.up();
  await page.locator('.signbox').screenshot({path:require('path').join(__dirname,'out','ios_2.png')});
  await page.click('.btn');
  await page.waitForSelector('.signed');
  console.log('sent name:', signed.name, 'sig bytes:', signed.signature.length, 'agree:', signed.agree);
  await page.locator('.signed').screenshot({path:require('path').join(__dirname,'out','ios_3.png')});
  await page.screenshot({path:require('path').join(__dirname,'out','ios_4.png'), fullPage:true});
  await b.close();
})();
