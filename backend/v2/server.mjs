import { createServer } from "node:http";
import { getProviderCatalog } from "./provider-catalog.mjs";
import { verifyFirebaseIdToken } from "./firebase-auth.mjs";
import { validateSynthesisRequest } from "./validation.mjs";
import { createProviderAdapters } from "./providers.mjs";
import { synthesizeWithFallback } from "./router.mjs";

const MAX_BODY_BYTES = 16 * 1024;
const RATE_WINDOW_MS = 60_000;
const RATE_LIMIT = 20;
const userRequests = new Map();

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error("REQUEST_TOO_LARGE"), { status: 413 });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw Object.assign(new Error("INVALID_JSON"), { status: 400 }); }
}

async function authenticate(req, env) {
  const match = /^Bearer\\s+(.+)$/i.exec(req.headers.authorization || "");
  if (!match) throw Object.assign(new Error("AUTH_REQUIRED"), { status: 401 });
  try { return await verifyFirebaseIdToken(match[1], env.SAWT_FIREBASE_PROJECT_ID); }
  catch { throw Object.assign(new Error("AUTH_INVALID_OR_EXPIRED"), { status: 401 }); }
}

function enforceRateLimit(uid) {
  const now = Date.now();
  const current = userRequests.get(uid) || { start: now, count: 0 };
  if (now - current.start >= RATE_WINDOW_MS) {
    current.start = now;
    current.count = 0;
  }
  current.count += 1;
  userRequests.set(uid, current);
  if (userRequests.size > 10_000) {
    for (const [key, value] of userRequests) {
      if (now - value.start > RATE_WINDOW_MS * 2) userRequests.delete(key);
    }
  }
  if (current.count > RATE_LIMIT) throw Object.assign(new Error("RATE_LIMITED"), { status: 429 });
}

export function createSawtServer({ env = process.env, fetchImpl = fetch, adapters = null } = {}) {
  const providerAdapters = adapters || createProviderAdapters(env, fetchImpl);
  return createServer(async (req, res) => {
    const url = new URL(req.url || "/", "http://localhost");
    if (req.method === "GET" && url.pathname === "/v2/health") {
      return sendJson(res, 200, {
        ok: true,
        service: "SAWT AI Provider Router v2",
        authConfigured: Boolean(env.SAWT_FIREBASE_PROJECT_ID),
        freeOnly: true
      });
    }

    if (req.method === "GET" && url.pathname === "/v2/providers") {
      try {
        await authenticate(req, env);
        return sendJson(res, 200, {
          freeOnly: true,
          providers: getProviderCatalog(env).map(({ id, company, name, model, status, freeTierNote, dataConsentRequired }) => ({
            id, company, name, model, status, freeTierNote, dataConsentRequired
          }))
        });
      } catch (error) {
        return sendJson(res, error.status || 401, { error: error.message });
      }
    }

    if (req.method === "POST" && url.pathname === "/v2/synthesize") {
      try {
        const user = await authenticate(req, env);
        enforceRateLimit(user.uid);
        const body = await readJson(req);
        const validation = validateSynthesisRequest(body, env);
        if (!validation.ok) {
          const status = validation.code === "CLOUD_DATA_CONSENT_REQUIRED" ? 428 : 400;
          return sendJson(res, status, validation);
        }
        const result = await synthesizeWithFallback(validation.value, env, providerAdapters);
        res.writeHead(200, {
          "content-type": result.contentType,
          "content-length": result.audio.length,
          "cache-control": "no-store",
          "content-disposition": 'attachment; filename="sawt-' + Date.now() + "." + result.extension + '"',
          "x-sawt-provider": result.providerId,
          "x-sawt-fallback-count": String(result.fallbackCount),
          "x-content-type-options": "nosniff"
        });
        return res.end(result.audio);
      } catch (error) {
        const status = error.status || (error.code === "NO_FREE_PROVIDER_AVAILABLE" ? 503 : 502);
        return sendJson(res, status, {
          error: error.code || "SYNTHESIS_FAILED",
          message: error.message,
          retryable: status === 429 || status >= 500
        });
      }
    }

    return sendJson(res, 404, { error: "NOT_FOUND" });
  });
}

if (process.argv[1] && import.meta.url === new URL("file://" + process.argv[1]).href) {
  const port = Number(process.env.PORT || 8080);
  createSawtServer().listen(port, "0.0.0.0", () => {
    console.log("SAWT AI v2 API listening on " + port);
  });
}
