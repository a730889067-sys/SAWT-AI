import test from "node:test";
import assert from "node:assert/strict";
import { synthesizeWithFallback } from "../router.mjs";

test("auto route falls back only through configured free providers", async () => {
  const env = { GEMINI_API_KEY: "test-key", GEMINI_FREE_TIER_CONFIRMED: "true" };
  const calls = [];
  const adapters = {
    "gemini-quality": async () => { calls.push("gemini-quality"); const error = new Error("quota"); error.status = 429; error.retryable = true; throw error; },
    "gemini-fast": async () => { calls.push("gemini-fast"); return { audio: Buffer.from("wav"), contentType: "audio/wav", extension: "wav" }; },
    "azure-f0": async () => { calls.push("azure-f0"); throw new Error("must not run"); }
  };
  const result = await synthesizeWithFallback({ text: "اختبار", provider: "auto", voice: "male" }, env, adapters);
  assert.deepEqual(calls, ["gemini-quality", "gemini-fast"]);
  assert.equal(result.providerId, "gemini-fast");
  assert.equal(result.fallbackCount, 1);
});

test("manual selection never silently changes provider", async () => {
  const env = { GEMINI_API_KEY: "test-key", GEMINI_FREE_TIER_CONFIRMED: "true" };
  const calls = [];
  const adapters = {
    "gemini-quality": async () => { calls.push("quality"); const error = new Error("quota"); error.status = 429; error.retryable = true; throw error; },
    "gemini-fast": async () => { calls.push("fast"); return { audio: Buffer.from("x") }; }
  };
  await assert.rejects(
    () => synthesizeWithFallback({ text: "x", provider: "gemini-quality" }, env, adapters),
    /المحركات المجانية/
  );
  assert.deepEqual(calls, ["quality"]);
});

test("no configured provider returns a safe unavailable error", async () => {
  await assert.rejects(
    () => synthesizeWithFallback({ text: "x", provider: "auto" }, {}, {}),
    error => error.code === "NO_FREE_PROVIDER_AVAILABLE"
  );
});
