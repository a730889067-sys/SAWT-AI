import test from "node:test";
import assert from "node:assert/strict";
import { validateSynthesisRequest, escapeXml } from "../validation.mjs";

test("accepts MSA text without corrupting Unicode", () => {
  const result = validateSynthesisRequest({ text: "إِنَّ الْعِلْمَ نُورٌ", provider: "auto", voice: "female", cloudDataConsent: true });
  assert.equal(result.ok, true);
  assert.equal(result.value.text, "إِنَّ الْعِلْمَ نُورٌ");
});

test("rejects empty and oversized text", () => {
  assert.equal(validateSynthesisRequest({ text: "  " }).code, "EMPTY_TEXT");
  assert.equal(validateSynthesisRequest({ text: "ا".repeat(3001) }).code, "TEXT_TOO_LONG");
});

test("Every cloud provider requires explicit data-use consent", () => {
  assert.equal(validateSynthesisRequest({ text: "مرحبا", provider: "gemini-quality" }).code, "CLOUD_DATA_CONSENT_REQUIRED");
  assert.equal(validateSynthesisRequest({ text: "مرحبا", provider: "easyvoice-msa" }).code, "CLOUD_DATA_CONSENT_REQUIRED");
  assert.equal(validateSynthesisRequest({ text: "مرحبا", provider: "gemini-quality", cloudDataConsent: true }).ok, true);
  assert.equal(validateSynthesisRequest({ text: "مرحبا", provider: "auto" }, { GEMINI_API_KEY: "configured" }).code, "CLOUD_DATA_CONSENT_REQUIRED");
  assert.equal(validateSynthesisRequest({ text: "مرحبا", provider: "auto", cloudDataConsent: true }, { GEMINI_API_KEY: "configured" }).ok, true);
  assert.equal(validateSynthesisRequest({ text: "مرحبا", provider: "auto", cloudDataConsent: true }).ok, true);
});

test("speech speed is constrained to a safe provider range", () => {
  assert.equal(validateSynthesisRequest({ text: "مرحبا", speed: 3, cloudDataConsent: true }).code, "INVALID_SPEED");
  assert.equal(validateSynthesisRequest({ text: "مرحبا", speed: 1.25, cloudDataConsent: true }).ok, true);
});

test("SSML XML escaping prevents markup injection", () => {
  assert.equal(escapeXml("<a x='1'>&"), "&lt;a x=&apos;1&apos;&gt;&amp;");
});
