// /focus: the laptop focus guard's sessions (~/Workspace/focus/sessions.jsonl as a JSON list), for the app's Focus tab.
// PUT from focus.py after every session (whole list, last write wins), GET from the app. _middleware.js has
// already checked the key. Stored in the NOTES namespace under "_focus": book ids can't contain "_", so no clash.
const KEY = "_focus";
const MAX = 10_000_000;  // bytes; ~50k sessions. KV allows 25 MB.

export async function onRequest({ request, env }) {
  if (request.method === "GET") {
    const body = (await env.NOTES.get(KEY)) ?? "[]";
    return new Response(body, { headers: { "Content-Type": "application/json; charset=utf-8" } });
  }
  if (request.method === "PUT") {
    const body = await request.text();
    if (body.length > MAX) return new Response("too large", { status: 413 });
    let list;
    try { list = JSON.parse(body); } catch { return new Response("not JSON", { status: 400 }); }
    if (!Array.isArray(list)) return new Response("expected a JSON list", { status: 400 });
    await env.NOTES.put(KEY, body);
    return new Response("saved", { status: 200 });
  }
  return new Response("method not allowed", { status: 405, headers: { "Allow": "GET, PUT" } });
}
