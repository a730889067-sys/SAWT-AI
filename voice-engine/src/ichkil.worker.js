import * as ort from "onnxruntime-web";

const REVISION = "v0.3.0";
const BASE = `https://huggingface.co/ichkil/ichkil/resolve/${REVISION}`;
const MODEL_URL = `${BASE}/model.onnx`;
const CONFIG_URL = `${BASE}/config.json`;
const EXPECTED_SHA256 = "9779e1aca89cebc77a9b92037b7d700c422cdf6c3774d4883f4652f641f3f705";
const CACHE_NAME = "sawt-ai-ichkil-v030";

let session = null;
let config = null;
let loading = null;

const charMap = {
  "\u0671": "\u0627",
  "\u06CC": "\u064A",
  "\u06A9": "\u0643",
  "\u06C1": "\u0647",
  "\u00A0": " "
};
const diacritics = /[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]/g;
const tatweel = "\u0640";
const labelToCombo = [
  "", "", "\u064E", "\u064F", "\u0650", "\u0652", "\u0651",
  "\u0651\u064E", "\u0651\u064F", "\u0651\u0650", "\u064B",
  "\u064C", "\u064D", "\u0651\u064B", "\u0651\u064C", "\u0651\u064D"
];

function normalizeText(value) {
  let text = String(value).normalize("NFC");
  text = Array.from(text, ch => charMap[ch] ?? ch).join("");
  return text.replace(/[\u200B-\u200F\uFEFF]/g, "").replace(/\s+/g, " ").trim();
}
function stripDiacritics(value) {
  return normalizeText(value).replace(diacritics, "").split(tatweel).join("");
}
function bytesToHex(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}
async function sha256(buffer) {
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return bytesToHex(new Uint8Array(digest));
}
async function readWithProgress(response, name) {
  const total = Number(response.headers.get("Content-Length")) || 0;
  if (!response.body) {
    const buffer = await response.arrayBuffer();
    self.postMessage({ type: "progress", name, loaded: buffer.byteLength, total: total || buffer.byteLength });
    return buffer;
  }
  const reader = response.body.getReader();
  const pieces = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    pieces.push(value);
    loaded += value.byteLength;
    self.postMessage({ type: "progress", name, loaded, total });
  }
  const result = new Uint8Array(loaded);
  let offset = 0;
  for (const piece of pieces) {
    result.set(piece, offset);
    offset += piece.byteLength;
  }
  return result.buffer;
}
async function fetchCached(url, name, verifyHash = false) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(url);
  if (cached) {
    const buffer = await cached.arrayBuffer();
    if (!verifyHash || await sha256(buffer) === EXPECTED_SHA256) {
      self.postMessage({ type: "progress", name, loaded: buffer.byteLength, total: buffer.byteLength, cached: true });
      return buffer;
    }
    await cache.delete(url);
  }
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) throw new Error(`تعذر تنزيل ${name} (HTTP ${response.status}).`);
  const buffer = await readWithProgress(response, name);
  if (verifyHash && await sha256(buffer) !== EXPECTED_SHA256) {
    throw new Error("فشل التحقق من سلامة نموذج التشكيل SHA-256؛ أُوقف التحميل.");
  }
  await cache.put(url, new Response(buffer, {
    headers: { "Content-Type": "application/octet-stream" }
  }));
  return buffer;
}
async function load() {
  if (session) return;
  if (loading) return loading;
  loading = (async () => {
    ort.env.wasm.wasmPaths = new URL("../wasm/", import.meta.url).href;
    ort.env.wasm.numThreads = 1;

    const configBuffer = await fetchCached(CONFIG_URL, "config", false);
    config = JSON.parse(new TextDecoder().decode(configBuffer));
    if (!config.sym2id || config.num_tags !== 16 || (config.max_seq_len ?? 1024) < 1) {
      throw new Error("ملف إعدادات التشكيل غير صالح أو غير متوافق.");
    }

    const modelBuffer = await fetchCached(MODEL_URL, "diacritizer", true);
    session = await ort.InferenceSession.create(modelBuffer, {
      executionProviders: ["wasm"],
      graphOptimizationLevel: "all",
      logSeverityLevel: 3
    });
    self.postMessage({ type: "ready" });
  })();
  try {
    await loading;
  } catch (error) {
    loading = null;
    throw error;
  }
}
async function diacritize(rawText) {
  await load();
  const base = stripDiacritics(rawText);
  if (!base) return rawText;
  const chars = Array.from(base);
  const max = config.max_seq_len ?? 1024;
  if (chars.length > max) throw new Error("قسّم النص إلى فقرات أقصر ثم أعد المحاولة.");

  const ids = BigInt64Array.from(chars, ch => BigInt(config.sym2id[ch] ?? config.sym2id["<unk>"] ?? 1));
  const input = new ort.Tensor("int64", ids, [1, ids.length]);
  let output;
  try {
    output = await session.run({ input_ids: input });
    const logits = output.logits?.data;
    if (!logits) throw new Error("نموذج التشكيل لم يُرجع النتائج المتوقعة.");
    let result = "";
    for (let i = 0; i < chars.length; i++) {
      const ch = chars[i];
      let best = 0;
      let bestValue = -Infinity;
      const offset = i * config.num_tags;
      for (let label = 0; label < config.num_tags; label++) {
        const value = logits[offset + label];
        if (value > bestValue) {
          bestValue = value;
          best = label;
        }
      }
      const isArabic = /[\u0621-\u063A\u0641-\u064A]/.test(ch);
      result += ch + (isArabic ? (labelToCombo[best] ?? "") : "");
    }
    return result;
  } finally {
    input.dispose?.();
    if (output) for (const tensor of Object.values(output)) tensor.dispose?.();
  }
}
self.onmessage = async event => {
  const message = event.data || {};
  if (message.type === "load") {
    try { await load(); }
    catch (error) { self.postMessage({ type: "error", id: message.id, message: String(error?.message ?? error) }); }
  } else if (message.type === "diacritize") {
    try {
      const result = await diacritize(message.text);
      self.postMessage({ type: "result", id: message.id, text: result });
    } catch (error) {
      self.postMessage({ type: "error", id: message.id, message: String(error?.message ?? error) });
    }
  } else if (message.type === "dispose") {
    try { await session?.release(); } catch {}
    session = null;
    self.close();
  }
};
