// "Request a job" page backend. Public: GET -> business info for the page; POST {action:'request', ...} -> into the app's inbox.
// App only (x-app-key): POST {action:'profile', profile:{name, phone, area, owner}} keeps the page's business info current.
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "content-type, x-app-key", "Access-Control-Allow-Methods": "GET, POST, OPTIONS" };
const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
async function sha256(s: string) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
const str = (v: unknown, max: number) => String(v ?? "").trim().slice(0, max);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    if (req.method === "GET") {
      const { data } = await db.from("app_content").select("data").eq("key", "business").maybeSingle();
      const b = data?.data ?? {};
      return json({ name: b.name ?? "", phone: b.phone ?? "", area: b.area ?? "", owner: b.owner ?? "" });
    }
    if (req.method !== "POST") return json({ error: "method" }, 405);
    const raw = await req.text();
    if (raw.length > 2_500_000) return json({ error: "too_large" }, 413);
    const body = JSON.parse(raw);

    if (body.action === "request") {
      if (str(body.website, 10)) return json({ ok: true }); // bots fill hidden fields
      const name = str(body.name, 80), phone = str(body.phone, 30), address = str(body.address, 200), problem = str(body.problem, 1500);
      if (name.length < 2 || phone.replace(/\D/g, "").length < 10 || problem.length < 3) return json({ error: "missing" }, 400);
      // At most 5 requests an hour from one connection.
      const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
      const h = await sha256("zimdars:" + ip);
      const since = new Date(Date.now() - 3_600_000).toISOString();
      const { count } = await db.from("book_limits").select("*", { count: "exact", head: true }).eq("ip_hash", h).gte("at", since);
      if ((count ?? 0) >= 5) return json({ error: "slow_down" }, 429);
      await db.from("book_limits").insert({ ip_hash: h });
      const photos = (Array.isArray(body.photos) ? body.photos : [])
        .filter((p: unknown) => typeof p === "string" && p.startsWith("data:image/jpeg;base64,") && p.length < 700_000).slice(0, 3);
      const times = (Array.isArray(body.times) ? body.times : []).map((t: unknown) => str(t, 60)).filter(Boolean).slice(0, 6);
      const data = { name, phone, email: str(body.email, 120), address, problem, times, urgent: body.urgent === true, photos, received: new Date().toISOString() };
      await db.from("app_inbox").insert({ kind: "request", data });
      return json({ ok: true });
    }

    const k = req.headers.get("x-app-key");
    const { data: key } = k ? await db.from("app_keys").select("key_hash").eq("key_hash", await sha256(k)).maybeSingle() : { data: null };
    if (!key) return json({ error: "unauthorized" }, 401);
    if (body.action === "profile" && body.profile && typeof body.profile === "object") {
      const p = body.profile;
      const data = { name: str(p.name, 80), phone: str(p.phone, 30), area: str(p.area, 120), owner: str(p.owner, 40) };
      await db.from("app_content").upsert({ key: "business", data });
      return json({ ok: true });
    }
    return json({ error: "bad_request" }, 400);
  } catch (_e) {
    return json({ error: "server" }, 500);
  }
});
