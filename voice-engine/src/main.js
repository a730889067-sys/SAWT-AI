import { NabraTTS, clearCache } from "../vendor/nabra-tts/src/index.ts";

const native = () => window.SAWTBridge;
let tts = null;
let loadPromise = null;
let loadedKey = "";

const DEFAULT_PACK = {
  repo: "oddadmix/Nabra-7M-Distill-ONNX",
  revision: "bbac0f1e6b001bfae89871be366a4b000180deb2",
  dtype: "fp16",
  device: "wasm"
};

function notifyError(error) {
  const message = String(error?.message ?? error ?? "تعذر تشغيل محرك Nabra.");
  native()?.onEngineError(message);
}
function notifyProgress(name, loaded, total) {
  native()?.onEngineProgress(name, Number(loaded) || 0, Number(total) || 0);
}
function normalizedOptions(options = {}) {
  const repo = String(options.repo || DEFAULT_PACK.repo);
  const revision = String(options.revision || DEFAULT_PACK.revision);
  const dtype = options.dtype === "fp32" ? "fp32" : "fp16";
  // Android downloads pinned model files into private storage first. The WebView
  // is allowed to fetch only those files through its internal asset origin.
  const localPrefix = "https://appassets.androidplatform.net/models/";
  const localModelPattern = /^https:\/\/appassets\.androidplatform\.net\/models\/[a-z0-9][a-z0-9-]{2,60}\/(?:onnx\/model(?:_fp16)?\.onnx|voices\/af_msa\.bin)$/;
  const modelUrl = String(options.modelUrl || "");
  const voiceUrl = String(options.voiceUrl || "");
  if (repo !== DEFAULT_PACK.repo || !/^[a-f0-9]{40}$/i.test(revision)) {
    throw new Error("حزمة الصوت غير معتمدة أو أن إصدارها غير مثبت.");
  }
  if (options.localModels !== true ||
      !modelUrl.startsWith(localPrefix) ||
      !voiceUrl.startsWith(localPrefix) ||
      !localModelPattern.test(modelUrl) ||
      !localModelPattern.test(voiceUrl)) {
    throw new Error("ملفات حزمة الصوت المحلية غير جاهزة أو أن مسارها غير معتمد.");
  }
  return { repo, revision, dtype, device: "wasm", modelUrl, voiceUrl, localModels: true };
}
function packKey(options) {
  return [options.repo, options.revision, options.dtype].join("@");
}

async function load(options = {}) {
  const safe = normalizedOptions(options);
  const key = packKey(safe);
  if (tts && loadedKey === key) return tts;
  if (loadPromise && loadedKey === key) return loadPromise;
  if (tts) {
    try { await tts.dispose(); } catch {}
    tts = null;
    loadedKey = "";
  }
  loadPromise = (async () => {
    // Keep ONNX Runtime WASM beside the APK's local WebView assets. It must not
    // fall back to a CDN when the device is offline.
    const engine = await NabraTTS.load({
      ...safe,
      worker: true,
      // Model and voice files have already been downloaded with resumable HTTP
      // transfers by the Android layer; avoid making a duplicate WebView cache.
      cache: !safe.localModels,
      numThreads: 1,
      wasmPaths: new URL("./wasm/", window.location.href).href,
      normalize: false,
      onProgress: p => notifyProgress(p.file, p.loaded, p.total)
    });
    tts = engine;
    loadedKey = key;
    native()?.onEngineReady(engine.device);
    return engine;
  })();
  try {
    return await loadPromise;
  } catch (error) {
    loadPromise = null;
    tts = null;
    loadedKey = "";
    notifyError(error);
    throw error;
  }
}

function floatToPcm16(audio) {
  const result = new Uint8Array(audio.length * 2);
  const view = new DataView(result.buffer);
  for (let i = 0; i < audio.length; i++) {
    const sample = Math.max(-1, Math.min(1, audio[i]));
    view.setInt16(i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }
  return result;
}
function base64(bytes) {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length)));
  }
  return btoa(binary);
}
function sendPcm(bytes) {
  const bridge = native();
  if (!bridge) throw new Error("تعذر الاتصال بتطبيق SAWT AI.");
  const chunkSize = 48 * 1024;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    if (!bridge.appendAudioChunk(base64(bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length))))) {
      throw new Error("تعذرت كتابة جزء من التسجيل.");
    }
  }
}

async function generate(text, speed = 1, options = {}) {
  if (typeof text !== "string" || !text.trim()) throw new Error("اكتب نصًا أولًا.");
  if (new TextEncoder().encode(text).length > 5000) {
    throw new Error("النص أطول من الحد المسموح به (5000 بايت).");
  }
  try {
    const engine = await load(options);
    if (!native()?.beginAudio(24000)) throw new Error("تعذر إنشاء ملف الصوت المحلي.");
    let chunksCount = 0;
    for await (const chunk of engine.stream(text, { speed: Math.max(0.8, Math.min(1.2, speed)) })) {
      if (chunk.audio?.length) {
        sendPcm(floatToPcm16(chunk.audio));
        chunksCount++;
      }
    }
    if (!chunksCount) throw new Error("لم ينتج محرك الصوت أي مقاطع صالحة.");
    if (!native()?.finishAudio()) throw new Error("تعذر إكمال ملف WAV.");
  } catch (error) {
    native()?.cancelAudio();
    notifyError(error);
    throw error;
  }
}

async function releaseRuntime() {
  try { await tts?.dispose(); } catch {}
  tts = null;
  loadPromise = null;
  loadedKey = "";
  native()?.onEngineReleased();
}

async function removeDownloadedModels() {
  try { await tts?.dispose(); } catch {}
  tts = null;
  loadPromise = null;
  loadedKey = "";
  await clearCache();
  native()?.onModelsRemoved();
}

window.sawtTts = {
  load: options => load(options).catch(() => null),
  generate,
  releaseRuntime,
  removeDownloadedModels
};

native()?.onEnginePageReady();
