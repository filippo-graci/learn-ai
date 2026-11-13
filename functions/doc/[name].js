// /doc/<name>: a JSON object the app syncs with syncDoc (Content.kt), one KV key per name ("_doc_<name>").
// For documents added after progress / movies (which keep their own endpoints): "reviews" = flashcard states (Review.kt).
// GET returns it ("{}" if none), PUT replaces it. _middleware.js has already checked the app's key.
const NAMES = new Set(["reviews"]);
const MAX = 10_000_000;  // bytes. KV allows 25 MB.

export async function onRequest({ request, env, params }) {
  if (!NAMES.has(params.name)) return new Response("unknown document", { status: 404 });
  const key = "_doc_" + params.name;
  if (request.method === "GET") {
    const body = (await env.NOTES.get(key)) ?? "{}";
    return new Response(body, { headers: { "Content-Type": "application/json; charset=utf-8" } });
  }
  if (request.method === "PUT") {
    const body = await request.text();
    if (body.length > MAX) return new Response("too large", { status: 413 });
    let obj;
    try { obj = JSON.parse(body); } catch { return new Response("not JSON", { status: 400 }); }
    if (obj === null || typeof obj !== "object" || Array.isArray(obj)) return new Response("expected a JSON object", { status: 400 });
    await env.NOTES.put(key, body);
    return new Response("saved", { status: 200 });
  }
  return new Response("method not allowed", { status: 405, headers: { "Allow": "GET, PUT" } });
}
