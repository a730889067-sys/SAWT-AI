const native = () => window.SAWTTashkeelNative;
let worker = null;

function postError(id, error) {
  native()?.onError(id, String(error?.message ?? error ?? "تعذر تشكيل النص العربي."));
}

function ensureWorker() {
  if (worker) return worker;
  worker = new Worker(new URL("./ichkil.worker.js", import.meta.url), {
    type: "module",
    name: "sawt-ichkil-diacritizer"
  });

  worker.onmessage = event => {
    const message = event.data || {};
    if (message.type === "progress") {
      native()?.onProgress(message.name || "diacritizer", Number(message.loaded) || 0, Number(message.total) || 0);
    } else if (message.type === "ready") {
      native()?.onWorkerReady();
    } else if (message.type === "result") {
      native()?.onResult(Number(message.id), String(message.text ?? ""));
    } else if (message.type === "error") {
      postError(Number(message.id ?? -1), message.message);
    }
  };

  worker.onerror = event => {
    postError(-1, event.message || "تعطل محرك التشكيل المحلي.");
  };
  return worker;
}

window.sawtTashkeel = {
  diacritize(id, text) {
    try {
      ensureWorker().postMessage({
        type: "diacritize",
        id: Number(id),
        text: String(text ?? "")
      });
    } catch (error) {
      postError(Number(id), error);
    }
  },
  warmUp() {
    try {
      ensureWorker().postMessage({ type: "load" });
    } catch (error) {
      postError(-1, error);
    }
  },
  dispose() {
    try { worker?.postMessage({ type: "dispose" }); } catch {}
    try { worker?.terminate(); } catch {}
    worker = null;
  }
};

native()?.onPageReady();
