/**
 * Zephyr Bridge — an always-on, OpenAI-compatible endpoint.
 *
 * WHY THIS EXISTS
 * The ntfy pairing in Zephyr solves exactly one problem: a phone cannot be
 * dialled into. A deployed bridge HAS a public address, so that problem is
 * gone — and with it the need for the whole relay dance. Zephyr talks to this
 * URL the same way it talks to OpenAI, with no special protocol at all.
 *
 * WHAT IT DOES
 *   Zephyr  ──POST /v1/chat/completions──►  this function  ──►  upstream model
 *                                                          ◄──  answer (streamed)
 *
 * The bridge holds the API key, so the key never lives on the phone, and every
 * device you own points at one place you can rotate in seconds.
 *
 * IMPORTANT ABOUT SERVERLESS
 * This is request/response on purpose. A Vercel function is woken per request
 * and killed when it returns — it CANNOT sit in a loop polling ntfy. Any bridge
 * written as "subscribe and wait" will look fine in the logs and then silently
 * stop. Keep bridges shaped like this one.
 *
 * DEPLOY
 *   vercel env add BRIDGE_TOKEN     # a secret you invent; Zephyr sends it as the API key
 *   vercel env add UPSTREAM_URL     # e.g. https://open.bigmodel.cn/api/paas/v4/chat/completions
 *   vercel env add UPSTREAM_KEY     # the provider key
 *   vercel env add UPSTREAM_MODEL   # optional: force a model regardless of what was asked
 *   vercel deploy --prod
 *
 * THEN IN ZEPHYR
 *   Настройки → ИИ Помощник → Добавить модель
 *     Endpoint: https://<your-app>.vercel.app/v1/chat/completions
 *     Model:    whatever the upstream expects (or anything, if UPSTREAM_MODEL is set)
 *     Key:      the BRIDGE_TOKEN you invented
 */

export const config = {
  // streaming answers need the edge runtime; node would buffer them
  runtime: 'edge',
};

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });

export default async function handler(req) {
  if (req.method === 'GET') {
    // a browser hitting the URL should see that the bridge is alive
    return json(200, { ok: true, service: 'zephyr-bridge', ready: Boolean(process.env.UPSTREAM_URL) });
  }
  if (req.method !== 'POST') return json(405, { error: { message: 'POST only' } });

  const expected = process.env.BRIDGE_TOKEN;
  if (expected) {
    const auth = req.headers.get('authorization') || '';
    const token = auth.replace(/^Bearer\s+/i, '').trim();
    // without this anyone who learns the URL spends your quota
    if (token !== expected) {
      return json(401, { error: { message: 'bad bridge token' } });
    }
  }

  const upstreamUrl = process.env.UPSTREAM_URL;
  const upstreamKey = process.env.UPSTREAM_KEY;
  if (!upstreamUrl) {
    return json(500, { error: { message: 'UPSTREAM_URL is not configured on the bridge' } });
  }

  let body;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: { message: 'body is not JSON' } });
  }

  // let the bridge pin a model, so the phone never has to know the exact name
  if (process.env.UPSTREAM_MODEL) body.model = process.env.UPSTREAM_MODEL;

  const wantsStream = body.stream === true;

  let upstream;
  try {
    upstream = await fetch(upstreamUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(upstreamKey ? { Authorization: `Bearer ${upstreamKey}` } : {}),
      },
      body: JSON.stringify(body),
    });
  } catch (e) {
    return json(502, { error: { message: `upstream unreachable: ${e?.message || e}` } });
  }

  if (!upstream.ok) {
    const text = await upstream.text().catch(() => '');
    // pass the real status through — Zephyr turns 401/429/404 into useful advice
    return new Response(text || JSON.stringify({ error: { message: 'upstream error' } }), {
      status: upstream.status,
      headers: { 'Content-Type': upstream.headers.get('content-type') || 'application/json' },
    });
  }

  if (wantsStream && upstream.body) {
    // straight passthrough: Zephyr parses the SSE frames itself
    return new Response(upstream.body, {
      status: 200,
      headers: {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
      },
    });
  }

  const text = await upstream.text();
  return new Response(text, {
    status: 200,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}
