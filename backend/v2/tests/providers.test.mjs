import test from "node:test";
import assert from "node:assert/strict";
import { createProviderAdapters } from "../providers.mjs";

test("EasyVoice sends the documented free Arabic male/female voice IDs and speed", async () => {
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
  assert.equal(captured[0].body.model, "kokoro-82m");
  assert.equal(captured[0].body.voice, "ar_m1");
  assert.equal(captured[1].body.voice, "ar_f1");
  assert.equal(captured[0].body.speed, 1.2);
  assert.equal(captured[1].body.speed, 0.9);
  assert.equal(captured[0].body.response_format, "mp3");
});

test("EasyVoice rejects missing API keys without making network calls", async () => {
  let calls = 0;
  const adapters = createProviderAdapters({}, async () => { calls += 1; });
  await assert.rejects(() => adapters["easyvoice-msa"]({ text: "مرحبا", voice: "female" }));
  assert.equal(calls, 0);
});

test("Gemini 3.8 TTS uses the documented Interactions API and extracts WAV from model_output steps", async () => {
  const captured = [];
  const wav = Buffer.alloc(640);
  wav.write("RIFF", 0, "ascii");
  wav.write("WAVE", 8, "ascii");
  const fakeFetch = async (url, options) => {
    captured.push({ url, options, body: JSON.parse(options.body) });
    return new Response(JSON.stringify({
      steps: [
        { type: "user_input", content: [{ type: "text", text: "ignored" }] },
        { type: "model_output", content: [{ type: "audio", mime_type: "audio/wav", data: wav.toString("base64") }] }
      ]
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
  assert.equal(captured[0].url, "https://generativelanguage.googleapis.com/v1beta/interactions");
  assert.equal(captured[0].url.includes("secret-google-key"), false);
  assert.equal(captured[0].options.headers["x-goog-api-key"], "secret-google-key");
  assert.equal(captured[0].body.model, "gemini-3.8-flash-tts");
  assert.equal(captured[0].body.input[0].type, "user_input");
  assert.equal(captured[0].body.input[0].content[0].text, "السَّلَامُ عَلَيْكُمْ");
  assert.equal(captured[0].body.input[0].content[0].annotations[0].type, "speech_metadata");
  assert.match(captured[0].body.input[0].content[0].annotations[0].style, /Modern Standard Arabic/);
  assert.match(captured[0].body.input[0].content[0].annotations[0].style, /1.15/);
  assert.deepEqual(captured[0].body.response_format, { type: "audio" });
  assert.deepEqual(captured[0].body.generation_config.speech_config, [{ voice: "Kore" }]);
  assert.equal(result.contentType, "audio/wav");
  assert.equal(result.extension, "wav");
  assert.equal(result.audio.length, 640);
});

test("Gemini prebuilt voice profiles can be changed with server environment variables", async () => {
  let requestedVoice = "";
  const wav = Buffer.alloc(640);
  wav.write("RIFF", 0, "ascii");
  wav.write("WAVE", 8, "ascii");
  const fakeFetch = async (_url, options) => {
    const body = JSON.parse(options.body);
    requestedVoice = body.generation_config.speech_config[0].voice;
    return new Response(JSON.stringify({
      steps: [{ type: "model_output", content: [{ type: "audio", data: wav.toString("base64") }] }]
    }), { status: 200 });
  };
  const adapters = createProviderAdapters({
    GEMINI_API_KEY: "key",
    GEMINI_VOICE_FEMALE: "Iapetus"
  }, fakeFetch);
  await adapters["gemini-quality"]({ text: "مرحبًا", voice: "female" });
  assert.equal(requestedVoice, "Iapetus");
});

test("Gemini rejects a response that contains no audio data", async () => {
  const fakeFetch = async () => new Response(JSON.stringify({
    steps: [{ type: "model_output", content: [{ type: "text", text: "no audio" }] }]
  }), { status: 200 });
  const adapters = createProviderAdapters({ GEMINI_API_KEY: "key" }, fakeFetch);
  await assert.rejects(
    () => adapters["gemini-quality"]({ text: "نص", voice: "male" }),
    /GEMINI_AUDIO_MISSING/
  );
});
