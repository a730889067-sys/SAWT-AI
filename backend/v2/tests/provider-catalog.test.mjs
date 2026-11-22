import test from "node:test";
import assert from "node:assert/strict";
import { getProviderCatalog, getEligibleProviderIds } from "../provider-catalog.mjs";

test("Gemini engines need a server key", () => {
  const catalog = getProviderCatalog({});
  assert.equal(catalog.find(x => x.id === "gemini-quality").status, "needs_setup");
  assert.equal(catalog.find(x => x.id === "gemini-fast").status, "needs_setup");
});

test("Azure is enabled only for confirmed F0, never S0", () => {
  const env = {
    AZURE_SPEECH_KEY: "secret",
    AZURE_SPEECH_REGION: "uaenorth",
    AZURE_SPEECH_TIER: "S0",
    AZURE_SPEECH_FREE_TIER_CONFIRMED: "true"
  };
  assert.equal(getProviderCatalog(env).find(x => x.id === "azure-f0").status, "needs_setup");
  env.AZURE_SPEECH_TIER = "F0";
  assert.equal(getProviderCatalog(env).find(x => x.id === "azure-f0").status, "available");
});

test("ElevenLabs free plan is blocked for public distribution", () => {
  const env = {
    ELEVENLABS_API_KEY: "secret",
    ELEVENLABS_VOICE_ID: "known-id",
    ELEVENLABS_FREE_PERSONAL_CONSENT: "true",
    SAWT_DISTRIBUTION_SCOPE: "public"
  };
  assert.equal(getProviderCatalog(env).find(x => x.id === "elevenlabs-personal").status, "blocked_for_public_app");
  assert.equal(getEligibleProviderIds(env).includes("elevenlabs-personal"), false);
});

test("ElevenLabs free plan is personal-only and requires explicit consent", () => {
  const env = {
    ELEVENLABS_API_KEY: "secret",
    ELEVENLABS_VOICE_ID: "known-id",
    ELEVENLABS_FREE_PERSONAL_CONSENT: "true",
    SAWT_DISTRIBUTION_SCOPE: "personal"
  };
  assert.equal(getProviderCatalog(env).find(x => x.id === "elevenlabs-personal").status, "available_personal_only");
});
