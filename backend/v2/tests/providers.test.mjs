import test from "node:test";
import assert from "node:assert/strict";
import { createProviderAdapters } from "../providers.mjs";

test("EasyVoice sends the correct Arabic male/female voice IDs and speed", async () => {
  const captured = [];
  const audio = Buffer.alloc(300);
  audio.write("ID3", 0, "ascii");
  const fakeFetch = async (url, options) => {
    captured.push({ url, options, body: JSON.parse(options.body) });
    return new Response(audio, { status: 200, headers: { "content-type": "audio/mpeg" } });
  };
  const adapters = createProviderAdapters({ EASYVOICE_API_KEY: "private-key" }, fakeFetch);
  await adapters["easyvoice-msa"]({ text: "نص عربي", voice: "male", speed: 1.2 });
  await adapters["easyvoice-msa"]({ text: "نص عربي", voice: "female", speed: 0.9 });
  assert.equal(captured.length, 2);
  assert.equal(captured[0].url, "https://easyvoice.ae/api/v1/audio/speech");
  assert.equal(captured[0].options.headers.Authorization, "Bearer private-key");
  assert.equal(captured[0].body.voice, "ar_m1");
  assert.equal(captured[1].body.voice, "ar_f1");
  assert.equal(captured[0].body.speed, 1.2);
  assert.equal(captured[1].body.speed, 0.9);
});

test("EasyVoice rejects missing API keys without making network calls", async () => {
  let calls = 0;
  const adapters = createProviderAdapters({}, async () => { calls += 1; });
  await assert.rejects(() => adapters["easyvoice-msa"]({ text: "مرحبا", voice: "female" }));
  assert.equal(calls, 0);
});

test("Gemini 3.8 TTS uses the documented audio request and keeps API keys out of URLs", async () => {
  const captured = [];
  const wav = Buffer.alloc(640);
  wav.write("RIFF", 0, "ascii");
  wav.write("WAVE", 8, "ascii");
  const fakeFetch = async (url, options) => {
    captured.push({ url, options, body: JSON.parse(options.body) });
    return new Response(JSON.stringify({
      candidates: [{
        content: {
          parts: [{ inlineData: { mimeType: "audio/wav", data: wav.toString("base64") } }]
        }
      }]
    }), { status: 200, headers: { "content-type": "application/json" } });
  };
  const adapters = createProviderAdapters({
    GEMINI_API_KEY: "secret-google-key",
    GEMINI_FREE_TIER_CONFIRMED: "true",
    GEMINI_MODEL_QUALITY: "gemini-3.8-flash-tts",
    GEMINI_MODEL_FAST: "gemini-3.8-flash-lite-tts"
  }, fakeFetch);

  const result = await adapters["gemini-quality"]({
    text: "السَّلَامُ عَلَيْكُمْ",
    voice: "female",
    speed: 1.15
  });

  assert.equal(captured.length, 1);
  assert.equal(captured[0].url,
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:generateContent");
  assert.equal(captured[0].url.includes("secret-google-key"), false);
  assert.equal(captured[0].options.headers["x-goog-api-key"], "secret-google-key");
  assert.deepEqual(captured[0].body.generationConfig.responseModalities, ["AUDIO"]);
  assert.equal(
    captured[0].body.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName,
    "Kore"
  );
  assert.match(captured[0].body.contents[0].parts[0].speechMetadata.style, /Modern Standard Arabic/);
  assert.match(captured[0].body.contents[0].parts[0].speechMetadata.style, /1.15/);
  assert.equal(result.contentType, "audio/wav");
  assert.equal(result.extension, "wav");
  assert.equal(result.audio.length, 640);
});
