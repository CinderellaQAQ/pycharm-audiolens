import { spawnSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { build } from "esbuild";

const directory = await mkdtemp(path.join(tmpdir(), "audiolens-web-test-"));
const output = path.join(directory, "sample-value.test.cjs");

try {
  await build({
    entryPoints: ["src/shared/sampleValue.test.ts"],
    outfile: output,
    bundle: true,
    platform: "node",
    format: "cjs",
    target: "node20",
    logLevel: "silent"
  });
  const result = spawnSync(process.execPath, [output], { stdio: "inherit" });
  if (result.status !== 0) {
    process.exitCode = result.status ?? 1;
  }
} finally {
  await rm(directory, { recursive: true, force: true });
}
