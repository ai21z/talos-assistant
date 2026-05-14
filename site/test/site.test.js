import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const read = (path) => readFileSync(join(root, path), "utf8");
const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const publicFiles = ["index.html", "src/main.js", "src/styles.css"];
const publicText = () => publicFiles.map(read).join("\n");

function walkFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walkFiles(path) : [path];
  });
}

function anchorTargets(html) {
  return Array.from(html.matchAll(/href="#([^"]+)"/g), (match) => match[1]);
}

function ids(html) {
  return new Set(Array.from(html.matchAll(/\sid="([^"]+)"/g), (match) => match[1]));
}

describe("Talos landing page static contract", () => {
  it("uses the final site package name and required scripts", () => {
    const pkg = JSON.parse(read("package.json"));
    assert.equal(pkg.name, "talos-site");
    assert.equal(pkg.scripts.dev, "vite");
    assert.equal(pkg.scripts.build, "vite build");
    assert.equal(pkg.scripts.preview, "vite preview");
    assert.equal(pkg.scripts.test, "npm run test:static");
    assert.equal(pkg.scripts["test:static"], "node --test test/site.test.js");
    assert.equal(pkg.scripts["test:e2e"], "playwright test");
  });

  it("keeps production source maps disabled and emits no .map files after build", () => {
    assert.match(read("vite.config.js"), /sourcemap:\s*false/);
    const mapFiles = walkFiles(join(root, "dist")).filter((file) => file.endsWith(".map"));
    assert.deepEqual(mapFiles, []);
  });

  it("has one descriptive h1 and keeps Talos as product, not just ceremony", () => {
    const html = read("index.html");
    const h1Matches = Array.from(html.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/gi));
    assert.equal(h1Matches.length, 1);
    const h1Text = h1Matches[0][1].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    assert.match(h1Text, /local-first/i);
    assert.match(h1Text, /workspace/i);
    assert.notEqual(h1Text.toUpperCase(), "TALOS");
  });

  it("uses concrete hero copy and beta CTA hierarchy", () => {
    const html = read("index.html").replace(/\s+/g, " ");
    const hero = html.slice(html.indexOf('id="product"'), html.indexOf('id="workflow"'));

    for (const copy of [
      "reads the workspace",
      "proposes bounded changes",
      "asks before mutation",
      "checks the result",
      "leaves a trace",
      "No hosted workspace handoff",
      "Get beta build",
    ]) {
      assert.match(hero, new RegExp(escapeRegExp(copy), "i"));
    }

    assert.doesNotMatch(hero, />\s*Setup models\s*</i);
    assert.doesNotMatch(hero, /runtime-owned outcomes/i);
    assert.doesNotMatch(hero, /tool surface/i);
    assert.doesNotMatch(hero, /contract defines intent/i);
    assert.doesNotMatch(hero, /prompt-debug evidence/i);
  });

  it("keeps real command examples and canonical runtime tool names", () => {
    const text = publicText();
    for (const command of [
      "talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write",
      "talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write",
      "talos status --verbose",
      "/tools",
      "/models",
      "/workspace",
      "/last trace",
      "talos.list_dir",
      "talos.read_file",
      "talos.grep",
      "talos.retrieve",
      "talos.write_file",
      "talos.run_command",
      "gradle_test",
    ]) {
      assert.match(text, new RegExp(escapeRegExp(command), "i"));
    }
  });

  it("does not introduce fake downloads, external runtime assets, or hype claims", () => {
    const text = publicText();
    assert.doesNotMatch(text, /https?:\/\//i);
    assert.doesNotMatch(text, /href="[^"]*\.(?:zip|msi|exe|dmg|pkg|tar\.gz)"/i);
    assert.doesNotMatch(text, /download\s*=/i);

    for (const misleading of [
      "swarm",
      "multi-agent",
      "autonomous workforce",
      "replaces developers",
      "one-click cloud agent",
      "retrieve_context",
      "gradle-test",
      "Every action is verified",
      "verified write + /last trace",
      "--local-only",
      "No telemetry",
    ]) {
      assert.doesNotMatch(text, new RegExp(escapeRegExp(misleading), "i"));
    }
  });

  it("keeps anchor navigation targetable", () => {
    const html = read("index.html");
    const definedIds = ids(html);
    for (const target of anchorTargets(html)) {
      assert.ok(definedIds.has(target), `missing #${target}`);
    }
  });

  it("labels all copy buttons uniquely and accessibly", () => {
    const html = read("index.html");
    const copyButtons = Array.from(html.matchAll(/<button\b[^>]*class="[^"]*\bcopy-button\b[^"]*"[^>]*>/g));
    assert.ok(copyButtons.length >= 6);

    const labels = copyButtons.map((match) => {
      const attr = match[0].match(/\saria-label="([^"]+)"/);
      return attr?.[1] ?? "";
    });

    assert.equal(labels.length, new Set(labels).size);
    assert.ok(labels.every((label) => /^Copy .+ command$/i.test(label)), labels.join(", "));
  });

  it("uses accessible terminal and decorative art semantics", () => {
    const html = read("index.html");
    assert.doesNotMatch(html, /<pre[^>]*aria-live=/i);
    assert.match(html, /id="terminal-status"[\s\S]*aria-live="polite"/i);
    assert.doesNotMatch(html, /aria-hidden="true"[\s\S]{0,500}<svg[^>]*(?:role="img"|aria-label=)/i);
  });

  it("supports anchor offset and reduced motion", () => {
    const css = read("src/styles.css");
    assert.match(css, /scroll-margin-top/);
    assert.match(css, /prefers-reduced-motion:\s*reduce/);
    assert.match(css, /scroll-behavior:\s*auto\s*!important/);
    assert.match(css, /transition(?:-duration)?:\s*none\s*!important|transition-duration:\s*0\.01ms\s*!important/);
    assert.match(css, /animation(?:-duration)?:\s*none\s*!important|animation-duration:\s*0\.01ms\s*!important/);
    assert.match(css, /\.js\s+\.reveal[\s\S]*opacity:\s*1/);
  });

  it("keeps vanilla JavaScript behavior for tabs, copy, and beta placeholder CTA", () => {
    const js = read("src/main.js");
    assert.match(js, /terminalStates/);
    assert.match(js, /ArrowRight/);
    assert.match(js, /ArrowLeft/);
    assert.match(js, /Home/);
    assert.match(js, /End/);
    assert.match(js, /navigator\.clipboard\.writeText/);
    assert.match(js, /data-beta-placeholder/);
    assert.match(js, /Beta download placeholder\. Build artifacts will be added later\./);
    assert.doesNotMatch(js, /React|Vue|createApp|tailwind/i);
  });
});
