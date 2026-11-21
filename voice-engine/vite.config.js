import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  publicDir: "public",
  build: {
    outDir: "../app/src/main/assets/voice_engine",
    emptyOutDir: true,
    target: "es2022",
    assetsInlineLimit: 0,
    rollupOptions: {
      input: {
        tashkeel: new URL("./tashkeel.html", import.meta.url).pathname
      }
    }
  }
});
