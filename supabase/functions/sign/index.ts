// Zimdars Electric customer pages: quote signing (+ ST-124, add-ons), appointment confirmations, ST-124 only, invoices, customer pages.
// Public: GET ?t=TOKEN, GET ?t=TOKEN&ics=1, POST {action:'sign'} (quote / st124), POST {action:'confirm'|'reschedule'} (appointment).
// App only (x-app-key): POST {action:'create'|'status'|'cancel'|'paid'|'retime'|'update'}.
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
const BASE = "https://jeffzimdars1-web.github.io/ZimdarsElectric-Android/";
const TZ = "America/New_York";
const AGREE = "I have read this quote and agree to the work, price and terms above. I agree to sign electronically, and my electronic signature is as binding as signing on paper.";
const AGREE_ST124 = "I sign New York State Form ST-124 (Certificate of Capital Improvement) for the work described, and certify the statements on that form. I agree to sign electronically, and my electronic signature is as binding as signing on paper.";
const KINDS = ["quote", "appt", "st124", "invoice", "portal"];
const PAGE: Record<string, string> = { quote: "sign/", st124: "sign/", appt: "appt/", invoice: "invoice/", portal: "portal/" };

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, x-app-key",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });

async function sha256(s: string) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function newToken() {
  const b = crypto.getRandomValues(new Uint8Array(24));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
async function isApp(req: Request) {
  const k = req.headers.get("x-app-key");
  if (!k || k.length < 20) return false;
  const { data } = await db.from("app_keys").select("key_hash").eq("key_hash", await sha256(k)).maybeSingle();
  return !!data;
}
const validToken = (t: unknown): t is string => typeof t === "string" && /^[A-Za-z0-9_-]{20,64}$/.test(t);

// ---- Calendar file ----
const utc = (ms: number) => new Date(ms).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
const localDay = (ms: number) => new Date(ms).toLocaleDateString("en-CA", { timeZone: TZ }).replace(/-/g, "");
const esc = (s: unknown) => String(s ?? "").replace(/\\/g, "\\\\").replace(/,/g, "\\,").replace(/;/g, "\\;").replace(/\r?\n/g, "\\n");
function ics(token: string, p: any, status: string): string {
  const a = p?.appt ?? {}, b = p?.business ?? {};
  const start = Number(a.start);
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Zimdars Electric//Appointment//EN", "METHOD:PUBLISH", "BEGIN:VEVENT",
    `UID:${token}@zimdarselectric`, `DTSTAMP:${utc(Date.now())}`];
  if (a.hasTime) lines.push(`DTSTART:${utc(start)}`, `DTEND:${utc(start + 3600000)}`);
  else lines.push(`DTSTART;VALUE=DATE:${localDay(start)}`, `DTEND;VALUE=DATE:${localDay(start + 86400000)}`);
  lines.push(`SUMMARY:${esc((b.name || "Electrician") + " appointment")}`, `LOCATION:${esc(a.site)}`,
    `DESCRIPTION:${esc([a.reason ? "Reason: " + a.reason : "", b.phone ? "Questions or changes: " + b.phone : ""].filter(Boolean).join("\n"))}`,
    `STATUS:${status === "canceled" ? "CANCELLED" : "CONFIRMED"}`,
    "BEGIN:VALARM", "TRIGGER:-PT1H", "ACTION:DISPLAY", "DESCRIPTION:Electrician appointment", "END:VALARM", "END:VEVENT", "END:VCALENDAR");
  return lines.join("\r\n") + "\r\n";
}

