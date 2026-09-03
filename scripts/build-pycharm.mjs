import { build } from "esbuild";

await build({
  entryPoints: ["src/webview/main.ts"],
  outfile: "dist/webview.js",
  bundle: true,
  minify: true,
  logLevel: "info",
  platform: "browser",
  format: "iife",
  target: "es2022"
});
