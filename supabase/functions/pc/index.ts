// Zimdars Electric for PC. The phone sends a snapshot of its data; the PC reads it and sends new jobs back through the inbox.
// Public: POST {action:'pair_finish', code} -> {key} (one-time code shown on the phone).
// App key (phone or linked PC): pair_start, put_snapshot, get_snapshot, photo_upload_url, photo_urls, send_job.
import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
const BUCKET = "backups";
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "content-type, x-app-key", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
async function sha256(s: string) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function random(n: number, alphabet: string) {
  const b = crypto.getRandomValues(new Uint8Array(n));
  return [...b].map((x) => alphabet[x % alphabet.length]).join("");
}
const photoName = (n: unknown) => typeof n === "string" && /^[A-Za-z0-9_-]{1,40}\.jpg$/.test(n) ? n : null;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    if (req.method !== "POST") return json({ error: "method" }, 405);
    const raw = await req.text();
    if (raw.length > 8_000_000) return json({ error: "too_large" }, 413);
    const body = JSON.parse(raw);

    if (body.action === "pair_finish") {
      const code = String(body.code ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
      if (code.length !== 8) return json({ error: "code" }, 400);
      const { data: p } = await db.from("pc_pair").select("key,expires_at").eq("code", code).maybeSingle();
      await db.from("pc_pair").delete().lt("expires_at", new Date().toISOString());
      if (!p || new Date(p.expires_at).getTime() < Date.now()) return json({ error: "code" }, 404);
      await db.from("pc_pair").delete().eq("code", code);
      return json({ key: p.key });
    }

    const k = req.headers.get("x-app-key");
    const { data: ok } = k && k.length >= 20 ? await db.from("app_keys").select("key_hash").eq("key_hash", await sha256(k)).maybeSingle() : { data: null };
    if (!ok) return json({ error: "unauthorized" }, 401);

    switch (body.action) {
      case "pair_start": {
        const key = random(40, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
        const code = random(8, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789");
        await db.from("app_keys").insert({ key_hash: await sha256(key), label: "pc " + new Date().toISOString().slice(0, 10) });
        await db.from("pc_pair").insert({ code, key, expires_at: new Date(Date.now() + 15 * 60_000).toISOString() });
        return json({ code });
      }
      case "put_snapshot": {
        if (typeof body.data !== "object" || !body.data) return json({ error: "bad_request" }, 400);
        await db.from("pc_snapshot").upsert({ id: 1, data: body.data, updated_at: new Date().toISOString() });
        return json({ ok: true });
      }
      case "get_snapshot": {
        const { data } = await db.from("pc_snapshot").select("data,updated_at").eq("id", 1).maybeSingle();
        return json({ data: data?.data ?? null, updated_at: data?.updated_at ?? null });
      }
      case "photo_upload_url": {
        const n = photoName(body.name);
        if (!n) return json({ error: "name" }, 400);
        const { data, error } = await db.storage.from(BUCKET).createSignedUploadUrl("pc/photos/" + n, { upsert: true });
        if (error) return json({ error: "storage" }, 500);
        return json({ url: data.signedUrl });
      }
      case "photo_urls": {
        const names = (Array.isArray(body.names) ? body.names : []).map(photoName).filter(Boolean).slice(0, 300) as string[];
        if (!names.length) return json({ urls: {} });
        const { data } = await db.storage.from(BUCKET).createSignedUrls(names.map((n) => "pc/photos/" + n), 3600);
        const urls: Record<string, string> = {};
        (data ?? []).forEach((d: any, i: number) => { if (d.signedUrl && !d.error) urls[names[i]] = d.signedUrl; });
        return json({ urls });
      }
      case "photo_list": {
        const { data } = await db.storage.from(BUCKET).list("pc/photos", { limit: 1000 });
        return json({ names: (data ?? []).map((f: any) => f.name) });
      }
      case "send_job": {
        if (typeof body.data !== "object" || !body.data || !body.data.quote) return json({ error: "bad_request" }, 400);
        const data = { ...body.data, from: "pc" };
        const { data: row } = await db.from("app_inbox").insert({ kind: "job", data }).select("id").single();
        return json({ ok: true, id: row?.id });
      }
    }
    return json({ error: "bad_request" }, 400);
  } catch (_e) {
    return json({ error: "server" }, 500);
  }
});
