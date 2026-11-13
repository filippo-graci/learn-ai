// /movies: the phone's movie & series lists and reviews (Movies.kt), a JSON object. Pictures: movies/[image].js.
// GET returns it ("{}" if none), PUT replaces it. _middleware.js has already checked the app's key.
// Stored in the NOTES namespace under "_movies": book ids can't contain "_", so no clash (notes.py skips "_" keys).
const KEY = "_movies";
const MAX = 10_000_000;  // bytes; thousands of reviews. KV allows 25 MB.

export async function onRequest({ request, env }) {
  if (request.method === "GET") {
    const body = (await env.NOTES.get(KEY)) ?? "{}";
    return new Response(body, { headers: { "Content-Type": "application/json; charset=utf-8" } });
  }
  if (request.method === "PUT") {
    const body = await request.text();
    if (body.length > MAX) return new Response("too large", { status: 413 });
    let obj;
    try { obj = JSON.parse(body); } catch { return new Response("not JSON", { status: 400 }); }
    if (obj === null || typeof obj !== "object" || Array.isArray(obj)) return new Response("expected a JSON object", { status: 400 });
    await env.NOTES.put(KEY, body);
    return new Response("saved", { status: 200 });
  }
  return new Response("method not allowed", { status: 405, headers: { "Allow": "GET, PUT" } });
}
