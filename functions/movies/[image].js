// /movies/<image>: one review picture (JPEG, ≤ 600 px, made by the app), stored in KV under "_mimg_<image>".
// GET returns it (404 if missing), PUT stores it, DELETE removes it (the app deletes pictures it no longer uses).
// _middleware.js has already checked the app's key.
const MAX = 2_000_000;  // bytes; the app's pictures are ~50 KB
const NAME = /^[0-9a-f-]{36}-\d{13}\.jpg$/;  // <title uuid>-<epoch ms>.jpg, as Movies.setImage names them

export async function onRequest({ request, env, params }) {
  const name = params.image;
  if (!NAME.test(name)) return new Response("bad picture name", { status: 400 });
  const key = "_mimg_" + name;
  if (request.method === "GET") {
    const body = await env.NOTES.get(key, { type: "arrayBuffer" });
    if (body === null) return new Response("not found", { status: 404 });
    return new Response(body, { headers: { "Content-Type": "image/jpeg" } });
  }
  if (request.method === "PUT") {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX) return new Response("too large", { status: 413 });
    const b = new Uint8Array(body);
    if (b.length < 3 || b[0] !== 0xff || b[1] !== 0xd8 || b[2] !== 0xff) return new Response("not a JPEG", { status: 400 });
    await env.NOTES.put(key, body);
    return new Response("saved", { status: 200 });
  }
  if (request.method === "DELETE") {
    await env.NOTES.delete(key);
    return new Response("deleted", { status: 200 });
  }
  return new Response("method not allowed", { status: 405, headers: { "Allow": "GET, PUT, DELETE" } });
}
