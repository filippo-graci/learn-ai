// /notes/<bookId>: the phone's highlights & notes for one book, a JSON list, stored in KV (binding NOTES).
// GET returns it ("[]" if none), PUT replaces it. _middleware.js has already checked the app's key.
// ponytail: whole list per book, last write wins: fine for one phone; per-highlight merge if a second device appears.
const MAX = 2_000_000;  // bytes; ~thousands of highlights. KV allows 25 MB.

export async function onRequest({ request, env, params }) {
  const book = params.book;
  if (!/^[a-z0-9-]{1,100}$/.test(book)) return new Response("bad book id", { status: 400 });
  if (request.method === "GET") {
    const body = (await env.NOTES.get(book)) ?? "[]";
    return new Response(body, { headers: { "Content-Type": "application/json; charset=utf-8" } });
  }
  if (request.method === "PUT") {
    const body = await request.text();
    if (body.length > MAX) return new Response("too large", { status: 413 });
    let list;
    try { list = JSON.parse(body); } catch { return new Response("not JSON", { status: 400 }); }
    if (!Array.isArray(list)) return new Response("expected a JSON list", { status: 400 });
    await env.NOTES.put(book, body);
    return new Response("saved", { status: 200 });
  }
  return new Response("method not allowed", { status: 405, headers: { "Allow": "GET, PUT" } });
}
