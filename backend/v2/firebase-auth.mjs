import { createPublicKey, createVerify } from "node:crypto";

const JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
let jwksCache = null;
let jwksExpiresAt = 0;

function decodeJsonPart(part) {
  return JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
}

async function getJwks(fetchImpl = fetch) {
  if (jwksCache && Date.now() < jwksExpiresAt) return jwksCache;
  const response = await fetchImpl(JWKS_URL, { signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error("FIREBASE_CERTIFICATES_UNAVAILABLE");
  const data = await response.json();
  if (!Array.isArray(data.keys) || data.keys.length === 0) throw new Error("FIREBASE_CERTIFICATES_INVALID");
  const maxAge = Number(response.headers.get("cache-control")?.match(/max-age=(\d+)/i)?.[1] || 3600);
  jwksCache = data.keys;
  jwksExpiresAt = Date.now() + Math.min(Math.max(maxAge, 60), 6 * 60 * 60) * 1000;
  return jwksCache;
}

export async function verifyFirebaseIdToken(token, projectId, fetchImpl = fetch) {
  if (!projectId) throw new Error("FIREBASE_AUTH_NOT_CONFIGURED");
  if (typeof token !== "string" || token.length > 8192) throw new Error("INVALID_BEARER_TOKEN");
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("INVALID_BEARER_TOKEN");

  const header = decodeJsonPart(parts[0]);
  const claims = decodeJsonPart(parts[1]);
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new Error("INVALID_TOKEN_ALGORITHM");

  const keys = await getJwks(fetchImpl);
  const jwk = keys.find(key => key.kid === header.kid && key.kty === "RSA");
  if (!jwk) throw new Error("UNKNOWN_TOKEN_KEY");
  const publicKey = createPublicKey({ key: jwk, format: "jwk" });
  const verifier = createVerify("RSA-SHA256");
  verifier.update(parts[0] + "." + parts[1]);
  verifier.end();
  if (!verifier.verify(publicKey, Buffer.from(parts[2], "base64url"))) throw new Error("INVALID_TOKEN_SIGNATURE");

  const now = Math.floor(Date.now() / 1000);
  if (claims.aud !== projectId) throw new Error("INVALID_TOKEN_AUDIENCE");
  if (claims.iss !== "https://securetoken.google.com/" + projectId) throw new Error("INVALID_TOKEN_ISSUER");
  if (typeof claims.sub !== "string" || claims.sub.length < 1 || claims.sub.length > 128) throw new Error("INVALID_TOKEN_SUBJECT");
  if (!Number.isInteger(claims.exp) || claims.exp <= now) throw new Error("TOKEN_EXPIRED");
  if (!Number.isInteger(claims.iat) || claims.iat > now + 60) throw new Error("INVALID_TOKEN_ISSUED_AT");
  if (typeof claims.auth_time !== "number" || claims.auth_time > now + 60) throw new Error("INVALID_AUTH_TIME");
  return { uid: claims.sub, email: typeof claims.email === "string" ? claims.email : null };
}
