// Zimdars Sheets: your own spreadsheet, inside Zimdars Electric for PC. Saved to your private cloud.
(function (root) {
  const F = root.ZF;
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const DEF_W = 110, ROWS = 100, COLS = 26;
  let ctx = null;            // { call, data, savePdf }
  let list = null, listErr = null;
  let book = null;           // { id, name, data: { sheets: [{ name, cells, fmt, cols, rows, ncols }], active } }
  let computed = [];
  let sel = { r: 0, c: 0, r2: 0, c2: 0 };
  let editing = null;        // { r, c, input, fromBar }
  let undo = [], redo = [];
  let saveTimer = null, saveState = "", clip = null, dragging = false, rootEl = null;

  const sheet = () => book.data.sheets[book.data.active || 0];
  const A = (r, c) => F.addr(r, c);
  const norm = () => ({ r0: Math.min(sel.r, sel.r2), r1: Math.max(sel.r, sel.r2), c0: Math.min(sel.c, sel.c2), c1: Math.max(sel.c, sel.c2) });
  const blankSheet = (name) => ({ name, cells: {}, fmt: {}, cols: {}, rows: ROWS, ncols: COLS });
  const serialToDate = (n) => new Date(Date.UTC(1899, 11, 30) + Math.round(n * 86400000));
  const toSerial = (ms) => { const d = new Date(ms); return (Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) - Date.UTC(1899, 11, 30)) / 86400000; };

  // ---------- Display ----------
  function show(v, f) {
    if (F.isErr(v)) return { t: v.err, cls: "err" };
    if (v === "" || v == null) return { t: "", cls: "" };
    if (typeof v === "boolean") return { t: v ? "TRUE" : "FALSE", cls: "c" };
    if (typeof v === "number") {
      const nf = f && f.nf;
      let t;
      if (nf === "$") t = (v < 0 ? "-$" : "$") + Math.abs(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      else if (nf === "%") t = (v * 100).toLocaleString("en-US", { maximumFractionDigits: 2 }) + "%";
      else if (nf === "0.00") t = v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      else if (nf === "0") t = Math.round(v).toLocaleString("en-US");
      else if (nf === "date") { const d = serialToDate(v); t = (d.getUTCMonth() + 1) + "/" + d.getUTCDate() + "/" + d.getUTCFullYear(); }
      else t = Math.abs(v) >= 1e11 || (Math.abs(v) < 1e-6 && v !== 0) ? v.toExponential(4) : String(Math.round(v * 1e9) / 1e9);
      return { t, cls: "n" };
    }
    return { t: String(v), cls: "" };
  }

  function recompute() {
    try { computed = F.computeBook(book.data); } catch (e) { computed = book.data.sheets.map(() => ({})); }
  }

  // ---------- Saving ----------
  function changed() {
    recompute(); paintCells(); paintBar();
    saveState = "Saving…"; paintStatus();
    clearTimeout(saveTimer); saveTimer = setTimeout(save, 1200);
  }
  async function save() {
    if (!book) return;
    try {
      const r = await ctx.call({ action: "sheet_save", id: book.id || undefined, name: book.name, data: book.data });
      book.id = r.id; saveState = "Saved"; list = null;
    } catch (e) { saveState = "Couldn't save — will retry"; clearTimeout(saveTimer); saveTimer = setTimeout(save, 8000); }
    paintStatus();
  }

  function snapshot() { return JSON.stringify({ i: book.data.active || 0, s: sheet() }); }
  function remember() { undo.push(snapshot()); if (undo.length > 150) undo.shift(); redo = []; }
  function restore(from, to) {
    const snap = from.pop(); if (!snap) return;
    to.push(snapshot());
    const o = JSON.parse(snap); book.data.active = o.i; book.data.sheets[o.i] = o.s;
    mountEditor(); changed();
  }

  function setCell(r, c, raw) {
    const s = sheet(), a = A(r, c);
    if (raw === "" || raw == null) delete s.cells[a]; else s.cells[a] = raw;
    if (r >= s.rows - 1) s.rows = r + 51;
    if (c >= s.ncols - 1) s.ncols = Math.min(c + 6, 702);
  }

  // ---------- List of sheets ----------
  async function loadList() {
    try { list = (await ctx.call({ action: "sheets_list" })).items || []; listErr = null; } catch (e) { listErr = "Can't reach the cloud right now."; list = []; }
    paint();
  }
  function listHtml() {
    if (!list) { loadList(); return `<div class="muted">Loading your sheets…</div>`; }
    const ago = (t) => new Date(t).toLocaleString("en-US", { month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit" });
    return `<div class="toolbar">
        <button class="btn" data-sx="blank">+ New sheet</button>
        <button class="btn ghost" data-sx="open">Open Excel / CSV file</button>
        <input type="file" id="sfile" accept=".xlsx,.xls,.csv,.ods" style="display:none">
        <span class="label" style="margin-left:12px">Start from your app:</span>
        ${[["jobs", "Jobs"], ["expenses", "Expenses"], ["customers", "Customers"], ["stock", "Stock"], ["mileage", "Mileage"]].map(([k, l]) => `<button class="btn ghost small" data-sfrom="${k}">${l}</button>`).join("")}
      </div>
      ${listErr ? `<div class="note warn">${esc(listErr)}</div>` : ""}
      ${list.length ? `<table><tr><th>Name</th><th>Last changed</th><th></th></tr>${list.map((s) => `<tr class="click" data-sopen="${esc(s.id)}"><td>${esc(s.name)}</td><td class="muted">${esc(ago(s.updated_at))}</td>
        <td class="right"><button class="btn ghost small" data-sdel="${esc(s.id)}" data-sname="${esc(s.name)}">Delete</button></td></tr>`).join("")}</table>`
        : `<div class="card"><h2>Your own spreadsheets</h2><div class="muted">Make a blank sheet, open an Excel or CSV file someone sent you, or start from your jobs, expenses, customers, stock or mileage.
        Formulas work like Excel: =SUM(D2:D20), =B2*C2, =IF(E2>500,"big","small"), =ROUND(D9*8.75%,2), =VLOOKUP(...). Everything saves by itself.</div></div>`}`;
  }

  // ---------- Editor ----------
  function editorHtml() {
    const s = sheet();
    return `<div class="sx-top">
        <button class="btn ghost small" data-sx="list">← All sheets</button>
        <input id="sname" class="sx-name" value="${esc(book.name)}" title="Sheet name">
        <span id="sstatus" class="label"></span>
        <span style="flex:1"></span>
        <button class="btn ghost small" data-sx="bold" title="Bold (Ctrl+B)"><b>B</b></button>
        <button class="btn ghost small" data-snf="$" title="Money">$</button>
        <button class="btn ghost small" data-snf="%" title="Percent">%</button>
        <button class="btn ghost small" data-snf="0.00" title="2 decimals">.00</button>
        <button class="btn ghost small" data-snf="date" title="Date">Date</button>
        <button class="btn ghost small" data-snf="" title="Plain number">123</button>
        <button class="btn ghost small" data-sal="l" title="Align left">⟸</button>
        <button class="btn ghost small" data-sal="c" title="Center">≡</button>
        <button class="btn ghost small" data-sal="r" title="Align right">⟹</button>
        <button class="btn ghost small" data-sx="sum" title="Add a total under the selection">Σ Sum</button>
        <button class="btn ghost small" data-sx="xlsx">Save as Excel</button>
        <button class="btn ghost small" data-sx="csv">CSV</button>
        <button class="btn ghost small" data-sx="print">Print / PDF</button>
      </div>
      <div class="sx-bar"><span id="saddr" class="sx-addr"></span><span class="sx-fx">fx</span><input id="sfx" spellcheck="false" autocomplete="off"></div>
      <div class="sx-grid" id="sgrid" tabindex="0">${gridHtml(s)}</div>
      <div class="sx-tabs">${book.data.sheets.map((x, i) => `<button data-ssheet="${i}" class="${i === (book.data.active || 0) ? "on" : ""}" title="Double-click to rename">${esc(x.name)}</button>`).join("")}
        <button data-sx="addsheet" title="New tab">+</button><span style="flex:1"></span>
        <button data-sx="rows">+ 100 rows</button><button data-sx="delsheet" class="warn">Delete this tab</button></div>`;
  }
  function gridHtml(s) {
    let h = `<table class="sx"><thead><tr><th class="sx-corner"></th>`;
    for (let c = 0; c < s.ncols; c++) { const L = F.colName(c); h += `<th style="width:${s.cols[L] || DEF_W}px;min-width:${s.cols[L] || DEF_W}px" data-shead="${c}">${L}<span class="sx-rz" data-srz="${c}"></span></th>`; }
    h += `</tr></thead><tbody>`;
    for (let r = 0; r < s.rows; r++) {
      h += `<tr><th data-srow="${r}">${r + 1}</th>`;
      for (let c = 0; c < s.ncols; c++) h += `<td data-sr="${r}" data-sc="${c}"></td>`;
      h += `</tr>`;
    }
    return h + `</tbody></table>`;
  }
  function cellEl(r, c) { return rootEl && rootEl.querySelector(`td[data-sr="${r}"][data-sc="${c}"]`); }

  function paintCells() {
    const grid = rootEl && rootEl.querySelector("#sgrid"); if (!grid) return;
    const s = sheet(), out = computed[book.data.active || 0] || {};
    grid.querySelectorAll("td[data-sr]").forEach((td) => {
      const a = A(Number(td.dataset.sr), Number(td.dataset.sc)), f = s.fmt[a], v = out[a];
      const d = show(v === undefined ? "" : v, f);
      if (td.textContent !== d.t) td.textContent = d.t;
      td.className = [d.cls, f && f.b ? "b" : "", f && f.al ? "al-" + f.al : ""].filter(Boolean).join(" ");
    });
    paintSel();
  }
  function paintSel() {
    if (!rootEl) return;
    rootEl.querySelectorAll("td.sel, td.cur").forEach((td) => td.classList.remove("sel", "cur"));
    rootEl.querySelectorAll("th.hl").forEach((t) => t.classList.remove("hl"));
    const n = norm();
    for (let r = n.r0; r <= Math.min(n.r1, sheet().rows - 1); r++) for (let c = n.c0; c <= Math.min(n.c1, sheet().ncols - 1); c++) { const td = cellEl(r, c); if (td) td.classList.add("sel"); }
    const cur = cellEl(sel.r, sel.c); if (cur) cur.classList.add("cur");
    for (let c = n.c0; c <= n.c1; c++) { const h = rootEl.querySelector(`th[data-shead="${c}"]`); if (h) h.classList.add("hl"); }
    for (let r = n.r0; r <= n.r1; r++) { const h = rootEl.querySelector(`th[data-srow="${r}"]`); if (h) h.classList.add("hl"); }
  }
  function paintBar() {
    const n = norm(), a = rootEl && rootEl.querySelector("#saddr"), fx = rootEl && rootEl.querySelector("#sfx");
    if (a) a.textContent = n.r0 === n.r1 && n.c0 === n.c1 ? A(sel.r, sel.c) : A(n.r0, n.c0) + ":" + A(n.r1, n.c1);
    if (fx && !(editing && editing.fromBar)) { const raw = sheet().cells[A(sel.r, sel.c)]; fx.value = raw == null ? "" : String(raw); }
  }
  function paintStatus() { const el = rootEl && rootEl.querySelector("#sstatus"); if (el) { el.textContent = saveState; el.className = "label " + (/Couldn/.test(saveState) ? "warn" : ""); } }
  function scrollIntoView(r, c) { const td = cellEl(r, c); if (td) td.scrollIntoView({ block: "nearest", inline: "nearest" }); }

  function select(r, c, extend) {
    r = Math.max(0, r); c = Math.max(0, Math.min(c, sheet().ncols - 1));
    if (r >= sheet().rows) { sheet().rows = r + 50; mountEditor(); }
    if (extend) { sel.r2 = r; sel.c2 = c; } else sel = { r, c, r2: r, c2: c };
    paintSel(); paintBar(); scrollIntoView(extend ? r : sel.r, extend ? c : sel.c);
  }

  // ---------- Editing a cell ----------
  function startEdit(initial, fromBar) {
    if (editing) return;
    const td = cellEl(sel.r, sel.c); if (!td && !fromBar) return;
    const raw = sheet().cells[A(sel.r, sel.c)];
    if (fromBar) { editing = { r: sel.r, c: sel.c, input: rootEl.querySelector("#sfx"), fromBar: true }; return; }
    const inp = document.createElement("input");
    inp.className = "sx-edit"; inp.spellcheck = false; inp.autocomplete = "off";
    inp.value = initial != null ? initial : raw == null ? "" : String(raw);
    const grid = rootEl.querySelector("#sgrid"), gr = grid.getBoundingClientRect(), tr = td.getBoundingClientRect();
    inp.style.left = (tr.left - gr.left + grid.scrollLeft) + "px"; inp.style.top = (tr.top - gr.top + grid.scrollTop) + "px";
    inp.style.minWidth = tr.width + "px"; inp.style.height = tr.height + "px";
    grid.appendChild(inp); inp.focus(); inp.setSelectionRange(inp.value.length, inp.value.length);
    editing = { r: sel.r, c: sel.c, input: inp, fromBar: false };
    inp.addEventListener("input", () => { const fx = rootEl.querySelector("#sfx"); if (fx) fx.value = inp.value; });
  }
  function commit(move) {
    if (!editing) return;
    const { r, c, input, fromBar } = editing; const v = input.value;
    editing = null; if (!fromBar) input.remove();
    const before = sheet().cells[A(r, c)];
    if (String(before == null ? "" : before) !== v) {
      remember(); setCell(r, c, v.trim() === "" ? "" : v);
      // Typing $12.50 or 15% formats the cell as money or percent, like Excel.
      const a = A(r, c), f = sheet().fmt[a] || {};
      if (/^\s*-?\$/.test(v) && typeof F.literal(v) === "number" && !f.nf) sheet().fmt[a] = Object.assign({}, f, { nf: "$" });
      else if (/%\s*$/.test(v) && typeof F.literal(v) === "number" && !f.nf) sheet().fmt[a] = Object.assign({}, f, { nf: "%" });
      changed();
    }
    if (move === "down") select(r + 1, c); else if (move === "right") select(r, c + 1); else if (move === "up") select(r - 1, c); else if (move === "left") select(r, c - 1);
    rootEl.querySelector("#sgrid").focus();
  }
  function cancel() { if (!editing) return; const { input, fromBar } = editing; editing = null; if (!fromBar) input.remove(); paintBar(); rootEl.querySelector("#sgrid").focus(); }

  // Typing a formula and clicking a cell drops that cell's address in, like Excel.
  function pointing() { if (!editing) return false; const v = editing.input.value; return v.startsWith("=") && /[=(,+\-*/^&<>:]\s*$/.test(v); }

  // ---------- Copy / paste ----------
  function selectionRaws() {
    const n = norm(), s = sheet(), rows = [];
    for (let r = n.r0; r <= n.r1; r++) { const row = []; for (let c = n.c0; c <= n.c1; c++) row.push(s.cells[A(r, c)] ?? ""); rows.push(row); }
    return { rows, r: n.r0, c: n.c0 };
  }
  function selectionText() {
    const n = norm(), out = computed[book.data.active || 0] || {}, s = sheet(), lines = [];
    for (let r = n.r0; r <= n.r1; r++) { const row = []; for (let c = n.c0; c <= n.c1; c++) { const a = A(r, c), v = out[a]; row.push(v === undefined || v === "" ? "" : show(v, s.fmt[a]).t); } lines.push(row.join("\t")); }
    return lines.join("\r\n");
  }
  function pasteText(text) {
    remember();
    if (clip && clip.text === text) {
      clip.rows.forEach((row, i) => row.forEach((raw, j) => setCell(sel.r + i, sel.c + j, F.shift(raw, sel.r - clip.r + 0, sel.c - clip.c))));
      if (clip.cut) { clip.rows.forEach((row, i) => row.forEach((_, j) => { const rr = clip.r + i, cc = clip.c + j; if (rr < sel.r || rr >= sel.r + clip.rows.length || cc < sel.c || cc >= sel.c + row.length) setCell(rr, cc, ""); })); clip = null; }
      sel.r2 = sel.r + (clip ? clip.rows.length : 1) - 1;
    } else {
      const rows = text.replace(/\r\n?/g, "\n").replace(/\n$/, "").split("\n").map((l) => l.split("\t"));
      rows.forEach((row, i) => row.forEach((v, j) => setCell(sel.r + i, sel.c + j, v)));
      sel.r2 = sel.r + rows.length - 1; sel.c2 = sel.c + Math.max(...rows.map((r) => r.length)) - 1;
    }
    if (sel.r2 >= sheet().rows || sel.c2 >= sheet().ncols) mountEditor();
    changed();
  }
  function clearSel() { const n = norm(); remember(); for (let r = n.r0; r <= n.r1; r++) for (let c = n.c0; c <= n.c1; c++) setCell(r, c, ""); changed(); }
  function fillDown() {
    const n = norm(); if (n.r1 === n.r0) return; remember();
    for (let c = n.c0; c <= n.c1; c++) { const top = sheet().cells[A(n.r0, c)] ?? ""; for (let r = n.r0 + 1; r <= n.r1; r++) setCell(r, c, F.shift(top, r - n.r0, 0)); }
    changed();
  }
  function fmtSel(fn) { const n = norm(), s = sheet(); remember(); for (let r = n.r0; r <= n.r1; r++) for (let c = n.c0; c <= n.c1; c++) { const a = A(r, c); const f = Object.assign({}, s.fmt[a]); fn(f); if (Object.keys(f).some((k) => f[k])) s.fmt[a] = f; else delete s.fmt[a]; } changed(); }
  function autoSum() {
    const n = norm(); remember();
    const row = n.r1 + 1;
    for (let c = n.c0; c <= n.c1; c++) {
      let top = n.r0; if (n.r0 === n.r1) { top = n.r0; while (top > 0 && sheet().cells[A(top - 1, c)] !== undefined && typeof F.literal(sheet().cells[A(top - 1, c)]) === "number") top--; }
      const start = n.r0 === n.r1 ? top : n.r0, end = n.r0 === n.r1 ? n.r0 - 1 : n.r1;
      setCell(n.r0 === n.r1 ? n.r0 : row, c, `=SUM(${A(start, c)}:${A(Math.max(start, end), c)})`);
    }
    changed();
  }

  // ---------- Making sheets ----------
  function newBook(name, sheets) { book = { id: null, name, data: { sheets, active: 0 } }; undo = []; redo = []; sel = { r: 0, c: 0, r2: 0, c2: 0 }; recompute(); paint(); save(); }
  function table(name, head, rows, opts) {
    const s = blankSheet(name), n = rows.length;
    head.forEach((h, c) => { s.cells[A(0, c)] = h; s.fmt[A(0, c)] = { b: 1 }; });
    rows.forEach((row, i) => row.forEach((v, c) => { if (v !== "" && v != null) s.cells[A(i + 1, c)] = typeof v === "number" ? String(Math.round(v * 1e6) / 1e6) : String(v).startsWith("=") ? v : String(v); }));
    (opts.money || []).forEach((c) => { for (let r = 1; r <= n + 1; r++) s.fmt[A(r, c)] = { nf: "$" }; });
    (opts.dates || []).forEach((c) => { for (let r = 1; r <= n; r++) s.fmt[A(r, c)] = { nf: "date" }; });
    (opts.sum || []).forEach((c) => { if (n) { s.cells[A(n + 1, c)] = `=SUM(${A(1, c)}:${A(n, c)})`; s.fmt[A(n + 1, c)] = Object.assign({ b: 1 }, s.fmt[A(n + 1, c)]); } });
    if (opts.sum && opts.sum.length && n) { s.cells[A(n + 1, 0)] = "Total"; s.fmt[A(n + 1, 0)] = { b: 1 }; }
    head.forEach((h, c) => s.cols[F.colName(c)] = (opts.widths && opts.widths[c]) || DEF_W);
    s.rows = Math.max(ROWS, n + 30); s.ncols = Math.max(COLS, head.length + 2);
    return s;
  }
  function fromApp(kind) {
    const D = ctx.data(), Z = root.ZL; if (!D) return;
    const y = new Date().getFullYear(), d = (ms) => ms ? toSerial(ms) : "";
    if (kind === "jobs") {
      const qs = D.quotes.filter((q) => !q.optionOf && !q.parentId && q.kind !== "APPT").sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
      const rows = qs.map((q) => { const t = Z.jobTotal(q, D.quotes, D.lines, D.settings); return [q.number, d(q.completedAt || q.startAt || q.createdAt), q.customerName, q.title, q.jobType, Z.statusOf(q), t.total, t.salesTax, Z.n(q.paidAmount), d(q.paidAt)]; });
      newBook(`Jobs ${y}`, [table("Jobs", ["Number", "Date", "Customer", "Job", "Type", "Status", "Total", "Sales tax", "Paid", "Paid on"], rows, { money: [6, 7, 8], dates: [1, 9], sum: [6, 7, 8], widths: [90, 95, 160, 240, 80, 90, 100, 90, 100, 95] })]);
    } else if (kind === "expenses") {
      const rows = D.expenses.slice().sort((a, b) => a.date - b.date).map((e) => [d(e.date), e.vendor, e.category, e.note, Z.n(e.amount)]);
      newBook(`Expenses ${y}`, [table("Expenses", ["Date", "Vendor", "Category", "Note", "Amount"], rows, { money: [4], dates: [0], sum: [4], widths: [95, 170, 140, 240, 100] })]);
    } else if (kind === "customers") {
      const rows = D.customers.slice().sort((a, b) => a.name.localeCompare(b.name)).map((c) => [c.name, c.phone, c.email, c.address, c.source]);
      newBook("Customers", [table("Customers", ["Name", "Phone", "Email", "Address", "Found you"], rows, { widths: [170, 120, 200, 280, 120] })]);
    } else if (kind === "stock") {
      const rows = D.materials.slice().sort((a, b) => a.name.localeCompare(b.name)).map((m, i) => [m.name, m.location, m.unit, Z.n(m.onHand), Z.n(m.cost), `=D${i + 2}*E${i + 2}`]);
      newBook("Stock", [table("Stock", ["Item", "Where", "Unit", "On hand", "Cost each", "Value"], rows, { money: [4, 5], sum: [5], widths: [260, 110, 70, 90, 100, 100] })]);
    } else if (kind === "mileage") {
      const rows = D.trips.slice().sort((a, b) => a.startedAt - b.startedAt).map((t, i) => [d(t.startedAt), t.fromLabel, t.toLabel, Z.n(t.miles), t.purpose, t.startedAt < Date.UTC(2026, 6, 1) ? 0.725 : 0.76, `=D${i + 2}*F${i + 2}`]);
      newBook(`Mileage ${y}`, [table("Mileage", ["Date", "From", "To", "Miles", "Purpose", "IRS rate", "Deduction"], rows, { money: [6], dates: [0], sum: [3, 6], widths: [95, 170, 170, 70, 220, 80, 100] })]);
    }
  }

  // ---------- Files ----------
  function parseCsv(text) {
    const rows = []; let row = [], cell = "", q = false;
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (q) { if (ch === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else q = false; } else cell += ch; continue; }
      if (ch === '"') q = true; else if (ch === ",") { row.push(cell); cell = ""; } else if (ch === "\n" || ch === "\r") { if (ch === "\r" && text[i + 1] === "\n") i++; row.push(cell); rows.push(row); row = []; cell = ""; } else cell += ch;
    }
    if (cell !== "" || row.length) { row.push(cell); rows.push(row); }
    return rows;
  }
  async function openFile(file) {
    const name = file.name.replace(/\.[^.]+$/, "");
    if (/\.csv$/i.test(file.name)) {
      const rows = parseCsv(await file.text()), s = blankSheet(name.slice(0, 30));
      rows.forEach((row, r) => row.forEach((v, c) => { if (v !== "") s.cells[A(r, c)] = v; }));
      s.rows = Math.max(ROWS, rows.length + 30); s.ncols = Math.max(COLS, ...rows.map((r) => r.length + 2));
      return newBook(name, [s]);
    }
    if (!root.XLSX) { alert("Excel files can't be opened in this copy yet. Save it as CSV and open that."); return; }
    const wb = root.XLSX.read(await file.arrayBuffer(), { cellFormula: true, cellDates: false, cellNF: true });
    const sheets = wb.SheetNames.slice(0, 30).map((sn) => {
      const ws = wb.Sheets[sn], s = blankSheet(sn.slice(0, 40));
      let maxR = 0, maxC = 0;
      Object.keys(ws).filter((k) => k[0] !== "!").forEach((a) => {
        const cell = ws[a], p = F.parseAddr(a); if (!p) return;
        maxR = Math.max(maxR, p.r); maxC = Math.max(maxC, p.c);
        if (cell.f) s.cells[a] = "=" + cell.f; else if (cell.v !== undefined && cell.v !== "") s.cells[a] = typeof cell.v === "boolean" ? (cell.v ? "TRUE" : "FALSE") : String(cell.v);
        const z = String(cell.z || "");
        const f = {};
        if (/\$/.test(z)) f.nf = "$"; else if (/%/.test(z)) f.nf = "%"; else if (cell.t === "n" && z && root.XLSX.SSF && root.XLSX.SSF.is_date(z)) f.nf = "date"; else if (/0\.00/.test(z)) f.nf = "0.00";
        if (cell.s && cell.s.font && cell.s.font.bold) f.b = 1;
        if (Object.keys(f).length) s.fmt[a] = f;
      });
      (ws["!cols"] || []).forEach((col, i) => { if (col && col.wpx) s.cols[F.colName(i)] = Math.round(col.wpx); else if (col && col.wch) s.cols[F.colName(i)] = Math.round(col.wch * 7 + 10); });
      s.rows = Math.max(ROWS, maxR + 30); s.ncols = Math.min(Math.max(COLS, maxC + 3), 702);
      return s;
    });
    newBook(name, sheets.length ? sheets : [blankSheet("Sheet 1")]);
  }
  function download(name, blob) { const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = name; document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 5000); }
  function used(s) { let r = -1, c = -1; Object.keys(s.cells).forEach((a) => { const p = F.parseAddr(a); if (p) { r = Math.max(r, p.r); c = Math.max(c, p.c); } }); return { r, c }; }
  function saveXlsx() {
    if (!root.XLSX) { alert("Excel saving isn't available in this copy yet. Use CSV for now."); return; }
    const X = root.XLSX, wb = X.utils.book_new();
    book.data.sheets.forEach((s, si) => {
      const ws = {}, u = used(s), out = computed[si] || {};
      Object.keys(s.cells).forEach((a) => {
        const raw = s.cells[a], v = out[a], f = s.fmt[a] || {};
        const cell = {};
        if (typeof raw === "string" && raw[0] === "=") cell.f = raw.slice(1);
        if (F.isErr(v)) { cell.t = "e"; cell.v = 0x0f; }
        else if (typeof v === "number") { cell.t = "n"; cell.v = v; cell.z = f.nf === "$" ? '"$"#,##0.00' : f.nf === "%" ? "0.00%" : f.nf === "0.00" ? "#,##0.00" : f.nf === "date" ? "m/d/yyyy" : f.nf === "0" ? "#,##0" : undefined; }
        else if (typeof v === "boolean") { cell.t = "b"; cell.v = v; }
        else { cell.t = "s"; cell.v = v == null ? "" : String(v); }
        ws[a] = cell;
      });
      ws["!ref"] = u.r < 0 ? "A1" : "A1:" + A(u.r, u.c);
      ws["!cols"] = Array.from({ length: Math.max(u.c + 1, 1) }, (_, c) => ({ wpx: s.cols[F.colName(c)] || DEF_W }));
      X.utils.book_append_sheet(wb, ws, s.name.replace(/[\\/?*[\]:]/g, " ").slice(0, 31) || "Sheet" + (si + 1));
    });
    const data = X.write(wb, { bookType: "xlsx", type: "array" });
    download(book.name.replace(/[^A-Za-z0-9 ._-]+/g, "-") + ".xlsx", new Blob([data], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }));
  }
  function saveCsv() {
    const s = sheet(), u = used(s), out = computed[book.data.active || 0] || {}, lines = [];
    for (let r = 0; r <= u.r; r++) { const row = []; for (let c = 0; c <= u.c; c++) { const v = out[A(r, c)]; let t = v === undefined || v === "" ? "" : F.isErr(v) ? v.err : typeof v === "number" ? (s.fmt[A(r, c)] && s.fmt[A(r, c)].nf === "date" ? show(v, s.fmt[A(r, c)]).t : String(v)) : String(v); if (/[",\n]/.test(t)) t = '"' + t.replace(/"/g, '""') + '"'; row.push(t); } lines.push(row.join(",")); }
    download((book.name + (book.data.sheets.length > 1 ? " - " + s.name : "")).replace(/[^A-Za-z0-9 ._-]+/g, "-") + ".csv", new Blob([lines.join("\r\n")], { type: "text/csv" }));
  }
  function printHtml() {
    const s = sheet(), u = used(s), out = computed[book.data.active || 0] || {};
    let rows = "";
    for (let r = 0; r <= u.r; r++) { rows += "<tr>"; for (let c = 0; c <= u.c; c++) { const a = A(r, c), f = s.fmt[a] || {}, d = show(out[a] === undefined ? "" : out[a], f); rows += `<td style="${d.cls === "n" ? "text-align:right;" : ""}${f.b ? "font-weight:700;" : ""}${f.al ? "text-align:" + { l: "left", c: "center", r: "right" }[f.al] + ";" : ""}">${esc(d.t)}</td>`; } rows += "</tr>"; }
    return `<!doctype html><html><head><meta charset="utf-8"><style>body{font:11px "Segoe UI",Arial;color:#111;margin:0}h1{font-size:15px;margin:0 0 8px}table{border-collapse:collapse}td{border:1px solid #bbb;padding:3px 6px;white-space:nowrap}</style></head>
      <body><h1>${esc(book.name)}${book.data.sheets.length > 1 ? " · " + esc(s.name) : ""}</h1><table>${rows}</table></body></html>`;
  }

  // ---------- Mount / events ----------
  function paint() {
    if (!rootEl || !document.body.contains(rootEl)) return;
    if (!book) { rootEl.innerHTML = listHtml(); return; }
    mountEditor();
  }
  function mountEditor() {
    if (!rootEl) return;
    const grid = rootEl.querySelector("#sgrid"), sx = grid ? grid.scrollLeft : 0, sy = grid ? grid.scrollTop : 0;
    editing = null;
    rootEl.innerHTML = editorHtml();
    paintCells(); paintBar(); paintStatus();
    const g = rootEl.querySelector("#sgrid"); g.scrollLeft = sx; g.scrollTop = sy; g.focus({ preventScroll: true });
  }

  function onMouseDown(e) {
    if (!book) return;
    const td = e.target.closest("td[data-sr]"), rh = e.target.closest("th[data-srow]"), ch = e.target.closest("th[data-shead]"), rz = e.target.closest("[data-srz]");
    if (rz) { e.preventDefault(); const c = Number(rz.dataset.srz), L = F.colName(c), x0 = e.clientX, w0 = sheet().cols[L] || DEF_W;
      const move = (ev) => { const w = Math.max(30, w0 + ev.clientX - x0); sheet().cols[L] = w; const th = rootEl.querySelector(`th[data-shead="${c}"]`); th.style.width = th.style.minWidth = w + "px"; };
      const up = () => { document.removeEventListener("mousemove", move); document.removeEventListener("mouseup", up); changed(); };
      document.addEventListener("mousemove", move); document.addEventListener("mouseup", up); return; }
    if (td) {
      const r = Number(td.dataset.sr), c = Number(td.dataset.sc);
      if (editing && !editing.fromBar && pointing()) { e.preventDefault(); const inp = editing.input; inp.value += A(r, c); inp.focus(); rootEl.querySelector("#sfx").value = inp.value; return; }
      if (editing && editing.fromBar && pointing()) { e.preventDefault(); editing.input.value += A(r, c); editing.input.focus(); return; }
      if (editing) commit();
      e.preventDefault(); select(r, c, e.shiftKey); dragging = true; rootEl.querySelector("#sgrid").focus({ preventScroll: true }); return;
    }
    if (rh) { if (editing) commit(); const r = Number(rh.dataset.srow); sel = e.shiftKey ? Object.assign(sel, { r2: r, c2: sheet().ncols - 1 }) : { r, c: 0, r2: r, c2: sheet().ncols - 1 }; paintSel(); paintBar(); return; }
    if (ch) { if (editing) commit(); const c = Number(ch.dataset.shead); sel = e.shiftKey ? Object.assign(sel, { r2: sheet().rows - 1, c2: c }) : { r: 0, c, r2: sheet().rows - 1, c2: c }; paintSel(); paintBar(); return; }
  }
  function onMouseOver(e) { if (!dragging) return; const td = e.target.closest("td[data-sr]"); if (td) { sel.r2 = Number(td.dataset.sr); sel.c2 = Number(td.dataset.sc); paintSel(); paintBar(); } }
  document.addEventListener("mouseup", () => { dragging = false; });

  function onKey(e) {
    if (!book || !rootEl || !document.body.contains(rootEl)) return;
    const t = e.target, ctrl = e.ctrlKey || e.metaKey;
    if (t.id === "sname") { if (e.key === "Enter") t.blur(); return; }
    if (t.id === "sfx") {
      if (!editing) startEdit(null, true);
      if (e.key === "Enter") { e.preventDefault(); commit("down"); } else if (e.key === "Escape") { e.preventDefault(); cancel(); } else if (e.key === "Tab") { e.preventDefault(); commit("right"); }
      return;
    }
    if (editing) {
      if (e.key === "Enter") { e.preventDefault(); commit(e.shiftKey ? "up" : "down"); }
      else if (e.key === "Tab") { e.preventDefault(); commit(e.shiftKey ? "left" : "right"); }
      else if (e.key === "Escape") { e.preventDefault(); cancel(); }
      else if (!editing.input.value.startsWith("=") && /^Arrow(Up|Down)$/.test(e.key)) { e.preventDefault(); commit(e.key === "ArrowUp" ? "up" : "down"); }
      return;
    }
    if (!t.closest || !t.closest("#sgrid")) return;
    const n = norm();
    if (ctrl && e.key.toLowerCase() === "z") { e.preventDefault(); restore(undo, redo); return; }
    if (ctrl && (e.key.toLowerCase() === "y" || (e.shiftKey && e.key.toLowerCase() === "z"))) { e.preventDefault(); restore(redo, undo); return; }
    if (ctrl && e.key.toLowerCase() === "b") { e.preventDefault(); const on = !(sheet().fmt[A(sel.r, sel.c)] || {}).b; fmtSel((f) => f.b = on ? 1 : 0); return; }
    if (ctrl && e.key.toLowerCase() === "d") { e.preventDefault(); fillDown(); return; }
    if (ctrl && e.key.toLowerCase() === "a") { e.preventDefault(); const u = used(sheet()); sel = { r: 0, c: 0, r2: Math.max(0, u.r), c2: Math.max(0, u.c) }; paintSel(); paintBar(); return; }
    if (ctrl) return; // copy / cut / paste handled by the clipboard events
    const moves = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
    if (moves[e.key]) { e.preventDefault(); const [dr, dc] = moves[e.key]; const base = e.shiftKey ? { r: sel.r2, c: sel.c2 } : sel; select(base.r + dr, base.c + dc, e.shiftKey); return; }
    if (e.key === "Enter") { e.preventDefault(); select(sel.r + (e.shiftKey ? -1 : 1), sel.c); return; }
    if (e.key === "Tab") { e.preventDefault(); select(sel.r, sel.c + (e.shiftKey ? -1 : 1)); return; }
    if (e.key === "Delete" || e.key === "Backspace") { e.preventDefault(); clearSel(); return; }
    if (e.key === "F2") { e.preventDefault(); startEdit(null); return; }
    if (e.key === "PageDown" || e.key === "PageUp") { e.preventDefault(); select(sel.r + (e.key === "PageDown" ? 20 : -20), sel.c); return; }
    if (e.key === "Home") { e.preventDefault(); select(sel.r, 0); return; }
    if (e.key.length === 1 && !e.altKey) { e.preventDefault(); startEdit(e.key); return; }
    void n;
  }

  function onClick(e) {
    handleClick(e);
    // Keep the keyboard on the grid after toolbar buttons, so Ctrl+Z and typing keep working.
    if (book && e.target.closest("button") && rootEl.querySelector("#sgrid") && !editing) rootEl.querySelector("#sgrid").focus({ preventScroll: true });
  }
  function handleClick(e) {
    if (!book && !e.target.closest("[data-sx],[data-sfrom],[data-sopen],[data-sdel]")) return;
    const el = e.target.closest("[data-sx],[data-sfrom],[data-sopen],[data-sdel],[data-snf],[data-sal],[data-ssheet]"); if (!el) return;
    const d = el.dataset;
    if (d.sdel !== undefined) { e.stopPropagation(); if (confirm(`Delete "${d.sname}"? This can't be undone.`)) ctx.call({ action: "sheet_delete", id: d.sdel }).finally(loadList); return; }
    if (d.sopen !== undefined) { saveState = ""; ctx.call({ action: "sheet_get", id: d.sopen }).then((r) => { book = { id: r.id, name: r.name, data: r.data && r.data.sheets ? r.data : { sheets: [blankSheet("Sheet 1")], active: 0 } }; book.data.active = Math.min(book.data.active || 0, book.data.sheets.length - 1); undo = []; redo = []; sel = { r: 0, c: 0, r2: 0, c2: 0 }; recompute(); saveState = "Saved"; paint(); }).catch(() => alert("Couldn't open that sheet.")); return; }
    if (d.sfrom) return fromApp(d.sfrom);
    if (d.snf !== undefined) return fmtSel((f) => f.nf = d.snf || undefined);
    if (d.sal) return fmtSel((f) => f.al = f.al === d.sal ? undefined : d.sal);
    if (d.ssheet !== undefined) { if (editing) commit(); book.data.active = Number(d.ssheet); sel = { r: 0, c: 0, r2: 0, c2: 0 }; mountEditor(); return; }
    switch (d.sx) {
      case "blank": return newBook("Untitled sheet", [blankSheet("Sheet 1")]);
      case "open": return rootEl.querySelector("#sfile").click();
      case "list": if (editing) commit(); clearTimeout(saveTimer); if (saveState === "Saving…") save(); book = null; list = null; return paint();
      case "bold": { const on = !(sheet().fmt[A(sel.r, sel.c)] || {}).b; return fmtSel((f) => f.b = on ? 1 : 0); }
      case "sum": return autoSum();
      case "xlsx": return saveXlsx();
      case "csv": return saveCsv();
      case "print": return ctx.savePdf(printHtml(), book.name);
      case "rows": sheet().rows += 100; mountEditor(); return changed();
      case "addsheet": { remember(); let i = book.data.sheets.length + 1; while (book.data.sheets.some((s) => s.name === "Sheet " + i)) i++; book.data.sheets.push(blankSheet("Sheet " + i)); book.data.active = book.data.sheets.length - 1; mountEditor(); return changed(); }
      case "delsheet": { if (book.data.sheets.length < 2) { alert("A sheet needs at least one tab."); return; } if (!confirm(`Delete the tab "${sheet().name}"?`)) return; book.data.sheets.splice(book.data.active || 0, 1); book.data.active = 0; undo = []; redo = []; mountEditor(); return changed(); }
    }
  }
  function onDbl(e) {
    if (!book) return;
    const tab = e.target.closest("[data-ssheet]");
    if (tab) { const i = Number(tab.dataset.ssheet), name = prompt("Tab name", book.data.sheets[i].name); if (name && name.trim()) { remember(); book.data.sheets[i].name = name.trim().slice(0, 40); mountEditor(); changed(); } return; }
    const td = e.target.closest("td[data-sr]"); if (td) { select(Number(td.dataset.sr), Number(td.dataset.sc)); startEdit(null); }
  }

  document.addEventListener("copy", (e) => { if (!book || editing || !rootEl || !document.activeElement || !document.activeElement.closest || !document.activeElement.closest("#sgrid")) return; const text = selectionText(); clip = Object.assign(selectionRaws(), { text }); e.clipboardData.setData("text/plain", text); e.preventDefault(); });
  document.addEventListener("cut", (e) => { if (!book || editing || !rootEl || !document.activeElement || !document.activeElement.closest || !document.activeElement.closest("#sgrid")) return; const text = selectionText(); clip = Object.assign(selectionRaws(), { text, cut: true }); e.clipboardData.setData("text/plain", text); e.preventDefault(); });
  document.addEventListener("paste", (e) => { if (!book || editing || !rootEl || !document.activeElement || !document.activeElement.closest || !document.activeElement.closest("#sgrid")) return; e.preventDefault(); pasteText(e.clipboardData.getData("text/plain")); });
  document.addEventListener("keydown", onKey, true);
  document.addEventListener("change", (e) => {
    if (e.target.id === "sfile" && e.target.files[0]) { openFile(e.target.files[0]).catch((err) => alert("Couldn't open that file: " + err.message)); e.target.value = ""; }
    if (e.target.id === "sname" && book) { book.name = e.target.value.trim().slice(0, 120) || "Untitled sheet"; changed(); }
  });
  document.addEventListener("input", (e) => { if (e.target.id === "sfx" && book) { if (!editing) startEdit(null, true); } });

  root.ZS = {
    mount(el, c) {
      ctx = c;
      if (rootEl !== el) { rootEl = el; el.addEventListener("mousedown", onMouseDown); el.addEventListener("mouseover", onMouseOver); el.addEventListener("click", onClick); el.addEventListener("dblclick", onDbl); }
      paint();
    },
    busy: () => !!book,   // a sheet is open: don't redraw the page underneath it
    _test: { get book() { return book; }, get computed() { return computed; }, get sel() { return sel; } },
  };
})(typeof window !== "undefined" ? window : globalThis);
