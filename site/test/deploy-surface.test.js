import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, extname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const dist = join(root, "dist");
const textExtensions = new Set([".css", ".html", ".js", ".json", ".map", ".svg", ".txt", ".webmanifest", ".xml"]);
const forbiddenMarkers = ["codex", "claude", "copilot"];
const oldPublicName = `${"Viss"}${"arion"} Zounarakis`;
const oldPublicEmail = `${"viss"}${"arion"}@zounarakis.com`;
const forbiddenIdentityPatterns = [
  [new RegExp(oldPublicName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"), "old public owner name"],
  [new RegExp(oldPublicEmail.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"), "old public owner email"],
];

function walkFiles(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walkFiles(path) : [path];
  });
}

function isTextArtifact(path) {
  return textExtensions.has(extname(path).toLowerCase());
}

describe("Talos deploy surface", () => {
  it("does not leak assistant provenance markers into built site output", () => {
    assert.ok(existsSync(dist), "site/dist missing; run `npm run build --prefix site` before this check");

    const findings = [];
    for (const file of walkFiles(dist).filter(isTextArtifact)) {
      const rel = relative(root, file).replaceAll("\\", "/");
      const body = readFileSync(file, "utf8");
      for (const marker of forbiddenMarkers) {
        if (new RegExp(`\\b${marker}\\b`, "i").test(body)) {
          findings.push(`${rel}: forbidden marker ${marker}`);
        }
      }
      for (const [pattern, label] of forbiddenIdentityPatterns) {
        if (pattern.test(body)) {
          findings.push(`${rel}: forbidden ${label}`);
        }
      }
    }

    assert.deepEqual(findings, []);
  });
});
