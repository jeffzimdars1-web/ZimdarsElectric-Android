// Opens a sample appointment confirmation in an iPhone (Safari/WebKit) browser with the service mocked:
// checks the page draws, Confirm works, and Add to calendar asks the server for the calendar file.
const { webkit, devices } = require('playwright');
const path = require('path');
const out = f => path.join(__dirname, 'out', f);
(async () => {
  const b = await webkit.launch();
  const ctx = await b.newContext({ ...devices['iPhone 13'], timezoneId: 'America/Los_Angeles' });
  const page = await ctx.newPage();
  const start = Date.parse('2026-10-31T14:00:00Z'); // 10:00 AM New York
  const payload = {
    business: { name: "Zimdars Electric LLC", owner: "Jeff", phone: "(315) 555-0100", email: "jeff@example.com", address: "6221 State Route 26, Rome, NY 13440" },
    customer: { name: "Pat Sample", phone: "(315) 555-0199" },
    appt: { number: "Q-0009", start, hasTime: true, windowMin: 15, site: "12 Main St, Utica, NY", reason: "Bedroom light switch not working" },
    terms: { rates: "Service call $125 covers the trip and the first 30 minutes on site." },
  };
  let confirmed = null, icsUrl = null;
  await page.route('https://dmaslbfwpsfnnnyushbc.supabase.co/**', async r => {
    const u = r.request().url();
    if (u.includes('ics=1')) { icsUrl = u; return r.fulfill({ status: 200, contentType: 'text/calendar', body: 'BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n' }); }
    if (r.request().method() === 'GET') return r.fulfill({ json: { kind: 'appt', status: 'sent', quote_number: 'Q-0009', payload } });
    confirmed = JSON.parse(r.request().postData());
    return r.fulfill({ json: { ok: true, confirmed_at: new Date().toISOString() } });
  });
  page.on('pageerror', e => console.log('PAGEERROR', e.message));
  await page.goto('file://' + path.resolve(__dirname, '../docs/appt/index.html') + '?t=abcdefghijklmnopqrstuvwx');
  await page.waitForSelector('.btn-main');
  console.log('day:', await page.textContent('.when .day'));
  console.log('time:', await page.textContent('.when .time'));
  console.log('window:', await page.textContent('.when .window'));
  await page.screenshot({ path: out('appt_1.png'), fullPage: true });
  await page.click('.btn-main');
  await page.waitForSelector('.confirmed');
  console.log('confirm sent:', confirmed && confirmed.action, confirmed && confirmed.t);
  await page.screenshot({ path: out('appt_2.png'), fullPage: true });
  console.log('call href:', await page.getAttribute('a[href^="tel:"]', 'href'), 'text href:', await page.getAttribute('a[href^="sms:"]', 'href'));
  await Promise.all([page.waitForRequest(r => r.url().includes('ics=1'), { timeout: 8000 }).catch(() => null),
    page.click('button.btn-line')]);
  await page.waitForTimeout(800);
  console.log('ics requested:', icsUrl ? 'yes' : 'no');
  await b.close();
})();
