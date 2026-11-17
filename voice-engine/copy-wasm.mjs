import fs from "node:fs";
import path from "node:path";

const source = path.resolve("node_modules/onnxruntime-web/dist");
const target = path.resolve("public/wasm");
if (!fs.existsSync(source)) {
  throw new Error("onnxruntime-web dist folder is missing. Run npm install first.");
}
fs.mkdirSync(target, { recursive: true });
const files = fs.readdirSync(source).filter(name => /^ort-wasm-.*\.(wasm|mjs)$/.test(name));
if (!files.some(name => name.endsWith(".wasm"))) {
  throw new Error("No ONNX Runtime WASM binaries were found.");
}
for (const name of files) fs.copyFileSync(path.join(source, name), path.join(target, name));
console.log(`Copied ${files.length} ONNX Runtime local runtime files.`);
