import { NabraTTS, clearCache } from "nabra-tts";

const native = () => window.SAWTBridge;
let tts = null;
let loadPromise = null;
let diacritizer = null;
let diacritizerReady = null;
let nextRequestId = 1;
const diacritizerPending = new Map();

function notifyError(error) {
  const message = String(error?.message ?? error ?? "حدث خطأ غير متوقع في محرك الصوت.");
  native()?.onEngineError(message);
}
function notifyProgress(name, loaded, total) {
  native()?.onEngineProgress(name, Number(loaded) || 0, Number(total) || 0);
}

function ensureDiacritizer() {
  if (diacritizerReady) return diacritizerReady;
  diacritizerReady = new Promise((resolve, reject) => {
    try {
      diacritizer = new Worker(new URL("./ichkil.worker.js", import.meta.url), {
        type: "module",
        name: "sawt-ichkil-diacritizer"
      });
      diacritizer.onmessage = event => {
        const message = event.data || {};
        if (message.type === "progress") {
          notifyProgress(message.name, message.loaded, message.total);
          return;
        }
        if (message.type === "ready") {
          resolve(true);
          return;
        }
        if (message.id !== undefined) {
          const pending = diacritizerPending.get(message.id);
          if (!pending) return;
          diacritizerPending.delete(message.id);
          if (message.type === "result") pending.resolve(message.text);
          else if (message.type === "error") pending.reject(new Error(message.message));
        } else if (message.type === "error") {
          reject(new Error(message.message));
        }
      };
      diacritizer.onerror = event => reject(new Error(event.message || "تعطل محرك التشكيل."));
      diacritizer.postMessage({ type: "load" });
    } catch (error) {
      reject(error);
    }
  });
  return diacritizerReady;
}

async function diacritizeArabic(text) {
  await ensureDiacritizer();
  const id = nextRequestId++;
  return new Promise((resolve, reject) => {
    diacritizerPending.set(id, { resolve, reject });
    diacritizer.postMessage({ type: "diacritize", id, text });
  });
}

async function load() {
  if (tts) return tts;
  if (loadPromise) return loadPromise;
  loadPromise = (async () => {
    const model = await NabraTTS.load({
      repo: "oddadmix/Nabra-7M-Distill-ONNX",
      revision: "bbac0f1e6b001bfae89871be366a4b000180deb2",
      dtype: "fp16",
      device: "wasm",
      worker: true,
      cache: true,
      numThreads: 1,
      wasmPaths: new URL("./wasm/", window.location.href).href,
      normalize: false,
      diacritize: diacritizeArabic,
      onProgress: progress => notifyProgress(progress.file, progress.loaded, progress.total)
    });
    await ensureDiacritizer();
    tts = model;
    native()?.onEngineReady("wasm");
    return model;
  })();
  try {
    return await loadPromise;
  } catch (error) {
    loadPromise = null;
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
      throw new Error("تعذرت كتابة جزء من ملف الصوت.");
    }
  }
}

async function generate(text, speed = 1) {
  if (typeof text !== "string" || !text.trim()) throw new Error("اكتب نصًا أولًا.");
  if (new TextEncoder().encode(text).length > 5000) {
    throw new Error("النص أطول من الحد المسموح به (5000 بايت).");
  }
  try {
    const engine = await load();
    if (!native()?.beginAudio(24000)) throw new Error("تعذر إنشاء ملف الصوت المحلي.");
    for await (const chunk of engine.stream(text, { speed: Math.max(0.8, Math.min(1.2, speed)) })) {
      sendPcm(floatToPcm16(chunk.audio));
    }
    if (!native()?.finishAudio()) throw new Error("تعذر إكمال ملف الصوت المحلي.");
  } catch (error) {
    native()?.cancelAudio();
    notifyError(error);
    throw error;
  }
}

async function removeDownloadedModels() {
  try { await tts?.dispose(); } catch {}
  tts = null;
  loadPromise = null;
  try { diacritizer?.postMessage({ type: "dispose" }); } catch {}
  diacritizer?.terminate();
  diacritizer = null;
  diacritizerReady = null;
  diacritizerPending.clear();
  await clearCache();
  await caches.delete("sawt-ai-ichkil-v030");
  native()?.onModelsRemoved();
}

window.sawtTts = {
  load: () => load().catch(() => null),
  generate: (text, speed = 1) => generate(text, speed),
  removeDownloadedModels
};

window.SAWTBridge?.onEnginePageReady();
