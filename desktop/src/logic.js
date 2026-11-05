// Same math as the phone app (QuoteMath.kt, House.kt), so totals match to the penny.
(function (root) {
  const HOLDING = ["DRAFT", "SENT", "APPROVED"];
  const b = (v) => v === true || v === 1 || v === "1";
  const n = (v) => Number(v) || 0;

  const counts = (l) => !b(l.optional) || n(l.addOnState) === 1;
  const isRepairLine = (q, l) => q.jobType === "REPAIR" ? true : q.jobType === "BOTH" ? b(l.repair) : false;
  const totalHours = (q) => n(q.laborHours) + (q.jobType === "BOTH" ? n(q.repairHours) : 0);
  const linePrice = (q, l) => b(l.optional) && n(l.addOnPrice) > 0 ? n(l.addOnPrice) : n(l.qty) * n(l.unitCost) * (1 + n(q.markupPct) / 100);

  function totals(q, allLines, s) {
    s = s || {};
    const lines = allLines.filter(counts);
    const materialsCost = lines.reduce((a, l) => a + n(l.qty) * n(l.unitCost), 0);
    const materialsPrice = lines.reduce((a, l) => a + linePrice(q, l), 0);
    const hours = totalHours(q);
    const labor = hours * n(q.laborRate);
    const repairMaterials = lines.filter((l) => isRepairLine(q, l)).reduce((a, l) => a + linePrice(q, l), 0);
    const repairLabor = q.jobType === "REPAIR" ? labor : q.jobType === "BOTH" ? n(q.repairHours) * n(q.laborRate) : 0;
    const repairPart = repairMaterials + repairLabor;
    const installPart = materialsPrice + labor - repairPart;
    const salesTax = repairPart * n(q.taxRatePct) / 100;
    const total = materialsPrice + labor + salesTax;
    const fuel = n(s.mpg) > 0 ? n(q.miles) / n(s.mpg) * n(s.gasPrice) : 0;
    const supplies = materialsCost * n(s.suppliesPct) / 100;
    const profit = total - salesTax - materialsCost - fuel - supplies;
    const savings = (total - salesTax) * n(s.savingsPct) / 100;
    const taxSetAside = Math.max(profit, 0) * n(s.taxSetAsidePct) / 100;
    const takeHome = profit - savings - taxSetAside;
    return { materialsCost, materialsPrice, labor, hours, salesTax, total, fuel, supplies, profit, savings, taxSetAside, takeHome,
      perHour: hours > 0 ? takeHome / hours : 0, installPart, repairPart };
  }

  // A job and its signed change orders, as billed.
  function jobTotal(q, quotes, lines, s) {
    const related = quotes.filter((x) => x.id === q.id || (x.parentId === q.id && x.signedAt));
    return related.map((j) => totals(j, lines.filter((l) => l.quoteId === j.id), s)).reduce((a, t) => ({
      total: a.total + t.total, salesTax: a.salesTax + t.salesTax, profit: a.profit + t.profit }), { total: 0, salesTax: 0, profit: 0 });
  }

  const WORDS = { street: "st", road: "rd", avenue: "ave", place: "pl", drive: "dr", lane: "ln", court: "ct", boulevard: "blvd", route: "rt", highway: "hwy", north: "n", south: "s", east: "e", west: "w" };
  const houseKey = (a) => String(a || "").split(",")[0].toLowerCase().split(/[^a-z0-9]+/).filter(Boolean).map((w) => WORDS[w] || w).join("");
  const sameHouse = (a, c) => !!a && !!c && houseKey(a) === houseKey(c);
  const HOUSE_FIELDS = [
    ["amps", "Service size"], ["panel", "Panel brand / model"], ["panelWhere", "Panel location"], ["panelAge", "Panel age or year"],
    ["subpanels", "Subpanels"], ["service", "Service drop"], ["wiring", "Wiring"], ["grounding", "Grounding / bonding"],
    ["detectors", "Smoke / CO detectors"], ["generator", "Generator / inlet"], ["surge", "Surge protection"], ["built", "Year built"], ["notes", "Notes"],
  ];
  function houseInfo(c, address) {
    try { return (JSON.parse(c.houses || "{}") || {})[houseKey(address)] || {}; } catch (e) { return {}; }
  }
  // Customer.properties: "label\taddress" per line.
  function properties(c) {
    const out = [];
    if (c.address) out.push({ label: "Main", address: c.address });
    String(c.properties || "").split("\n").forEach((ln) => {
      if (!ln.trim()) return;
      const i = ln.indexOf("\t");
      out.push(i >= 0 ? { label: ln.slice(0, i), address: ln.slice(i + 1) } : { label: "", address: ln });
    });
    return out.filter((p) => p.address);
  }

  // Stock held by open jobs.
  function holds(quotes, lines) {
    const open = new Set(quotes.filter((q) => HOLDING.includes(q.status) && !q.optionOf && !q.canceledAt).map((q) => q.id));
    const m = {};
    lines.forEach((l) => { if (l.materialId && open.has(l.quoteId) && counts(l)) m[l.materialId] = (m[l.materialId] || 0) + n(l.qty); });
    return m;
  }

  function statusOf(q) {
    if (q.canceledAt) return "Canceled";
    if (q.paidAt) return "Paid";
    if (q.kind === "APPT") return q.confirmedAt ? "Confirmed" : q.sentAt ? "Sent" : "Not sent";
    if (q.status === "DONE") return q.invoiceSentAt ? "Invoiced" : "Done";
    if (q.signedAt) return "Signed";
    return { DRAFT: "Draft", SENT: "Sent", APPROVED: "Approved", DECLINED: "Declined" }[q.status] || q.status;
  }

  // Paid income by month (no sales tax), expenses and miles, like the phone's Money screen.
  function money(year, data) {
    const s = data.settings, months = [];
    for (let m = 0; m < 12; m++) months.push({ income: 0, tax: 0, expenses: 0, miles: 0, jobs: 0 });
    data.quotes.forEach((q) => {
      if (!q.paidAt || q.parentId || q.optionOf) return;
      const d = new Date(q.paidAt); if (d.getFullYear() !== year) return;
      const t = jobTotal(q, data.quotes, data.lines, s);
      const share = t.total > 0 ? n(q.paidAmount) / t.total : 1;
      const mo = months[d.getMonth()];
      mo.tax += t.salesTax * share; mo.income += n(q.paidAmount) - t.salesTax * share; mo.jobs++;
    });
    data.expenses.forEach((e) => { const d = new Date(e.date); if (d.getFullYear() === year) months[d.getMonth()].expenses += n(e.amount); });
    data.trips.forEach((t) => { const d = new Date(t.startedAt); if (d.getFullYear() === year) months[d.getMonth()].miles += n(t.miles); });
    const rate = (ms) => ms < Date.UTC(2026, 6, 1) ? 0.725 : 0.76;
    return months.map((m, i) => ({ ...m, mileage: m.miles * rate(Date.UTC(year, i, 15)), profit: m.income - m.expenses - m.miles * rate(Date.UTC(year, i, 15)) }));
  }

  root.ZL = { totals, jobTotal, linePrice, counts, houseKey, sameHouse, houseInfo, properties, HOUSE_FIELDS, holds, statusOf, money, b, n, HOLDING };
})(typeof window !== "undefined" ? window : globalThis);
