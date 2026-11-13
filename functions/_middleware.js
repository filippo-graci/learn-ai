// Cloudflare Pages middleware: runs before every request to the site, static files included.
// The summaries quote copyrighted books, so nothing is served without the app's key (header X-Learnai-Key).
// Fails closed: if the LEARNAI_KEY secret isn't set on the project, every request is refused.
// publish.py checks for MARKER to confirm an anonymous request really was refused by this guard.
const MARKER = "learn-ai: key required";

export async function onRequest({ request, env, next }) {
  const key = env.LEARNAI_KEY || "";
  const sent = request.headers.get("X-Learnai-Key") || "";
  if (!key || !(await same(sent, key))) {
    return new Response(MARKER, { status: 401, headers: { "Cache-Control": "no-store" } });
  }
  const res = await next();
  const out = new Response(res.body, res);  // copy: the asset response's headers are immutable
  out.headers.set("Cache-Control", "private, no-store");
  return out;
}

// Constant-time comparison: hash both, then compare the equal-length digests.
async function same(a, b) {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([a, b].map(s => crypto.subtle.digest("SHA-256", enc.encode(s))));
  return crypto.subtle.timingSafeEqual(x, y);
}
