// Spreadsheet formula engine for Zimdars Sheets: cell refs (A1, $B$2), ranges (A1:C9), other sheets ('Sheet 2'!A1),
// math + - * / ^ %, text &, comparisons, and the common Excel functions.
(function (root) {
  const ERR = (code) => ({ err: code });
  const isErr = (v) => v && typeof v === "object" && "err" in v;

  const colNum = (s) => s.toUpperCase().split("").reduce((a, c) => a * 26 + c.charCodeAt(0) - 64, 0) - 1;
  const colName = (n) => { let s = ""; n++; while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); } return s; };
  const addr = (r, c) => colName(c) + (r + 1);
  function parseAddr(a) { const m = /^\$?([A-Za-z]{1,3})\$?(\d{1,7})$/.exec(a); return m ? { r: Number(m[2]) - 1, c: colNum(m[1]) } : null; }

  // ---------- Tokenizer ----------
  function tokenize(src) {
    const t = []; let i = 0;
    while (i < src.length) {
      const ch = src[i];
      if (/\s/.test(ch)) { i++; continue; }
      if (/[0-9.]/.test(ch)) { const m = /^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/.exec(src.slice(i)); t.push({ k: "num", v: Number(m[0]) }); i += m[0].length; continue; }
      if (ch === '"') { let j = i + 1, s = ""; while (j < src.length) { if (src[j] === '"') { if (src[j + 1] === '"') { s += '"'; j += 2; continue; } break; } s += src[j++]; } if (j >= src.length) throw "string"; t.push({ k: "str", v: s }); i = j + 1; continue; }
      if (ch === "'") { const j = src.indexOf("'!", i + 1); if (j < 0) throw "sheet"; t.push({ k: "sheet", v: src.slice(i + 1, j) }); i = j + 2; continue; }
      const w = /^[A-Za-z_][A-Za-z0-9_.]*!?|^\$?[A-Za-z]{1,3}\$?\d+/.exec(src.slice(i));
      if (w) {
        let word = w[0];
        if (word.endsWith("!")) { t.push({ k: "sheet", v: word.slice(0, -1) }); i += word.length; continue; }
        const ref = /^\$?[A-Za-z]{1,3}\$?\d+/.exec(src.slice(i));
        if (ref && !/^[A-Za-z0-9_.(]/.test(src.slice(i + ref[0].length, i + ref[0].length + 1) || " ") ) { t.push({ k: "ref", v: ref[0].replace(/\$/g, "").toUpperCase() }); i += ref[0].length; continue; }
        if (ref && src[i + ref[0].length] === ":") { t.push({ k: "ref", v: ref[0].replace(/\$/g, "").toUpperCase() }); i += ref[0].length; continue; }
        const up = word.toUpperCase();
        if (up === "TRUE" || up === "FALSE") { t.push({ k: "bool", v: up === "TRUE" }); i += word.length; continue; }
        t.push({ k: "name", v: up }); i += word.length; continue;
      }
      const two = src.slice(i, i + 2);
      if (["<=", ">=", "<>"].includes(two)) { t.push({ k: "op", v: two }); i += 2; continue; }
      if ("+-*/^&=<>%(),:".includes(ch)) { t.push({ k: "op", v: ch }); i++; continue; }
      throw "char";
    }
    return t;
  }

  // ---------- Parser (precedence climbing) ----------
  function parse(src) {
    const t = tokenize(src); let p = 0;
    const peek = () => t[p], next = () => t[p++];
    const isOp = (v) => peek() && peek().k === "op" && peek().v === v;
    const PREC = { "=": 1, "<>": 1, "<": 1, ">": 1, "<=": 1, ">=": 1, "&": 2, "+": 3, "-": 3, "*": 4, "/": 4, "^": 5 };
    function expr(min) {
      let left = unary();
      for (;;) {
        const o = peek(); if (!o || o.k !== "op" || !(o.v in PREC) || PREC[o.v] < min) break;
        next(); const right = expr(PREC[o.v] + 1); // left to right, like Excel (2^3^2 = 64)
        left = { t: "bin", op: o.v, a: left, b: right };
      }
      return left;
    }
    function unary() {
      if (isOp("-")) { next(); return { t: "neg", a: unary() }; }
      if (isOp("+")) { next(); return unary(); }
      let n = primary();
      while (isOp("%")) { next(); n = { t: "pct", a: n }; }
      return n;
    }
    function refOrRange(sheet) {
      const a = next(); if (!a || a.k !== "ref") throw "ref";
      if (isOp(":")) { next(); const b = next(); if (!b || b.k !== "ref") throw "range"; return { t: "range", sheet, a: a.v, b: b.v }; }
      return { t: "ref", sheet, a: a.v };
    }
    function primary() {
      const k = peek(); if (!k) throw "end";
      if (k.k === "num") { next(); return { t: "lit", v: k.v }; }
      if (k.k === "str") { next(); return { t: "lit", v: k.v }; }
      if (k.k === "bool") { next(); return { t: "lit", v: k.v }; }
      if (k.k === "sheet") { next(); return refOrRange(k.v); }
      if (k.k === "ref") return refOrRange(null);
      if (k.k === "name") {
        next();
        if (!isOp("(")) throw "name";
        next(); const args = [];
        if (!isOp(")")) { for (;;) { args.push(expr(1)); if (isOp(",")) { next(); continue; } break; } }
        if (!isOp(")")) throw "paren"; next();
        return { t: "fn", name: k.v, args };
      }
      if (isOp("(")) { next(); const e = expr(1); if (!isOp(")")) throw "paren"; next(); return e; }
      throw "unexpected";
    }
    const tree = expr(1);
    if (p < t.length) throw "extra";
    return tree;
  }

  // ---------- Values ----------
  const num = (v) => {
    if (isErr(v)) return v;
    if (typeof v === "number") return v;
    if (typeof v === "boolean") return v ? 1 : 0;
    if (v === "" || v == null) return 0;
    const s = String(v).replace(/[$,\s]/g, "");
    if (/^-?\d*\.?\d+(e[+-]?\d+)?%$/i.test(s)) return Number(s.slice(0, -1)) / 100;
    const n = Number(s); return isNaN(n) ? ERR("#VALUE!") : n;
  };
  const str = (v) => v == null ? "" : typeof v === "boolean" ? (v ? "TRUE" : "FALSE") : typeof v === "number" ? fmtNum(v) : String(v);
  const truthy = (v) => typeof v === "string" ? (v.toUpperCase() === "TRUE" ? true : v === "" ? false : num(v) !== 0) : !!num(v);
  const fmtNum = (n) => Number.isInteger(n) ? String(n) : String(Math.round(n * 1e10) / 1e10);
  const flat = (args) => args.flatMap((a) => Array.isArray(a) ? a.flat() : [a]);
  const nums = (args) => { const out = []; for (const a of args) { if (Array.isArray(a)) { for (const v of a.flat()) { if (isErr(v)) return v; if (typeof v === "number") out.push(v); } } else { const n = num(a); if (isErr(n)) return n; out.push(n); } } return out; };
  function criteria(c) {
    const s = String(c), m = /^(<=|>=|<>|=|<|>)?(.*)$/.exec(s), op = m[1] || "=", rhs = m[2];
    const rn = rhs !== "" && !isNaN(Number(rhs)) ? Number(rhs) : null;
    const re = /[*?]/.test(rhs) ? new RegExp("^" + rhs.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".") + "$", "i") : null;
    return (v) => {
      if (typeof c === "number") return num(v) === c;
      const vn = typeof v === "number" ? v : (v !== "" && !isNaN(Number(v)) ? Number(v) : null);
      if (rn !== null && vn !== null) return op === "=" ? vn === rn : op === "<>" ? vn !== rn : op === "<" ? vn < rn : op === ">" ? vn > rn : op === "<=" ? vn <= rn : vn >= rn;
      const vs = str(v).toLowerCase(), rs = rhs.toLowerCase();
      if (op === "=") return re ? re.test(str(v)) : vs === rs;
      if (op === "<>") return re ? !re.test(str(v)) : vs !== rs;
      return op === "<" ? vs < rs : op === ">" ? vs > rs : op === "<=" ? vs <= rs : vs >= rs;
    };
  }
  const round = (n, d, mode) => { const f = Math.pow(10, d); const x = n * f; const r = mode === "up" ? (x < 0 ? Math.floor(x - 1e-9) : Math.ceil(x - 1e-9)) : mode === "down" ? Math.trunc(x + (x < 0 ? -1e-9 : 1e-9)) : Math.sign(x) * Math.round(Math.abs(x) + 1e-9); return r / f; };
  const serial = (d) => (Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) - Date.UTC(1899, 11, 30)) / 86400000;

  const FN = {
    SUM: (a) => { const n = nums(a); return isErr(n) ? n : n.reduce((x, y) => x + y, 0); },
    AVERAGE: (a) => { const n = nums(a); return isErr(n) ? n : n.length ? n.reduce((x, y) => x + y, 0) / n.length : ERR("#DIV/0!"); },
    MIN: (a) => { const n = nums(a); return isErr(n) ? n : n.length ? Math.min(...n) : 0; },
    MAX: (a) => { const n = nums(a); return isErr(n) ? n : n.length ? Math.max(...n) : 0; },
    COUNT: (a) => flat(a).filter((v) => typeof v === "number").length,
    COUNTA: (a) => flat(a).filter((v) => v !== "" && v != null).length,
    COUNTBLANK: (a) => flat(a).filter((v) => v === "" || v == null).length,
    PRODUCT: (a) => { const n = nums(a); return isErr(n) ? n : n.reduce((x, y) => x * y, 1); },
    ROUND: (a) => round(num(a[0]), num(a[1] ?? 0), "n"),
    ROUNDUP: (a) => round(num(a[0]), num(a[1] ?? 0), "up"),
    ROUNDDOWN: (a) => round(num(a[0]), num(a[1] ?? 0), "down"),
    INT: (a) => Math.floor(num(a[0])),
    ABS: (a) => Math.abs(num(a[0])),
    SQRT: (a) => num(a[0]) < 0 ? ERR("#NUM!") : Math.sqrt(num(a[0])),
    POWER: (a) => Math.pow(num(a[0]), num(a[1])),
    MOD: (a) => num(a[1]) === 0 ? ERR("#DIV/0!") : ((num(a[0]) % num(a[1])) + num(a[1])) % num(a[1]),
    PI: () => Math.PI,
    CEILING: (a) => { const s = num(a[1] ?? 1); return s === 0 ? 0 : Math.ceil(num(a[0]) / s - 1e-9) * s; },
    FLOOR: (a) => { const s = num(a[1] ?? 1); return s === 0 ? 0 : Math.floor(num(a[0]) / s + 1e-9) * s; },
    IF: (a) => truthy(a[0]) ? (a[1] ?? true) : (a[2] ?? false),
    IFERROR: (a) => isErr(a[0]) ? a[1] : a[0],
    AND: (a) => flat(a).every(truthy),
    OR: (a) => flat(a).some(truthy),
    NOT: (a) => !truthy(a[0]),
    CONCAT: (a) => flat(a).map(str).join(""),
    CONCATENATE: (a) => flat(a).map(str).join(""),
    TEXTJOIN: (a) => flat(a.slice(2)).filter((v) => !truthy(a[1]) || v !== "").map(str).join(str(a[0])),
    LEN: (a) => str(a[0]).length,
    UPPER: (a) => str(a[0]).toUpperCase(),
    LOWER: (a) => str(a[0]).toLowerCase(),
    PROPER: (a) => str(a[0]).toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase()),
    TRIM: (a) => str(a[0]).trim().replace(/\s+/g, " "),
    LEFT: (a) => str(a[0]).slice(0, num(a[1] ?? 1)),
    RIGHT: (a) => { const n = num(a[1] ?? 1); return n <= 0 ? "" : str(a[0]).slice(-n); },
    MID: (a) => str(a[0]).substr(num(a[1]) - 1, num(a[2])),
    TEXT: (a) => { const v = num(a[0]), f = str(a[1]); if (isErr(v)) return v; if (/%/.test(f)) return (v * 100).toFixed((f.split(".")[1] || "").replace(/[^0#]/g, "").length) + "%"; const dp = (f.split(".")[1] || "").replace(/[^0#]/g, "").length; const s = v.toLocaleString("en-US", { minimumFractionDigits: dp, maximumFractionDigits: dp, useGrouping: /,/.test(f) }); return (/\$/.test(f) ? "$" : "") + s; },
    VALUE: (a) => num(a[0]),
    TODAY: () => serial(new Date()),
    NOW: () => { const d = new Date(); return serial(d) + (d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()) / 86400; },
    DATE: (a) => serial(new Date(num(a[0]), num(a[1]) - 1, num(a[2]))),
    YEAR: (a) => new Date(Date.UTC(1899, 11, 30) + num(a[0]) * 86400000).getUTCFullYear(),
    MONTH: (a) => new Date(Date.UTC(1899, 11, 30) + num(a[0]) * 86400000).getUTCMonth() + 1,
    DAY: (a) => new Date(Date.UTC(1899, 11, 30) + num(a[0]) * 86400000).getUTCDate(),
    SUMIF: (a) => { const test = criteria(a[1]), r = (a[0] || []).flat(), s = a[2] ? a[2].flat() : r; let t = 0; r.forEach((v, i) => { if (test(v)) { const n = num(s[i]); if (typeof n === "number") t += n; } }); return t; },
    COUNTIF: (a) => { const test = criteria(a[1]); return (a[0] || []).flat().filter(test).length; },
    AVERAGEIF: (a) => { const test = criteria(a[1]), r = (a[0] || []).flat(), s = a[2] ? a[2].flat() : r; const v = []; r.forEach((x, i) => { if (test(x)) v.push(num(s[i])); }); return v.length ? v.reduce((x, y) => x + y, 0) / v.length : ERR("#DIV/0!"); },
    SUMIFS: (a) => { const s = a[0].flat(); let t = 0; s.forEach((v, i) => { for (let k = 1; k < a.length; k += 2) if (!criteria(a[k + 1])(a[k].flat()[i])) return; t += num(v) || 0; }); return t; },
    COUNTIFS: (a) => { const n = a[0].flat().length; let c = 0; for (let i = 0; i < n; i++) { let ok = true; for (let k = 0; k < a.length; k += 2) if (!criteria(a[k + 1])(a[k].flat()[i])) { ok = false; break; } if (ok) c++; } return c; },
    VLOOKUP: (a) => {
      const key = a[0], table = a[1] || [], col = num(a[2]) - 1, exact = a.length > 3 && !truthy(a[3]);
      if (col < 0 || !table.length || col >= table[0].length) return ERR("#REF!");
      if (exact) { const row = table.find((r) => (typeof key === "number" ? num(r[0]) === key : str(r[0]).toLowerCase() === str(key).toLowerCase())); return row ? row[col] : ERR("#N/A"); }
      let best = null; for (const r of table) { if (r[0] === "" ) continue; if (typeof key === "number" ? num(r[0]) <= key : str(r[0]).toLowerCase() <= str(key).toLowerCase()) best = r; else break; }
      return best ? best[col] : ERR("#N/A");
    },
    INDEX: (a) => { const t = a[0] || [], r = num(a[1]) - 1, c = num(a[2] ?? 1) - 1; return t[r] && t[r][c] !== undefined ? t[r][c] : ERR("#REF!"); },
    MATCH: (a) => { const list = (a[1] || []).flat(), k = a[0]; const i = list.findIndex((v) => typeof k === "number" ? num(v) === k : str(v).toLowerCase() === str(k).toLowerCase()); return i < 0 ? ERR("#N/A") : i + 1; },
    PMT: (a) => { const r = num(a[0]), n = num(a[1]), pv = num(a[2]); return r === 0 ? -pv / n : -(pv * r * Math.pow(1 + r, n)) / (Math.pow(1 + r, n) - 1); },
  };

  // ---------- Evaluation ----------
  // book: { get(sheetName|null, "A1") -> value (already computed) }
  function evaluate(node, ctx) {
    switch (node.t) {
      case "lit": return node.v;
      case "ref": return ctx.get(node.sheet, node.a);
      case "range": return ctx.range(node.sheet, node.a, node.b);
      case "neg": { const v = num(evaluate(node.a, ctx)); return isErr(v) ? v : -v; }
      case "pct": { const v = num(evaluate(node.a, ctx)); return isErr(v) ? v : v / 100; }
      case "fn": {
        const f = FN[node.name]; if (!f) return ERR("#NAME?");
        if (node.name === "IF") { const c = evaluate(node.args[0], ctx); if (isErr(c)) return c; return truthy(c) ? (node.args[1] ? evaluate(node.args[1], ctx) : true) : (node.args[2] ? evaluate(node.args[2], ctx) : false); }
        if (node.name === "IFERROR") { const v = evaluate(node.args[0], ctx); return isErr(v) ? evaluate(node.args[1], ctx) : v; }
        const args = node.args.map((x) => evaluate(x, ctx));
        if (!["IFERROR", "COUNT", "COUNTA", "COUNTBLANK", "ISERROR"].includes(node.name)) { for (const v of args) if (isErr(v)) return v; }
        try { const r = f(args); return typeof r === "number" && !isFinite(r) ? ERR("#NUM!") : r; } catch (e) { return ERR("#VALUE!"); }
      }
      case "bin": {
        const a = evaluate(node.a, ctx), b = evaluate(node.b, ctx);
        if (isErr(a)) return a; if (isErr(b)) return b;
        if (Array.isArray(a) || Array.isArray(b)) return ERR("#VALUE!");
        if (node.op === "&") return str(a) + str(b);
        if (["=", "<>", "<", ">", "<=", ">="].includes(node.op)) {
          let x = a, y = b;
          if (typeof x === "string" && typeof y === "string") { x = x.toLowerCase(); y = y.toLowerCase(); }
          else if (typeof x !== typeof y && x !== "" && y !== "") { const nx = num(x), ny = num(y); if (!isErr(nx) && !isErr(ny)) { x = nx; y = ny; } }
          if (x === "") x = typeof y === "number" ? 0 : ""; if (y === "") y = typeof x === "number" ? 0 : "";
          return node.op === "=" ? x === y : node.op === "<>" ? x !== y : node.op === "<" ? x < y : node.op === ">" ? x > y : node.op === "<=" ? x <= y : x >= y;
        }
        const x = num(a), y = num(b); if (isErr(x)) return x; if (isErr(y)) return y;
        if (node.op === "/" && y === 0) return ERR("#DIV/0!");
        return node.op === "+" ? x + y : node.op === "-" ? x - y : node.op === "*" ? x * y : node.op === "/" ? x / y : Math.pow(x, y);
      }
    }
    return ERR("#VALUE!");
  }

  // A cell's typed-in text -> literal value (numbers, $1,200, 15%, TRUE) or formula.
  function literal(raw) {
    if (raw == null || raw === "") return "";
    const s = String(raw);
    if (/^\s*-?\$?\s*[\d,]*\.?\d+\s*$/.test(s) && /\d/.test(s)) { const n = Number(s.replace(/[$,\s]/g, "")); if (!isNaN(n)) return n; }
    if (/^\s*-?[\d,]*\.?\d+\s*%\s*$/.test(s)) return Number(s.replace(/[%,\s]/g, "")) / 100;
    if (/^(true|false)$/i.test(s.trim())) return s.trim().toUpperCase() === "TRUE";
    return s;
  }

  // Computes every cell of every sheet. book = { sheets: [{ name, cells: { A1: "raw" } }] }.
  function computeBook(book) {
    const out = book.sheets.map(() => ({}));
    const byName = {}; book.sheets.forEach((s, i) => byName[s.name.toLowerCase()] = i);
    const state = book.sheets.map(() => ({}));
    const parsed = book.sheets.map(() => ({}));
    function cell(si, a) {
      if (si === undefined) return ERR("#REF!");
      if (state[si][a] === 2) return out[si][a];
      if (state[si][a] === 1) return ERR("#CIRC!");
      const raw = book.sheets[si].cells[a];
      if (raw == null || raw === "") return "";
      if (typeof raw !== "string" || raw[0] !== "=" || raw.length < 2) { const v = literal(raw); out[si][a] = v; state[si][a] = 2; return v; }
      state[si][a] = 1;
      let v;
      try {
        if (!(a in parsed[si])) parsed[si][a] = parse(raw.slice(1));
        v = evaluate(parsed[si][a], ctxFor(si));
      } catch (e) { v = ERR("#ERROR!"); }
      if (Array.isArray(v)) v = v[0] ? v[0][0] : "";
      out[si][a] = v; state[si][a] = 2; return v;
    }
    function ctxFor(si) {
      const which = (name) => name == null ? si : byName[String(name).toLowerCase()];
      return {
        get: (sheet, a) => cell(which(sheet), a),
        range: (sheet, a, b) => {
          const s = which(sheet); if (s === undefined) return ERR("#REF!");
          const p = parseAddr(a), q = parseAddr(b); if (!p || !q) return ERR("#REF!");
          const r0 = Math.min(p.r, q.r), r1 = Math.min(Math.max(p.r, q.r), r0 + 20000), c0 = Math.min(p.c, q.c), c1 = Math.max(p.c, q.c);
          const rows = [];
          for (let r = r0; r <= r1; r++) { const row = []; for (let c = c0; c <= c1; c++) row.push(cell(s, addr(r, c))); rows.push(row); }
          return rows;
        },
      };
    }
    book.sheets.forEach((s, si) => Object.keys(s.cells).forEach((a) => cell(si, a)));
    return out;
  }

  // Moves relative refs when a formula is copied (A1 -> B2 when pasted one down, one right). $ parts stay put.
  function shift(raw, dr, dc) {
    if (typeof raw !== "string" || raw[0] !== "=") return raw;
    let out = "", i = 0, inStr = false;
    while (i < raw.length) {
      const ch = raw[i];
      if (ch === '"') { inStr = !inStr; out += ch; i++; continue; }
      if (inStr) { out += ch; i++; continue; }
      const m = /^(\$?)([A-Za-z]{1,3})(\$?)(\d+)(?![\w(])/.exec(raw.slice(i));
      const prev = out[out.length - 1] || "";
      if (m && !/[A-Za-z0-9_.]/.test(prev)) {
        const c = m[1] ? colNum(m[2]) : colNum(m[2]) + dc, r = m[3] ? Number(m[4]) - 1 : Number(m[4]) - 1 + dr;
        out += (c < 0 || r < 0) ? "#REF!" : m[1] + colName(c) + m[3] + (r + 1); i += m[0].length; continue;
      }
      out += ch; i++;
    }
    return out;
  }

  root.ZF = { parse, computeBook, literal, shift, colName, colNum, addr, parseAddr, isErr, FN, str, num };
})(typeof window !== "undefined" ? window : globalThis);
