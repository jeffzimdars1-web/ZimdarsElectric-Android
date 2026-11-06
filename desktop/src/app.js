// Zimdars Electric for PC. Reads the copy your phone sends; jobs made here go back to the phone through the inbox.
(function () {
  const API = "https://dmaslbfwpsfnnnyushbc.supabase.co/functions/v1/pc";
  const Z = window.ZL;
  const $root = document.getElementById("root");
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const money = (v) => (Number(v) < 0 ? "-$" : "$") + Math.abs(Number(v) || 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const day = (ms) => ms ? new Date(ms).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" }) : "";
  const when = (ms, hasTime) => ms ? new Date(ms).toLocaleString("en-US", hasTime ? { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" } : { weekday: "short", month: "short", day: "numeric" }) : "";
  const qty = (v) => (Math.round(Number(v) * 100) / 100).toString();
  const store = { get: (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } }, set: (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) {} } };

  // Works as the Windows program or as a web page in any browser.
  const native = !!window.zapi;
  function openExternal(url) { if (!/^https:\/\//.test(url)) return; native ? window.zapi.openExternal(url) : window.open(url, "_blank", "noopener"); }
  function savePdf(html, name) {
    if (native) return window.zapi.savePdf(html, name);
    // Browser: print the customer copy; choose "Save as PDF" (or "Microsoft Print to PDF") as the printer.
    const f = document.createElement("iframe");
    f.style.cssText = "position:fixed;width:0;height:0;border:0;right:0;bottom:0";
    f.setAttribute("sandbox", "allow-modals allow-same-origin");
    document.body.appendChild(f);
    f.contentDocument.open(); f.contentDocument.write(html.replace("<title>", "<title>").replace("<head>", "<head><title>" + esc(name) + "</title>")); f.contentDocument.close();
    setTimeout(() => { try { f.contentWindow.focus(); f.contentWindow.print(); } finally { setTimeout(() => f.remove(), 60_000); } }, 300);
  }

  let key = store.get("key");
  let D = null, updatedAt = null, loadErr = null;
  let view = { page: "home" };
  const ui = { quotesTab: 0, search: "", custSearch: "", month: null, year: new Date().getFullYear(), stockSearch: "" };
  const photoUrls = {}; let photoUrlsAt = 0;

  async function call(body, withKey = true) {
    const r = await fetch(API, { method: "POST", headers: Object.assign({ "Content-Type": "application/json" }, withKey ? { "x-app-key": key } : {}), body: JSON.stringify(body) });
    const j = await r.json().catch(() => ({}));
    if (r.status === 401) { const e = new Error("unlinked"); e.unlinked = true; throw e; }
    if (!r.ok) throw new Error(j.error || ("Error " + r.status));
    return j;
  }

  function shape(raw) {
    const t = (k) => (raw && raw[k]) || [];
    const quotes = t("Quote"), customers = t("Customer");
    return { quotes, lines: t("QuoteLine"), customers, materials: t("Material"), expenses: t("Expense"), trips: t("Trip"), photos: t("JobPhoto"),
      settings: t("Settings")[0] || {}, made: raw && raw.made,
      cust: (id) => customers.find((c) => c.id === id), quote: (id) => quotes.find((q) => q.id === id) };
  }

  async function load(quiet) {
    try {
      const r = await call({ action: "get_snapshot" });
      loadErr = null;
      if (r.data) { D = shape(r.data); updatedAt = r.updated_at; }
    } catch (e) {
      if (e.unlinked) { key = null; store.set("key", null); }
      loadErr = e.unlinked ? null : "Can't reach the cloud right now. Check your internet.";
    }
    if (!quiet) return render();
    // Background refresh: don't disturb typing, a half-filled form, or where you've scrolled to.
    if (view.page === "new" || document.querySelector("input:focus, textarea:focus, select:focus, .viewer")) return;
    const m = document.querySelector(".main"), y = m ? m.scrollTop : 0;
    render();
    const m2 = document.querySelector(".main"); if (m2) m2.scrollTop = y;
  }

  async function photoSrcs(names) {
    if (Date.now() - photoUrlsAt > 45 * 60_000) { Object.keys(photoUrls).forEach((k) => delete photoUrls[k]); photoUrlsAt = Date.now(); }
    const need = names.filter((n) => !(n in photoUrls));
    if (need.length) {
      try { const r = await call({ action: "photo_urls", names: need }); need.forEach((n) => photoUrls[n] = (r.urls || {})[n] || ""); } catch (e) {}
    }
  }
  function photosHtml(list) {
    if (!list.length) return `<div class="muted">No photos yet.</div>`;
    const names = list.map((p) => p.id + ".jpg");
    photoSrcs(names).then(() => document.querySelectorAll("img[data-photo]").forEach((img) => {
      const u = photoUrls[img.dataset.photo]; if (u && img.src !== u) img.src = u; else if (!u) img.alt = "Not sent from the phone yet";
    }));
    return `<div class="photos">${list.map((p) => `<img data-photo="${p.id}.jpg" data-cap="${esc(p.caption || "")}" alt="" title="${esc(p.caption || "")}">`).join("")}</div>`;
  }

  // ---------- Pages ----------
  function pairPage() {
    return `<div class="center">
      <div class="brand" style="font-size:30px">ZIMDARS ELECTRIC<small>FOR PC</small></div>
      <p>On your phone: <b>Settings → 19 · Zimdars Electric for PC → Link a PC</b>. Type the 8-letter code it shows.</p>
      <input id="code" class="code search" style="width:100%" maxlength="9" placeholder="ABCD EFGH" autofocus>
      <button class="btn" id="pair" style="width:100%">Link this PC</button>
      <p id="pairErr" class="warn"></p></div>`;
  }

  const sortDate = (q) => q.startAt || q.completedAt || q.createdAt || 0;
  const visibleQuotes = () => D.quotes.filter((q) => !q.optionOf);

  function homePage() {
    const now = Date.now(), s = D.settings;
    const y = new Date().getFullYear(), m = new Date().getMonth();
    const month = Z.money(y, D)[m];
    const unsigned = visibleQuotes().filter((q) => q.kind !== "APPT" && q.status === "SENT" && !q.signedAt && !q.canceledAt);
    const unpaid = visibleQuotes().filter((q) => q.status === "DONE" && !q.paidAt && !q.parentId);
    const owed = unpaid.reduce((a, q) => a + Z.jobTotal(q, D.quotes, D.lines, s).total - Z.n(q.paidAmount) - Z.n(q.depositPaid), 0);
    const upcoming = visibleQuotes().filter((q) => q.startAt && q.startAt >= new Date().setHours(0, 0, 0, 0) && !q.canceledAt && q.status !== "DONE" && q.status !== "DECLINED")
      .sort((a, b) => a.startAt - b.startAt).slice(0, 10);
    const requests = visibleQuotes().filter((q) => q.kind === "APPT" && q.status === "DRAFT" && !q.canceledAt && String(q.privateNote || "").startsWith("Requested online"));
    return `
      ${requests.length ? `<div class="note warn"><b>${requests.length} new booking request${requests.length > 1 ? "s" : ""}</b> · ${requests.map((q) => `<a data-q="${q.id}">${esc(q.customerName || "Someone")} — ${esc(q.title)}</a>`).join(" · ")}</div>` : ""}
      <div class="kpis">
        <div class="kpi n"><div class="label">Made this month</div><div class="v">${money(month.income)}</div><div class="muted">${month.jobs} paid job${month.jobs === 1 ? "" : "s"}</div></div>
        <div class="kpi o"><div class="label">Profit this month</div><div class="v">${money(month.profit)}</div><div class="muted">after expenses + mileage</div></div>
        <div class="kpi i"><div class="label">Waiting on signature</div><div class="v">${unsigned.length}</div><div class="muted">${money(unsigned.reduce((a, q) => a + Z.totals(q, D.lines.filter((l) => l.quoteId === q.id), s).total, 0))}</div></div>
        <div class="kpi w"><div class="label">Owed to you</div><div class="v">${money(owed)}</div><div class="muted">${unpaid.length} unpaid job${unpaid.length === 1 ? "" : "s"}</div></div>
      </div>
      <div class="grid2">
        <div class="card"><h2>Coming up</h2>${upcoming.length ? `<table>${upcoming.map((q) => `<tr class="click" data-q="${q.id}"><td style="width:170px">${esc(when(q.startAt, Z.b(q.hasTime)))}</td><td>${esc(q.customerName)}<div class="muted">${esc(q.title)}</div></td><td><span class="pill p-${esc(Z.statusOf(q)).replace(" ", ".")}">${esc(Z.statusOf(q))}</span></td></tr>`).join("")}</table>` : `<div class="muted">Nothing on the schedule.</div>`}</div>
        <div class="card"><h2>Needs you</h2>${[...unsigned.map((q) => [q, "Not signed yet · sent " + day(q.sentAt)]), ...unpaid.map((q) => [q, "Done " + day(q.completedAt) + " · not paid"])]
          .map(([q, why]) => `<div style="padding:8px 0;border-bottom:1px solid var(--line)"><a data-q="${q.id}">${esc(q.number)} · ${esc(q.customerName || q.title)}</a><div class="muted">${esc(why)}</div></div>`).join("") || `<div class="muted">All caught up.</div>`}</div>
      </div>`;
  }

  function quotesPage() {
    const tabs = ["Open", "Done", "All"];
    const term = ui.search.trim().toLowerCase();
    let list = visibleQuotes().filter((q) => ui.quotesTab === 2 || (ui.quotesTab === 0 ? Z.HOLDING.includes(q.status) && !q.canceledAt : q.status === "DONE"));
    if (term) list = list.filter((q) => [q.number, q.customerName, q.title, q.site, q.notes].join(" ").toLowerCase().includes(term));
    list.sort((a, b) => sortDate(b) - sortDate(a));
    return `<div class="toolbar"><div class="tabs">${tabs.map((t, i) => `<button data-tab="${i}" class="${i === ui.quotesTab ? "on" : ""}">${t}</button>`).join("")}</div>
      <input class="search" id="qsearch" placeholder="Search name, job, address, number…" value="${esc(ui.search)}"><span class="muted">${list.length} shown</span></div>
      <table><tr><th>Number</th><th>Customer</th><th>Job</th><th>Date</th><th>Status</th><th class="num">Total</th></tr>
      ${list.map((q) => `<tr class="click" data-q="${q.id}"><td class="num" style="text-align:left">${esc(q.number)}</td><td>${esc(q.customerName)}</td>
        <td>${q.kind === "APPT" ? `<span class="label">Appt</span> ` : ""}${esc(q.title)}<div class="muted">${esc(q.site)}</div></td><td>${esc(day(sortDate(q)))}</td>
        <td><span class="pill p-${esc(Z.statusOf(q)).replace(" ", ".")}">${esc(Z.statusOf(q))}</span></td>
        <td class="num">${q.kind === "APPT" ? "" : money(Z.totals(q, D.lines.filter((l) => l.quoteId === q.id), D.settings).total)}</td></tr>`).join("")}</table>`;
  }

  function quotePage(id) {
    const q = D.quote(id); if (!q) return `<div class="muted">That job isn't on your phone anymore.</div>`;
    const s = D.settings, ql = D.lines.filter((l) => l.quoteId === q.id), t = Z.totals(q, ql, s), c = D.cust(q.customerId);
    const kids = D.quotes.filter((x) => x.parentId === q.id), opts = D.quotes.filter((x) => x.optionOf === q.id);
    const photos = D.photos.filter((p) => p.quoteId === q.id);
    const typeName = { INSTALL: "New installation (no sales tax, ST-124)", REPAIR: "Repair (taxed)", BOTH: "Install + repair" }[q.jobType] || q.jobType;
    const link = (u, label) => u ? `<button class="btn ghost small" data-ext="${esc(u)}">${label}</button>` : "";
    const site = q.site || (c && c.address) || "";
    return `
      <div class="toolbar">
        <span class="pill p-${esc(Z.statusOf(q)).replace(" ", ".")}">${esc(Z.statusOf(q))}</span>
        ${q.kind !== "APPT" ? `<button class="btn small" data-pdf="${q.id}">${native ? "Save PDF" : "Print / Save PDF"}</button>` : ""}
        ${link(q.signUrl, q.kind === "APPT" ? "Customer's appointment page" : "Signing page")}${link(q.invoiceUrl, "Invoice page")}${link(q.st124Url, "ST-124 page")}
        ${site ? `<button class="btn ghost small" data-ext="https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(site)}">Map</button>` : ""}
        <button class="btn ghost small" data-edit="${q.id}">Add to this job</button>
      </div>
      <div class="card"><div class="facts">
        <div><div class="label">Customer</div><div class="v">${c ? `<a data-c="${c.id}">${esc(c.name)}</a>` : esc(q.customerName)}</div><div class="muted">${esc(c ? c.phone : "")}</div></div>
        <div><div class="label">Job site</div><div class="v">${esc(site)}</div>${c && site ? `<a data-house="${c.id}" data-addr="${esc(site)}">House history →</a>` : ""}</div>
        <div><div class="label">${q.kind === "APPT" ? "When" : "Type"}</div><div class="v">${esc(q.kind === "APPT" ? when(q.startAt, Z.b(q.hasTime)) : typeName)}</div></div>
        ${q.kind !== "APPT" ? `<div><div class="label">Scheduled</div><div class="v">${esc(q.startAt ? when(q.startAt, Z.b(q.hasTime)) : q.scheduled || "Not yet")}</div></div>` : ""}
        <div><div class="label">Created</div><div class="v">${esc(day(q.createdAt))}</div></div>
        ${q.signedAt ? `<div><div class="label">Signed</div><div class="v">${esc(day(q.signedAt))} · ${esc(q.signerName)}</div></div>` : ""}
        ${q.paidAt ? `<div><div class="label">Paid</div><div class="v ok">${money(q.paidAmount)} · ${esc(day(q.paidAt))}</div></div>` : ""}
        ${Z.n(q.depositPct) > 0 ? `<div><div class="label">Deposit</div><div class="v">${qty(q.depositPct)}% ${q.depositPaidAt ? `· <span class="ok">got ${money(q.depositPaid)}</span>` : "· not received"}</div></div>` : ""}
      </div></div>
      ${q.notes ? `<div class="card"><h2>${q.kind === "APPT" ? "Reason for visit" : "Work"}</h2><div style="white-space:pre-wrap">${esc(q.notes)}</div>${q.repairNotes ? `<h2 style="margin-top:14px">Repair work</h2><div style="white-space:pre-wrap">${esc(q.repairNotes)}</div>` : ""}</div>` : ""}
      ${q.privateNote ? `<div class="note"><span class="label">Private note</span><div style="white-space:pre-wrap">${esc(q.privateNote)}</div></div>` : ""}
      ${q.kind !== "APPT" ? `<div class="card"><h2>Materials</h2>${ql.length ? `<table><tr><th>Item</th><th class="num">Qty</th><th class="num">Your cost</th><th class="num">Customer price</th></tr>
        ${ql.map((l) => `<tr><td>${esc(l.name)}${Z.b(l.optional) ? ` <span class="pill" style="color:var(--info)">add-on${Z.n(l.addOnState) === 1 ? " · added" : Z.n(l.addOnState) === 2 ? " · passed" : ""}</span>` : ""}${q.jobType === "BOTH" && Z.b(l.repair) ? ` <span class="label">repair</span>` : ""}</td>
        <td class="num">${qty(l.qty)} ${esc(l.unit)}</td><td class="num">${money(Z.n(l.qty) * Z.n(l.unitCost))}</td><td class="num">${money(Z.linePrice(q, l))}</td></tr>`).join("")}</table>` : `<div class="muted">No materials.</div>`}</div>
      <div class="grid2"><div class="card"><h2>Customer pays</h2><table>
        <tr><td>Materials</td><td class="num">${money(t.materialsPrice)}</td></tr>
        <tr><td>Labor · ${qty(t.hours)} hr × ${money(q.laborRate)}</td><td class="num">${money(t.labor)}</td></tr>
        ${q.jobType === "BOTH" ? `<tr><td class="muted">Install part (no tax)</td><td class="num muted">${money(t.installPart)}</td></tr><tr><td class="muted">Repair part</td><td class="num muted">${money(t.repairPart)}</td></tr>` : ""}
        <tr><td>Sales tax ${qty(q.taxRatePct)}%${q.jobType === "INSTALL" ? " (capital improvement: none)" : ""}</td><td class="num">${money(t.salesTax)}</td></tr>
        <tr><td><b>Total</b></td><td class="num neon"><b>${money(t.total)}</b></td></tr></table></div>
      <div class="card"><h2>Your numbers (private)</h2><table>
        <tr><td>Materials cost</td><td class="num">${money(t.materialsCost)}</td></tr><tr><td>Fuel + supplies</td><td class="num">${money(t.fuel + t.supplies)}</td></tr>
        <tr><td>Profit</td><td class="num">${money(t.profit)}</td></tr><tr><td>Savings + tax set-aside</td><td class="num">${money(t.savings + t.taxSetAside)}</td></tr>
        <tr><td><b>Take-home</b></td><td class="num ok"><b>${money(t.takeHome)}</b></td></tr><tr><td class="muted">Per hour</td><td class="num muted">${money(t.perHour)}</td></tr></table></div></div>` : ""}
      ${opts.length ? `<div class="card"><h2>Other options</h2>${opts.map((o) => `<div><a data-q="${o.id}">${esc(o.optionLabel || o.number)} · ${esc(o.title)}</a> · ${money(Z.totals(o, D.lines.filter((l) => l.quoteId === o.id), s).total)}</div>`).join("")}</div>` : ""}
      ${kids.length ? `<div class="card"><h2>Change orders</h2>${kids.map((k) => `<div><a data-q="${k.id}">${esc(k.number)} · ${esc(k.title)}</a> · ${esc(Z.statusOf(k))}</div>`).join("")}</div>` : ""}
      <div class="card"><h2>Photos</h2>${photosHtml(photos)}</div>`;
  }

  function calendarPage() {
    const base = ui.month || new Date(new Date().getFullYear(), new Date().getMonth(), 1);
    const y = base.getFullYear(), m = base.getMonth(), first = new Date(y, m, 1), start = new Date(y, m, 1 - first.getDay());
    const today = new Date().toDateString();
    const events = visibleQuotes().filter((q) => q.startAt && q.status !== "DECLINED");
    let cells = "";
    for (let i = 0; i < 42; i++) {
      const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
      if (i === 35 && d.getMonth() !== m) break;
      const ev = events.filter((q) => new Date(q.startAt).toDateString() === d.toDateString()).sort((a, b) => a.startAt - b.startAt);
      cells += `<div class="d ${d.getMonth() !== m ? "out" : ""} ${d.toDateString() === today ? "today" : ""}"><div class="n">${d.getDate()}</div>
        ${ev.map((q) => `<span class="ev ${q.status === "DONE" ? "done" : ""} ${q.canceledAt ? "cx" : ""}" data-q="${q.id}" title="${esc(q.customerName + " · " + q.title)}">${Z.b(q.hasTime) ? esc(new Date(q.startAt).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })) + " " : ""}${esc(q.customerName || q.title)}</span>`).join("")}</div>`;
    }
    return `<div class="toolbar"><button class="btn ghost small" data-mo="-1">‹ Prev</button><h2 class="sec" style="margin:0;min-width:200px;text-align:center">${first.toLocaleDateString("en-US", { month: "long", year: "numeric" })}</h2>
      <button class="btn ghost small" data-mo="1">Next ›</button><button class="btn ghost small" data-mo="0">Today</button></div>
      <div class="cal">${["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].map((d) => `<div class="h label">${d}</div>`).join("")}${cells}</div>`;
  }

  function customersPage() {
    const term = ui.custSearch.trim().toLowerCase();
    const list = D.customers.filter((c) => !term || [c.name, c.phone, c.email, c.address, c.properties].join(" ").toLowerCase().includes(term)).sort((a, b) => a.name.localeCompare(b.name));
    return `<div class="toolbar"><input class="search" id="csearch" placeholder="Search name, phone, address…" value="${esc(ui.custSearch)}"><span class="muted">${list.length} customers</span></div>
      <table><tr><th>Name</th><th>Phone</th><th>Address</th><th>Found you</th><th class="num">Jobs</th><th class="num">Paid to date</th></tr>
      ${list.map((c) => { const jobs = visibleQuotes().filter((q) => q.customerId === c.id && q.kind !== "APPT");
        return `<tr class="click" data-c="${c.id}"><td>${esc(c.name)}</td><td>${esc(c.phone)}</td><td>${esc(c.address)}</td><td>${esc(c.source)}</td><td class="num">${jobs.length}</td><td class="num">${money(jobs.reduce((a, q) => a + Z.n(q.paidAmount), 0))}</td></tr>`; }).join("")}</table>`;
  }

  function customerPage(id) {
    const c = D.cust(id); if (!c) return `<div class="muted">Customer not found.</div>`;
    const props = Z.properties(c);
    const jobs = visibleQuotes().filter((q) => q.customerId === c.id).sort((a, b) => sortDate(b) - sortDate(a));
    return `<div class="card"><div class="facts">
        <div><div class="label">Phone</div><div class="v">${esc(c.phone)}</div></div><div><div class="label">Email</div><div class="v">${esc(c.email)}</div></div>
        <div><div class="label">How they found you</div><div class="v">${esc(c.source || "Not set")}</div></div></div>
        ${c.notes ? `<div style="margin-top:12px;white-space:pre-wrap" class="muted">${esc(c.notes)}</div>` : ""}
        ${c.portalUrl ? `<div style="margin-top:12px"><button class="btn ghost small" data-ext="${esc(c.portalUrl)}">Customer page</button></div>` : ""}</div>
      <div class="card"><h2>Properties</h2>${props.map((p) => `<div style="padding:6px 0"><a data-house="${c.id}" data-addr="${esc(p.address)}">${esc(p.label && p.label !== "Main" ? p.label + " · " : "")}${esc(p.address)} — House history →</a></div>`).join("") || `<div class="muted">No address saved.</div>`}</div>
      <div class="card"><h2>Jobs and visits</h2>${jobsTable(jobs)}</div>`;
  }

  function jobsTable(jobs) {
    if (!jobs.length) return `<div class="muted">None yet.</div>`;
    return `<table><tr><th>Date</th><th>Number</th><th>Job</th><th>Status</th><th class="num">Total</th></tr>${jobs.map((q) => `<tr class="click" data-q="${q.id}"><td>${esc(day(sortDate(q)))}</td><td>${esc(q.number)}</td><td>${esc(q.title)}</td>
      <td><span class="pill p-${esc(Z.statusOf(q)).replace(" ", ".")}">${esc(Z.statusOf(q))}</span></td><td class="num">${q.kind === "APPT" ? "" : money(Z.totals(q, D.lines.filter((l) => l.quoteId === q.id), D.settings).total)}</td></tr>`).join("")}</table>`;
  }

  function housePage(cid, addr) {
    const c = D.cust(cid); if (!c) return "";
    const info = Z.houseInfo(c, addr);
    const jobs = visibleQuotes().filter((q) => q.customerId === c.id && (Z.sameHouse(q.site, addr) || (!q.site && Z.sameHouse(c.address, addr)))).sort((a, b) => sortDate(b) - sortDate(a));
    const photos = D.photos.filter((p) => jobs.some((q) => q.id === p.quoteId)).sort((a, b) => b.takenAt - a.takenAt);
    const filled = Z.HOUSE_FIELDS.filter(([k]) => info[k]);
    const risky = /knob|aluminum/i.test(info.wiring || "");
    return `<div class="muted" style="margin-bottom:4px"><a data-c="${c.id}">${esc(c.name)}</a></div><h2 style="margin:0 0 18px;font-size:24px">${esc(addr)}</h2>
      ${risky ? `<div class="note warn">Knob &amp; tube or aluminum branch wiring here. Check connections and insurance requirements before adding load.</div>` : ""}
      <div class="card"><h2>The house</h2>${filled.length ? `<div class="facts">${filled.map(([k, l]) => `<div><div class="label">${esc(l)}</div><div class="v" style="white-space:pre-wrap">${esc(info[k])}</div></div>`).join("")}</div>`
        : `<div class="muted">Nothing saved yet. Fill it in on your phone: open a job here → House history.</div>`}</div>
      <div class="card"><h2>Work done here</h2>${jobsTable(jobs)}</div>
      <div class="card"><h2>Photos</h2>${photosHtml(photos)}</div>`;
  }

  function moneyPage() {
    const y = ui.year, rows = Z.money(y, D), sum = (k) => rows.reduce((a, r) => a + r[k], 0);
    const exp = D.expenses.filter((e) => new Date(e.date).getFullYear() === y);
    const cats = {}; exp.forEach((e) => cats[e.category || "Other"] = (cats[e.category || "Other"] || 0) + Z.n(e.amount));
    const top = Math.max(1, ...Object.values(cats));
    const q = [0, 1, 2, 3].map((i) => rows.slice(i * 3, i * 3 + 3).reduce((a, r) => a + r.tax, 0));
    const names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    return `<div class="toolbar"><div class="tabs">${[y - 1, y, y + 1].filter((v) => v <= new Date().getFullYear()).map((v) => `<button data-year="${v}" class="${v === y ? "on" : ""}">${v}</button>`).join("")}</div></div>
      <div class="kpis"><div class="kpi n"><div class="label">Income (no sales tax)</div><div class="v">${money(sum("income"))}</div></div>
        <div class="kpi w"><div class="label">Expenses</div><div class="v">${money(sum("expenses"))}</div></div>
        <div class="kpi i"><div class="label">Mileage deduction</div><div class="v">${money(sum("mileage"))}</div><div class="muted">${qty(sum("miles"))} miles</div></div>
        <div class="kpi o"><div class="label">Net profit</div><div class="v">${money(sum("profit"))}</div></div></div>
      <div class="grid2"><div class="card"><h2>By month</h2><table><tr><th>Month</th><th class="num">Income</th><th class="num">Expenses</th><th class="num">Miles</th><th class="num">Profit</th></tr>
        ${rows.map((r, i) => `<tr><td>${names[i]}</td><td class="num">${money(r.income)}</td><td class="num">${money(r.expenses)}</td><td class="num">${qty(r.miles)}</td><td class="num ${r.profit < 0 ? "bad" : ""}">${money(r.profit)}</td></tr>`).join("")}</table></div>
      <div><div class="card"><h2>Sales tax collected (owed to NY)</h2><table>${q.map((v, i) => `<tr><td>Q${i + 1} ${["Jan–Mar", "Apr–Jun", "Jul–Sep", "Oct–Dec"][i]}</td><td class="num">${money(v)}</td></tr>`).join("")}
        <tr><td><b>Year</b></td><td class="num"><b>${money(sum("tax"))}</b></td></tr></table>
        <div class="muted" style="margin-top:8px">NY sales tax returns are due Mar 20, Jun 20, Sep 20 and Dec 20 for quarterly filers.</div></div>
        <div class="card"><h2>Expenses by category</h2>${Object.keys(cats).length ? Object.entries(cats).sort((a, b) => b[1] - a[1]).map(([k, v]) => `<div style="margin-bottom:10px"><div style="display:flex;justify-content:space-between"><span>${esc(k)}</span><span style="font-family:var(--mono)">${money(v)}</span></div><div class="bar" style="width:${Math.max(2, v / top * 100)}%"></div></div>`).join("") : `<div class="muted">No expenses this year.</div>`}</div></div></div>
      <div class="card"><h2>Expenses</h2>${exp.length ? `<table><tr><th>Date</th><th>Vendor</th><th>Category</th><th>Note</th><th class="num">Amount</th></tr>${exp.sort((a, b) => b.date - a.date).map((e) => `<tr><td>${esc(day(e.date))}</td><td>${esc(e.vendor)}</td><td>${esc(e.category)}</td><td class="muted">${esc(e.note)}</td><td class="num">${money(e.amount)}</td></tr>`).join("")}</table>` : `<div class="muted">None.</div>`}</div>`;
  }

  function stockPage() {
    const held = Z.holds(D.quotes, D.lines), term = ui.stockSearch.trim().toLowerCase();
    const list = D.materials.filter((m) => !term || [m.name, m.spec, m.location].join(" ").toLowerCase().includes(term)).sort((a, b) => a.name.localeCompare(b.name));
    return `<div class="toolbar"><input class="search" id="ssearch" placeholder="Search stock…" value="${esc(ui.stockSearch)}"><span class="muted">${list.length} items · ${money(list.reduce((a, m) => a + Z.n(m.onHand) * Z.n(m.cost), 0))} on hand</span></div>
      <table><tr><th>Item</th><th>Where</th><th class="num">On hand</th><th class="num">On hold</th><th class="num">Free</th><th class="num">Cost</th></tr>
      ${list.map((m) => { const h = held[m.id] || 0, free = Z.n(m.onHand) - h, low = Z.n(m.alertLevel) > 0 && free <= Z.n(m.alertLevel);
        return `<tr><td>${esc(m.name)}<div class="muted">${esc(m.spec)}</div></td><td>${esc(m.location)}</td><td class="num">${qty(m.onHand)} ${esc(m.unit)}</td><td class="num">${qty(h)}</td><td class="num ${free < 0 ? "bad" : low ? "warn" : ""}">${qty(free)}</td><td class="num">${money(m.cost)}</td></tr>`; }).join("")}</table>`;
  }

  // New quote / appointment, or additions to an existing job: sent to the phone through the inbox.
  function newPage(editId) {
    const q = editId ? D.quote(editId) : null, s = D.settings;
    const custOpts = D.customers.map((c) => `<option value="${esc(c.name)}">`).join("");
    const stockOpts = D.materials.map((m) => `<option value="${esc(m.name)}">`).join("");
    const lineRow = () => `<tr><td><input type="text" list="stock" class="ln-name" placeholder="Item (pick from stock or type)"></td><td style="width:110px"><input type="number" class="ln-qty" value="1" min="0" step="any"></td>
      <td style="width:130px"><input type="number" class="ln-cost" placeholder="from stock" step="any"></td>${!q || q.jobType === "BOTH" ? `<td style="width:90px;text-align:center"><input type="checkbox" class="ln-rep"></td>` : ""}</tr>`;
    return `<datalist id="custs">${custOpts}</datalist><datalist id="stock">${stockOpts}</datalist>
      <div class="note">${q ? `Changes to <b>${esc(q.number)}</b> go to your phone, and materials are <b>added</b> to what's there. Open the app on your phone to pull them in right away.` : `This goes to your phone and shows up as a new ${"draft"} there. Send it to the customer from your phone, so their texts come from your number.`}</div>
      <form id="jobform">
        ${q ? "" : `<div class="row"><label><span class="label">What</span><select name="kind"><option value="QUOTE">Quote</option><option value="APPT">Appointment / service call</option></select></label>
          <label><span class="label">Customer name</span><input type="text" name="name" list="custs" required></label>
          <label><span class="label">Phone</span><input type="tel" name="phone"></label></div>`}
        <div class="row"><label><span class="label">Job name</span><input type="text" name="title" value="${esc(q ? q.title : "")}"></label>
          <label><span class="label">Job site address</span><input type="text" name="site" value="${esc(q ? q.site : "")}"></label>
          <label><span class="label">Job type</span><select name="jobType">${["INSTALL", "REPAIR", "BOTH"].map((v) => `<option value="${v}" ${(q ? q.jobType : "REPAIR") === v ? "selected" : ""}>${{ INSTALL: "New installation (ST-124)", REPAIR: "Repair", BOTH: "Install + repair" }[v]}</option>`).join("")}</select></label></div>
        <div class="row"><label><span class="label">Date / time (optional)</span><input type="datetime-local" name="start" value="${q && q.startAt ? new Date(q.startAt - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ""}"></label>
          <label><span class="label">Labor hours${q && q.jobType === "BOTH" ? " (install)" : ""}</span><input type="number" name="hours" step="any" value="${q ? qty(q.laborHours) : ""}"></label>
          <label><span class="label">Repair hours (install + repair jobs)</span><input type="number" name="rhours" step="any" value="${q ? qty(q.repairHours) : ""}"></label></div>
        <label style="margin-bottom:14px"><span class="label">Work to be done (customer sees this)</span><textarea name="notes">${esc(q ? q.notes : "")}</textarea></label>
        <label style="margin-bottom:14px"><span class="label">Private note (only you)</span><textarea name="privateNote" style="min-height:50px">${esc(q ? q.privateNote : "")}</textarea></label>
        <h2 class="sec">${q ? "Add materials" : "Materials"}</h2>
        <table id="lines"><tr><th>Item</th><th>Qty</th><th>Your cost each</th>${!q || q.jobType === "BOTH" ? "<th>Repair?</th>" : ""}</tr>${lineRow()}${lineRow()}${lineRow()}</table>
        <button type="button" class="btn ghost small" id="addline" style="margin:10px 0 20px">+ Another item</button><br>
        <button class="btn" type="submit">${q ? "Send changes to phone" : "Send to phone"}</button> <span id="sendmsg" class="muted" style="margin-left:12px"></span>
      </form>
      <template id="linerow">${lineRow()}</template>`;
  }

  async function submitJob(form, editId) {
    const q = editId ? D.quote(editId) : null, f = new FormData(form), msg = document.getElementById("sendmsg");
    const num = (k) => f.get(k) === "" || f.get(k) == null ? undefined : Number(f.get(k));
    const quote = {};
    if (q) quote.number = q.number;
    else { quote.kind = f.get("kind"); quote.status = "DRAFT"; }
    ["title", "site", "jobType", "notes", "privateNote"].forEach((k) => { const v = String(f.get(k) ?? "").trim(); if (q ? v !== String(q[k] ?? "") : v) quote[k] = v; });
    if (num("hours") !== undefined) quote.laborHours = num("hours");
    if (num("rhours") !== undefined) quote.repairHours = num("rhours");
    if (f.get("start")) { quote.startAt = new Date(f.get("start")).getTime(); quote.hasTime = true; }
    const lines = [...form.querySelectorAll("#lines tr")].slice(1).map((tr) => {
      const name = tr.querySelector(".ln-name").value.trim(); if (!name) return null;
      const l = { name, qty: Number(tr.querySelector(".ln-qty").value) || 1 };
      const cost = tr.querySelector(".ln-cost").value; if (cost !== "") l.unitCost = Number(cost);
      const rep = tr.querySelector(".ln-rep"); if (rep && rep.checked) l.repair = true;
      const m = D.materials.find((x) => x.name.toLowerCase() === name.toLowerCase()); if (m) l.unit = m.unit;
      return l;
    }).filter(Boolean);
    const data = { quote, lines };
    if (!q) {
      const name = String(f.get("name") || "").trim();
      if (!name) { msg.textContent = "Add the customer's name."; return; }
      const known = D.customers.find((c) => c.name.toLowerCase() === name.toLowerCase());
      data.customer = { name, phone: String(f.get("phone") || "").trim() || (known ? known.phone : "") };
      if (!quote.site && known) quote.site = known.address;
    } else if (q.customerName) data.customer = { name: q.customerName };
    msg.textContent = "Sending…";
    try { await call({ action: "send_job", data }); msg.innerHTML = `<span class="ok">Sent. It'll be on your phone in a few minutes, or right away when you open the app.</span>`; form.querySelector("button[type=submit]").disabled = true; }
    catch (e) { msg.innerHTML = `<span class="bad">Couldn't send: ${esc(e.message)}</span>`; }
  }

  // Customer copy as a PDF, in the same plain professional style as the phone's.
  function quotePdfHtml(q) {
    const s = D.settings, c = D.cust(q.customerId), ql = D.lines.filter((l) => l.quoteId === q.id && !Z.b(l.optional)), t = Z.totals(q, D.lines.filter((l) => l.quoteId === q.id), s);
    const addons = D.lines.filter((l) => l.quoteId === q.id && Z.b(l.optional));
    const row = (a, b2, strong) => `<tr><td${strong ? ' style="font-weight:700"' : ""}>${a}</td><td style="text-align:right${strong ? ";font-weight:700" : ""}">${b2}</td></tr>`;
    return `<!doctype html><html><head><meta charset="utf-8"><style>
      body{font:12.5px/1.5 "Segoe UI",Arial,sans-serif;color:#111;margin:0} h1{font-size:22px;margin:0} .top{display:flex;justify-content:space-between;border-bottom:3px solid #111;padding-bottom:10px;margin-bottom:16px}
      .k{font:600 10px Arial;letter-spacing:.12em;text-transform:uppercase;color:#555} table{width:100%;border-collapse:collapse;margin-top:6px} td,th{padding:6px 4px;border-bottom:1px solid #ddd;text-align:left}
      th{font:600 10px Arial;letter-spacing:.1em;text-transform:uppercase;color:#555} .r{text-align:right} .tot td{border:0} h3{font-size:12px;letter-spacing:.12em;text-transform:uppercase;margin:18px 0 4px}
      .two{display:flex;gap:30px}.two>div{flex:1}</style></head><body>
      <div class="top"><div><h1>${esc(s.businessName || "Zimdars Electric LLC")}</h1><div>${esc(s.address)}</div><div>${esc(s.phone)} ${s.email ? "· " + esc(s.email) : ""}</div></div>
        <div style="text-align:right"><div class="k">Quote</div><div style="font-size:18px;font-weight:700">${esc(q.number)}</div><div>${esc(day(q.createdAt))}</div></div></div>
      <div class="two"><div><div class="k">Customer</div><div>${esc(c ? c.name : q.customerName)}</div><div>${esc(c ? c.phone : "")}</div></div><div><div class="k">Job site</div><div>${esc(q.site || (c && c.address) || "")}</div></div>
        <div><div class="k">Job</div><div>${esc(q.title)}</div></div></div>
      ${q.notes ? `<h3>Work to be done</h3><div style="white-space:pre-wrap">${esc(q.notes)}</div>` : ""}${q.repairNotes ? `<h3>Repair work</h3><div style="white-space:pre-wrap">${esc(q.repairNotes)}</div>` : ""}
      ${ql.length ? `<h3>Materials</h3><table><tr><th>Item</th><th class="r">Qty</th><th class="r">Price</th></tr>${ql.map((l) => `<tr><td>${esc(l.name)}</td><td class="r">${qty(l.qty)} ${esc(l.unit)}</td><td class="r">${money(Z.linePrice(q, l))}</td></tr>`).join("")}</table>` : ""}
      ${addons.length ? `<h3>Optional add-ons</h3><table>${addons.map((l) => `<tr><td>${esc(l.name)}</td><td class="r">${Z.n(l.addOnState) === 1 ? "Added" : Z.n(l.addOnState) === 2 ? "No thanks" : "Optional"}</td><td class="r">+${money(Z.linePrice(q, l))}</td></tr>`).join("")}</table>` : ""}
      <table class="tot" style="width:320px;margin-left:auto;margin-top:16px">${row("Materials", money(t.materialsPrice))}${row("Labor", money(t.labor))}${row("Sales tax", money(t.salesTax))}${row("Total", money(t.total), true)}</table>
      ${q.jobType === "INSTALL" ? `<p style="margin-top:14px">This is a capital improvement under New York State law, so no sales tax is charged. The customer signs Form ST-124.</p>` : ""}
      ${s.paymentTerms ? `<h3>Payment</h3><div>${esc(s.paymentTerms)}</div>` : ""}${s.warranty ? `<h3>Warranty</h3><div>${esc(s.warranty)}</div>` : ""}
      ${q.signedAt ? `<h3>Signed</h3><div>${esc(q.signerName)} · ${esc(day(q.signedAt))}</div>` : ""}
      </body></html>`;
  }

  // ---------- Shell ----------
  const PAGES = [["home", "Home"], ["quotes", "Quotes"], ["calendar", "Calendar"], ["customers", "Customers"], ["money", "Money"], ["stock", "Stock"]];
  const stack = [];
  function go(v, push = true) { if (push) stack.push(view); view = v; render(); document.querySelector(".main")?.scrollTo(0, 0); }
  function back() { view = stack.pop() || { page: "home" }; render(); }

  function title() {
    switch (view.page) {
      case "quote": { const q = D.quote(view.id); return q ? `${q.number} · ${q.customerName || q.title}` : "Job"; }
      case "customer": { const c = D.cust(view.id); return c ? c.name : "Customer"; }
      case "house": return "House history";
      case "new": return view.edit ? "Add to job" : "New quote or appointment";
      default: return (PAGES.find((p) => p[0] === view.page) || [0, ""])[1];
    }
  }

  function render() {
    if (!key) { $root.innerHTML = pairPage(); return; }
    if (!D) { $root.innerHTML = `<div class="center"><div class="brand" style="font-size:30px">ZIMDARS ELECTRIC<small>FOR PC</small></div>
      <p class="muted">${loadErr ? esc(loadErr) : updatedAt === null ? "Loading…" : "Waiting for your phone to send its data. Open the app on your phone, then Settings → 19 → Send to PC now."}</p>
      <button class="btn ghost" data-refresh>Try again</button> <button class="btn ghost" data-unlink>Unlink</button></div>`; return; }
    const age = updatedAt ? Math.round((Date.now() - new Date(updatedAt).getTime()) / 60000) : null;
    const top = ["home", "quotes", "calendar", "customers", "money", "stock"].includes(view.page);
    let body = "";
    try {
      body = { home: homePage, quotes: quotesPage, quote: () => quotePage(view.id), calendar: calendarPage, customers: customersPage, customer: () => customerPage(view.id),
        house: () => housePage(view.id, view.addr), money: moneyPage, stock: stockPage, new: () => newPage(view.edit) }[view.page]();
    } catch (e) { body = `<div class="note warn">Something went wrong showing this page: ${esc(e.message)}</div>`; }
    $root.innerHTML = `<div class="app"><aside class="side"><div class="brand">ZIMDARS<br>ELECTRIC<small>FOR PC</small></div>
      <nav class="nav">${PAGES.map(([k, l]) => `<button data-page="${k}" class="${view.page === k ? "on" : ""}">${l}</button>`).join("")}</nav>
      <button class="btn new" data-new>+ New</button>
      <div class="foot">${loadErr ? `<span class="warn">${esc(loadErr)}</span><br>` : ""}From your phone ${age === null ? "" : age < 1 ? "just now" : age < 60 ? age + " min ago" : day(new Date(updatedAt).getTime())}<br>
        <a data-refresh>Refresh</a> · <a data-unlink>Unlink</a></div></aside>
      <main class="main"><div class="top">${top ? "" : `<button class="btn ghost small" data-back>← Back</button>`}<h1>${esc(title())}</h1></div><div class="page">${body}</div></main></div>`;
  }

  // ---------- Events ----------
  document.addEventListener("click", async (e) => {
    const el = e.target.closest("[data-page],[data-q],[data-c],[data-house],[data-back],[data-new],[data-edit],[data-tab],[data-mo],[data-year],[data-ext],[data-pdf],[data-refresh],[data-unlink],#pair,#addline,img[data-photo],.viewer");
    if (!el) return;
    const d = el.dataset;
    if (el.classList.contains("viewer")) { el.remove(); return; }
    if (el.matches("img[data-photo]")) { if (!el.src) return; const v = document.createElement("div"); v.className = "viewer"; v.innerHTML = `<img src="${esc(el.src)}"><div>${esc(d.cap)}</div>`; document.body.appendChild(v); return; }
    if (el.id === "pair") return pair();
    if (el.id === "addline") { const t = document.getElementById("linerow"); (document.querySelector("#lines tbody") || document.getElementById("lines")).insertAdjacentHTML("beforeend", t.innerHTML); return; }
    if ("page" in d) { stack.length = 0; return go({ page: d.page }, false); }
    if ("q" in d) return go({ page: "quote", id: Number(d.q) });
    if ("house" in d) return go({ page: "house", id: Number(d.house), addr: d.addr });
    if ("c" in d) return go({ page: "customer", id: Number(d.c) });
    if ("back" in d) return back();
    if ("new" in d) return go({ page: "new" });
    if ("edit" in d) return go({ page: "new", edit: Number(d.edit) });
    if ("tab" in d) { ui.quotesTab = Number(d.tab); return render(); }
    if ("year" in d) { ui.year = Number(d.year); return render(); }
    if ("mo" in d) { const b = ui.month || new Date(new Date().getFullYear(), new Date().getMonth(), 1); ui.month = d.mo === "0" ? null : new Date(b.getFullYear(), b.getMonth() + Number(d.mo), 1); return render(); }
    if ("ext" in d) return openExternal(d.ext);
    if ("pdf" in d) { const q = D.quote(Number(d.pdf)); if (q) savePdf(quotePdfHtml(q), `${q.number} ${q.customerName || ""}`.trim()); return; }
    if ("refresh" in d) return load();
    if ("unlink" in d) { if (confirm("Unlink this PC? You'll need a new code from your phone to link it again.")) { key = null; store.set("key", null); D = null; render(); } return; }
  });
  document.addEventListener("input", (e) => {
    const id = e.target.id;
    if (id === "qsearch" || id === "csearch" || id === "ssearch") {
      ui[{ qsearch: "search", csearch: "custSearch", ssearch: "stockSearch" }[id]] = e.target.value;
      const pos = e.target.selectionStart; render(); const el = document.getElementById(id); if (el) { el.focus(); el.setSelectionRange(pos, pos); }
    }
  });
  document.addEventListener("submit", (e) => { if (e.target.id === "jobform") { e.preventDefault(); submitJob(e.target, view.edit); } });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && e.target.id === "code") pair();
    if (e.key === "Escape") { const v = document.querySelector(".viewer"); if (v) v.remove(); }
  });

  async function pair() {
    const code = (document.getElementById("code").value || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
    const err = document.getElementById("pairErr");
    if (code.length !== 8) { err.textContent = "The code is 8 letters and numbers."; return; }
    err.textContent = "Linking…";
    try {
      const r = await call({ action: "pair_finish", code }, false);
      key = r.key; store.set("key", key); updatedAt = null; render(); load();
    } catch (e) { err.textContent = "That code didn't work. Codes work once, for 15 minutes. Get a new one on your phone."; }
  }

  render();
  if (key) load();
  setInterval(() => { if (key) load(true); }, 120_000);
})();
