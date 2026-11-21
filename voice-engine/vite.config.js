import { defineConfig } from "vite";
import path from "node:path";
import { build } from "esbuild";

const inlineNabraWorker = {
  name: "inline-nabra-worker",
  enforce: "pre",
  resolveId(source, importer) {
    if (!source.startsWith("inline-worker:")) return null;
    if (!importer) throw new Error("Nabra inline worker has no importer.");
    const relative = source.slice("inline-worker:".length) + ".ts";
    const resolved = path.resolve(path.dirname(importer), relative);
    return "\0nabra-inline-worker:" + resolved;
  },
  async load(id) {
    const prefix = "\0nabra-inline-worker:";
    if (!id.startsWith(prefix)) return null;
    const entry = id.slice(prefix.length);
    const result = await build({
      entryPoints: [entry],
      bundle: true,
      format: "esm",
      platform: "browser",
      target: "es2022",
      minify: true,
      write: false,
      external: ["node:*", "fs", "path", "module", "url", "worker_threads", "crypto"],
      logLevel: "error"
    });
    return "export default " + JSON.stringify(result.outputFiles[0].text) + ";";
  }
};

export default defineConfig({
  base: "./",
  publicDir: "public",
  plugins: [inlineNabraWorker],
  build: {
    outDir: "../app/src/main/assets/voice_engine",
    emptyOutDir: true,
    target: "es2022",
    assetsInlineLimit: 0,
    rollupOptions: {
      input: {
        index: new URL("./index.html", import.meta.url).pathname,
        tashkeel: new URL("./tashkeel.html", import.meta.url).pathname
      }
    }
  }
});
