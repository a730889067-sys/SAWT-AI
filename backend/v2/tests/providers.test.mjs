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