function cleanSt124(x: any) {
  if (!x || typeof x !== "object") return null;
  const role = x.role === "tenant" ? "tenant" : x.role === "owner" ? "owner" : null;
  const contract = x.contract === "includes" ? "includes" : x.contract === "not" ? "not" : null;
  if (!role || !contract) return null;
  return { role, contract, agreed_text: AGREE_ST124, signed_at: new Date().toISOString() };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    if (req.method === "GET") {
      const params = new URL(req.url).searchParams;
      const t = params.get("t");
      if (!validToken(t)) return json({ error: "not_found" }, 404);
      const { data: r } = await db.from("sign_requests").select("*").eq("token", t).maybeSingle();
      if (!r) return json({ error: "not_found" }, 404);
      if (params.get("ics") === "1") {
        if (r.kind !== "appt" || !r.payload?.appt?.start) return json({ error: "not_found" }, 404);
        return new Response(ics(t, r.payload, r.status), { headers: { ...cors, "Content-Type": "text/calendar; charset=utf-8",
          "Content-Disposition": "inline; filename=appointment.ics", "Cache-Control": "no-store" } });
      }
      if (!r.viewed_at && r.status === "sent") await db.from("sign_requests").update({ viewed_at: new Date().toISOString() }).eq("id", r.id);
      return json({
        kind: r.kind, status: r.status, quote_number: r.quote_number, payload: r.payload,
        agree_text: r.kind === "st124" ? AGREE_ST124 : AGREE, agree_st124: AGREE_ST124,
        signed_at: r.signed_at, signer_name: r.signer_name, signature_png: r.status === "signed" ? r.signature_png : null,
        confirmed_at: r.confirmed_at, st124: r.st124, reschedule: r.reschedule, addons: r.addons, choice: r.choice,
      });
    }
    if (req.method !== "POST") return json({ error: "method" }, 405);
    const raw = await req.text();
    if (raw.length > 600_000) return json({ error: "too_large" }, 413);
    const body = JSON.parse(raw);

    if (body.action === "confirm") {
      if (!validToken(body.t)) return json({ error: "not_found" }, 404);
      const { data: r } = await db.from("sign_requests").select("id,status,kind").eq("token", body.t).maybeSingle();
      if (!r || r.kind !== "appt") return json({ error: "not_found" }, 404);
      if (r.status === "confirmed") return json({ ok: true, already: true });
      if (r.status !== "sent") return json({ error: "canceled" }, 409);
      const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || null;
      const now = new Date().toISOString();
      await db.from("sign_requests").update({ status: "confirmed", confirmed_at: now, signer_ip: ip, signer_agent: (req.headers.get("user-agent") ?? "").slice(0, 300) }).eq("id", r.id).eq("status", "sent");
      return json({ ok: true, confirmed_at: now });
    }

    if (body.action === "reschedule") {
      if (!validToken(body.t)) return json({ error: "not_found" }, 404);
      const { data: r } = await db.from("sign_requests").select("id,status,kind").eq("token", body.t).maybeSingle();
      if (!r || r.kind !== "appt") return json({ error: "not_found" }, 404);
      if (r.status !== "sent" && r.status !== "confirmed") return json({ error: "canceled" }, 409);
      const date = String(body.date ?? "");
      const time = String(body.time ?? "");
      const part = ["morning", "afternoon", "time", "any"].includes(body.part) ? body.part : "any";
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "date" }, 400);
      if (part === "time" && !/^\d{2}:\d{2}$/.test(time)) return json({ error: "time" }, 400);
      const rs = { date, time: part === "time" ? time : null, part, note: String(body.note ?? "").slice(0, 300), requested_at: new Date().toISOString() };
      await db.from("sign_requests").update({ reschedule: rs }).eq("id", r.id).in("status", ["sent", "confirmed"]);
      return json({ ok: true, reschedule: rs });
    }

    if (body.action === "sign") {
      if (!validToken(body.t)) return json({ error: "not_found" }, 404);
      const name = String(body.name ?? "").trim();
      const sig = String(body.signature ?? "");
      if (body.agree !== true) return json({ error: "must_agree" }, 400);
      if (name.length < 2 || name.length > 100) return json({ error: "name" }, 400);
      if (!sig.startsWith("data:image/png;base64,") || sig.length > 400_000 || sig.length < 200) return json({ error: "signature" }, 400);
      const { data: r } = await db.from("sign_requests").select("id,status,kind,payload").eq("token", body.t).maybeSingle();
      if (!r || (r.kind !== "quote" && r.kind !== "st124")) return json({ error: "not_found" }, 404);
      if (r.status !== "sent") return json({ error: r.status === "signed" ? "already_signed" : "canceled" }, 409);
      const st = cleanSt124(body.st124);
      if (r.kind === "st124" && !st) return json({ error: "st124" }, 400);
      const options = (r.payload?.options ?? []) as any[];
      let choice: string | null = null;
      if (options.length) {
        choice = String(body.choice ?? "");
        if (!options.some((o) => String(o.id) === choice)) return json({ error: "choice" }, 400);
      }
      const needsSt = options.length ? !!options.find((o) => String(o.id) === choice)?.st124 : !!r.payload?.st124;
      if (r.kind === "quote" && needsSt && !st) return json({ error: "st124" }, 400);
      const offered = new Set(((r.payload?.addons ?? []) as any[]).map((a) => a.id));
      const addons = (Array.isArray(body.addons) ? body.addons : []).filter((n: unknown) => Number.isInteger(n) && offered.has(n)).slice(0, 50);
      const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || null;
      const { data: up } = await db.from("sign_requests").update({
        status: "signed", signed_at: new Date().toISOString(), signer_name: name, signature_png: sig,
        signer_ip: ip, signer_agent: (req.headers.get("user-agent") ?? "").slice(0, 300), agreed_text: r.kind === "st124" ? AGREE_ST124 : AGREE,
        st124: st, addons, choice,
      }).eq("id", r.id).eq("status", "sent").select("signed_at").maybeSingle();
      if (!up) return json({ error: "already_signed" }, 409);
      return json({ ok: true, signed_at: up.signed_at });
    }

    if (!(await isApp(req))) return json({ error: "unauthorized" }, 401);

    if (body.action === "create") {
      if (typeof body.quote_number !== "string" || typeof body.payload !== "object" || !body.payload) return json({ error: "bad_request" }, 400);
      const kind = KINDS.includes(body.kind) ? body.kind : "quote";
      if (validToken(body.replace)) await db.from("sign_requests").update({ status: "canceled" }).eq("token", body.replace).in("status", ["sent", "confirmed"]);
      const token = newToken();
      const { error } = await db.from("sign_requests").insert({ token, kind, quote_number: body.quote_number.slice(0, 40), payload: body.payload });
      if (error) return json({ error: "db" }, 500);
      return json({ token, url: BASE + PAGE[kind] + "?t=" + token });
    }
    if (body.action === "update") {
      // Refresh what a page shows (customer pages), keeping the same link.
      if (!validToken(body.t) || typeof body.payload !== "object" || !body.payload) return json({ error: "bad_request" }, 400);
      const { data: r } = await db.from("sign_requests").select("id,kind").eq("token", body.t).maybeSingle();
      if (!r) return json({ error: "not_found" }, 404);
      await db.from("sign_requests").update({ payload: body.payload }).eq("id", r.id);
      return json({ ok: true, url: BASE + PAGE[r.kind] + "?t=" + body.t });
    }
    if (body.action === "status") {
      const tokens = (Array.isArray(body.tokens) ? body.tokens : []).filter(validToken).slice(0, 100);
      if (tokens.length === 0) return json({ items: [] });
      const { data } = await db.from("sign_requests")
        .select("token,kind,status,viewed_at,signed_at,confirmed_at,signer_name,signer_ip,signer_agent,agreed_text,signature_png,st124,reschedule,addons,choice").in("token", tokens);
      return json({ items: data ?? [] });
    }
    if (body.action === "retime") {
      if (!validToken(body.t) || typeof body.start !== "number") return json({ error: "bad_request" }, 400);
      const { data: r } = await db.from("sign_requests").select("id,kind,payload").eq("token", body.t).maybeSingle();
      if (!r || r.kind !== "appt") return json({ error: "not_found" }, 404);
      const p = r.payload ?? {};
      p.appt = { ...(p.appt ?? {}), start: body.start, hasTime: body.hasTime !== false };
      await db.from("sign_requests").update({ payload: p, reschedule: null }).eq("id", r.id);
      return json({ ok: true });
    }
    if (body.action === "cancel") {
      if (!validToken(body.t)) return json({ error: "bad_request" }, 400);
      const upd: Record<string, unknown> = { status: "canceled" };
      if (body.notify === true) {
        const { data: r } = await db.from("sign_requests").select("payload").eq("token", body.t).maybeSingle();
        if (r) upd.payload = { ...(r.payload ?? {}), canceled: { at: new Date().toISOString(), reason: String(body.reason ?? "").slice(0, 200) } };
      }
      await db.from("sign_requests").update(upd).eq("token", body.t).in("status", ["sent", "confirmed"]);
      return json({ ok: true });
    }
    if (body.action === "paid") {
      if (!validToken(body.t)) return json({ error: "bad_request" }, 400);
      await db.from("sign_requests").update({ status: "paid", confirmed_at: new Date().toISOString() }).eq("token", body.t).eq("kind", "invoice");
      return json({ ok: true });
    }
    return json({ error: "bad_request" }, 400);
  } catch (_e) {
    return json({ error: "server" }, 500);
  }
});
