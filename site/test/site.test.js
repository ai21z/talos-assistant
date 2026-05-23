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

function currentTalosVersion() {
  const props = readFileSync(join(root, "..", "gradle.properties"), "utf8");
  const match = props.match(/^talosVersion=(.+)$/m);
  assert.ok(match, "gradle.properties must define talosVersion");
  return match[1].trim();
}

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

function sectionSlice(html, startId, endId) {
  const start = html.indexOf(`id="${startId}"`);
  const end = endId ? html.indexOf(`id="${endId}"`) : html.length;
  assert.ok(start >= 0, `missing #${startId}`);
  assert.ok(end > start, `missing or invalid end #${endId}`);
  return html.slice(start, end);
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

  it("uses one descriptive h1 grounded in local-first workspace identity", () => {
    const html = read("index.html");
    const h1Matches = Array.from(html.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/gi));
    assert.equal(h1Matches.length, 1);
    const h1Text = h1Matches[0][1].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    assert.match(h1Text, /local-first/i);
    assert.match(h1Text, /workspace/i);
    assert.notEqual(h1Text.toUpperCase(), "TALOS");
  });

  it("uses the six-screen story map with reduced navigation labels", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const navMatch = html.match(/<nav\b[^>]*id="primary-navigation"[\s\S]*?<\/nav>/);
    assert.ok(navMatch, "missing #primary-navigation nav");
    const nav = navMatch[0];
    const storySections = Array.from(html.matchAll(/<section\b(?=[^>]*\bstory-section\b)(?=[^>]*\bid="([^"]+)")[^>]*>/g), (m) => m[1]);

    assert.deepEqual(storySections, [
      "overview",
      "execution",
      "turn-ui",
      "local-boundaries",
      "good-fits",
      "docs",
    ]);

    for (const label of ["Overview", "Execution", "Turn UI", "Local Boundaries", "Good Fits", "Docs"]) {
      assert.match(nav, new RegExp(`>${escapeRegExp(label)}<`));
    }

    for (const removed of ["Product", "Contract", ">CLI<", "Use cases", "Install"]) {
      assert.doesNotMatch(nav, new RegExp(escapeRegExp(removed), "i"));
    }

    assert.doesNotMatch(html, /\sid="install"/);
    assert.doesNotMatch(html, /install-section/);
    assert.doesNotMatch(css, /#install\b|install-section/);
  });

  it("uses concrete hero copy, honest setup state, and no fake install CTA", () => {
    const html = read("index.html").replace(/\s+/g, " ");
    const hero = sectionSlice(html, "overview", "execution");

    for (const copy of [
      "Inspects before acting",
      "Asks before mutation",
      "Verifies before claiming success",
      "Approved writes only",
      "Interactive turns leave local trace evidence",
      "No hosted workspace handoff",
      "View on GitHub",
      "Read docs",
      "planned public beta",
      "winget install talos-cli",
      "TalosProject.TalosCLI",
      "talos",
    ]) {
      assert.match(hero, new RegExp(escapeRegExp(copy), "i"));
    }

    assert.doesNotMatch(hero, /Get beta build/i);
    assert.doesNotMatch(hero, /data-beta-placeholder/i);
    assert.doesNotMatch(hero, /data-copy="[^"]*winget/i);
  });

  it("shows the real Talos icon without cropped background or boxed mark", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    assert.ok(existsSync(join(root, "design", "talos-icon.png")), "talos-icon.png missing");
    assert.match(html, /design\/talos-icon\.png/);
    assert.doesNotMatch(html, /data:image\/svg\+xml/i);
    assert.doesNotMatch(html, /<svg\b/i);
    assert.doesNotMatch(css, /url\(["']?\.\.\/design\/talos-icon\.png/);
    assert.doesNotMatch(css, /\.brand-mark img\s*\{[\s\S]*?opacity:\s*0/);
    const wordmarkBlock = css.match(/\.wordmark-mark\s*\{(?<block>[^}]*)\}/)?.groups?.block ?? "";
    assert.doesNotMatch(wordmarkBlock, /border:/);
    assert.match(css, /\.wordmark-mark[\s\S]*?object-fit:\s*contain|\.wordmark-mark img[\s\S]*?object-fit:\s*contain/);
  });

  it("uses the locked startup terminal screenshot as the dominant hero proof", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const hero = sectionSlice(html, "overview", "execution");
    const heroText = hero.replace(/\s+/g, " ");

    assert.ok(existsSync(join(root, "design", "img.png")), "img.png missing");
    assert.match(hero, /<img\b[^>]*class="startup-terminal-image"[^>]*src="\.\/design\/img\.png"/);
    assert.match(hero, /alt="[^"]*Talos startup terminal screen/i);
    assert.doesNotMatch(hero, /<pre\b[^>]*class="banner"/i);
    assert.match(css, /grid-template-columns:\s*minmax\(0,\s*0\.7[0-9]fr\)\s+minmax\(0,\s*1\.2[0-9]fr\)/);

    for (const copy of [
      "TALOS",
      `v${currentTalosVersion()}`,
      "llama_cpp/gpt-oss-20b",
      "llama.cpp (managed)",
      "ready (5 chunks)",
      "ask before mutation",
    ]) {
      assert.match(heroText, new RegExp(escapeRegExp(copy)));
    }
  });

  it("renders the exact Greek heritage inscription with a self-hosted bronze font treatment", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const js = read("src/main.js");
    const pkg = JSON.parse(read("package.json"));
    const hero = sectionSlice(html, "overview", "execution");
    const publicSurface = publicText();
    const greekBlock = css.match(/\.greek-hero-inscription\s*\{(?<block>[\s\S]*?)\}/)?.groups?.block ?? "";

    assert.ok(pkg.devDependencies["@fontsource/gfs-neohellenic"], "missing self-hosted GFS Neohellenic package");
    assert.match(js, /@fontsource\/gfs-neohellenic\/greek-700\.css/);
    assert.match(
      hero,
      /<div\s+class="greek-hero-inscription"\s+lang="el"\s+aria-hidden="true">\s*ΤΑΛΩΣ\s*<\/div>/,
    );
    assert.equal((publicSurface.match(/ΤΑΛΩΣ/g) ?? []).length, 1);
    assert.doesNotMatch(publicSurface, /TAΛOS|TALΩS|TAΛΩS/);
    assert.doesNotMatch(publicSurface, /fonts\.googleapis\.com|fonts\.gstatic\.com/);
    assert.match(css, /\.greek-hero-inscription\s*\{[\s\S]*font-family:\s*"GFS Neohellenic"/);
    assert.match(css, /\.greek-hero-inscription\s*\{[\s\S]*color:\s*var\(--bronze\)/);
    assert.doesNotMatch(greekBlock, /--cyan|var\(--cyan\)|color:\s*transparent|background-clip|linear-gradient/);
    assert.match(hero, /<img\b[^>]*class="startup-terminal-image"[^>]*src="\.\/design\/img\.png"/);
  });

  it("ships a linear execution flow with one compact tool evidence strip", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const execution = sectionSlice(html, "execution", "turn-ui");
    const stepOrder = ["Classify", "Inspect", "Approve", "Mutate", "Verify", "Trace"];
    let cursor = 0;
    for (const step of stepOrder) {
      const idx = execution.indexOf(`>${step}<`, cursor);
      assert.ok(idx >= 0, `execution step "${step}" missing or out of order`);
      cursor = idx;
    }

    assert.match(execution, /execution-tool-strip/);
    for (const token of ["talos.list_dir", "talos.read_file", "talos.write_file", "talos.run_command", "/last trace"]) {
      assert.match(execution, new RegExp(escapeRegExp(token)));
    }

    for (const banned of [
      "cycle-diagram",
      "process-orbits",
      "cycle-node",
      "cycle-core",
      "sentinel-frame",
      "sentinel-emblem",
      "radial-grid",
      "footer-medallion",
      "greek-key",
    ]) {
      assert.doesNotMatch(html, new RegExp(escapeRegExp(banned), "i"));
      assert.doesNotMatch(css, new RegExp(escapeRegExp(banned), "i"));
    }
  });

  it("presents local boundaries as grouped reads, mutations, and evidence", () => {
    const html = read("index.html");
    const boundaries = sectionSlice(html, "local-boundaries", "good-fits");
    for (const group of ["Reads", "Mutations", "Evidence"]) {
      assert.match(boundaries, new RegExp(`>${escapeRegExp(group)}<`));
    }
    for (const state of ["state--allow", "state--ask", "state--deny"]) {
      assert.match(boundaries, new RegExp(state));
    }
    for (const required of [
      "Workspace files",
      "Protected paths",
      "File writes",
      "Command execution",
      "Unsupported documents",
      "/last trace",
    ]) {
      assert.match(boundaries, new RegExp(escapeRegExp(required), "i"));
    }
    assert.doesNotMatch(boundaries, /prompt-debug/i);
  });

  it("keeps content claims precise about traces, lanes, trust, and install state", () => {
    const text = publicText().replace(/\s+/g, " ");

    for (const required of [
      "Interactive turns leave local trace evidence",
      "A consistent turn grammar",
      "Runtime policy owns approval, tool exposure, result checks, protected reads, and unsupported-file honesty",
      "planned public beta",
      "winget install talos-cli",
      "TalosProject.TalosCLI",
      "Vissarion Zounarakis",
      "bundled Java runtime",
      "does not bundle a llama.cpp server or model weights",
      "Source setup remains documented",
    ]) {
      assert.match(text, new RegExp(escapeRegExp(required), "i"));
    }

    for (const tooAbsolute of [
      "Every turn leaves a trace",
      "Every Talos turn runs the same six lanes",
      "The model cannot bypass them by rewording the request",
      "install now with winget",
      "Linux public beta",
      "macOS public beta",
      "bundled models",
      "bundled llama.cpp",
    ]) {
      assert.doesNotMatch(text, new RegExp(escapeRegExp(tooAbsolute), "i"));
    }
  });

  it("curates the docs gateway to four in-site user documentation cards", () => {
    const html = read("index.html");
    const docs = sectionSlice(html, "docs", null);
    const docCards = Array.from(docs.matchAll(/<a\s+class="doc-card[^"]*"[^>]*href="([^"]+)"/g));
    assert.equal(docCards.length, 4);
    for (const title of ["Quickstart", "Model Setup", "Permissions", "Trace / Audit"]) {
      assert.match(docs, new RegExp(`>${escapeRegExp(title)}<`));
    }
    for (const [, href] of docCards) {
      assert.match(href, /^\.\/docs\.html#\//, `doc card href ${href} does not route to in-site docs`);
    }
    assert.doesNotMatch(docs, /github\.com\/ai21z\/talos-cli\/blob\/v0\.9\.0-beta-dev\/docs\/architecture/);
  });

  it("keeps real command examples without marketing maintainer-only debug commands", () => {
    const text = publicText();
    for (const command of [
      "talos status --verbose",
      "/tools",
      "/models",
      "/workspace",
      "/last trace",
      "talos.list_dir",
      "talos.read_file",
      "talos.write_file",
      "talos.run_command",
    ]) {
      assert.match(text, new RegExp(escapeRegExp(command), "i"));
    }

    assert.doesNotMatch(text, /--server-path\s+C:\/path\/to\/llama-server\.exe/i);
    assert.doesNotMatch(text, /\/prompt-debug/i);
    assert.doesNotMatch(text, /data-copy="[^"]*(?:winget|curl|irm|iwr)[^"]*"/i);
  });

  it("does not introduce fake downloads or unsupported claims", () => {
    const text = publicText();

    assert.doesNotMatch(text, /href="[^"]*\.(?:zip|msi|exe|dmg|pkg|tar\.gz)"/i);
    assert.doesNotMatch(text, /\sdownload\s*=/i);

    const externalHrefs = Array.from(text.matchAll(/href="(https?:\/\/[^"]+)"/g), (m) => m[1]);
    for (const href of externalHrefs) {
      assert.match(href, /^https:\/\/github\.com\/ai21z\/talos-cli/, `unexpected external href: ${href}`);
    }

    for (const misleading of [
      "swarm",
      "multi-agent",
      "autonomous workforce",
      "replaces developers",
      "one-click cloud agent",
      "AI-powered",
      "agentic",
      "browse the web",
      "Every action is verified",
      "--local-only",
      "No telemetry",
      "Get beta build",
      "Beta download placeholder",
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

  it("uses accessible terminal semantics", () => {
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
    assert.doesNotMatch(css, /\.js\s+\.reveal\s*\{[\s\S]*?opacity:\s*0/);
  });

  it("uses native scroll with content-only story blending without scrolljacking", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const js = read("src/main.js");
    const storySections = Array.from(html.matchAll(/<section\b[^>]*class="[^"]*\bstory-section\b[^"]*"/g));

    assert.equal(storySections.length, 6);
    assert.match(css, /\.story-section\b/);
    assert.match(css, /--story-top:\s*72px/);
    assert.match(css, /\.story-section\s*\{[\s\S]*?position:\s*sticky/);
    assert.match(css, /\.story-section\s*\{[\s\S]*?top:\s*var\(--story-top\)/);
    assert.match(css, /min-height:\s*calc\(100svh\s*-\s*var\(--story-top\)\)/);
    assert.match(css, /opacity:\s*var\(--story-opacity,\s*1\)/);
    assert.match(css, /transform:\s*translateY\(var\(--story-shift,\s*0px\)\)\s*scale\(var\(--story-scale,\s*1\)\)/);
    assert.match(js, /function\s+smootherStep/);
    assert.match(js, /style\.setProperty\("--story-opacity"/);
    assert.match(js, /scrollToStorySection/);
    assert.doesNotMatch(js, /addEventListener\(["'](?:wheel|touchmove)["']/);
    assert.doesNotMatch(css, /scroll-snap-type:\s*y\s+mandatory/);
  });

  it("keeps section navigation state synchronized by section id", () => {
    const html = read("index.html");
    const js = read("src/main.js");

    for (const sectionId of ["overview", "execution", "turn-ui", "local-boundaries", "good-fits", "docs"]) {
      assert.match(html, new RegExp(`<section[^>]+id="${escapeRegExp(sectionId)}"[^>]+story-section`));
      assert.match(html, new RegExp(`<a[^>]+href="#${escapeRegExp(sectionId)}"[^>]+data-section-nav`));
    }

    assert.match(js, /setActiveSection/);
    assert.match(js, /aria-current/);
    assert.match(js, /data-section-nav/);
    assert.match(js, /IntersectionObserver/);
  });

  it("uses semantic lane glyphs that match SemanticGlyphSet.java safe Unicode", () => {
    const js = read("src/main.js");
    for (const glyph of ["•", "→", "✓", "!", "│", "┌", "└"]) {
      assert.ok(js.includes(glyph), `lane glyph ${glyph} missing from main.js`);
    }
    assert.ok(!js.includes("◐"), "main.js uses ◐ which is not part of SemanticGlyphSet");
    assert.ok(!js.includes("╭"), "main.js uses rounded answer pane glyphs not shipped by SemanticGlyphSet");
    assert.ok(!js.includes("╰"), "main.js uses rounded answer pane glyphs not shipped by SemanticGlyphSet");
    assert.match(js, /approval required/);
    assert.match(js, /talos.*\[auto\]\s*&gt;|talos.*\[auto\]\s*>/);
  });

  it("keeps vanilla JavaScript behavior for tabs and scroll state", () => {
    const js = read("src/main.js");
    assert.match(js, /terminalStates/);
    assert.match(js, /ArrowRight/);
    assert.match(js, /ArrowLeft/);
    assert.match(js, /Home/);
    assert.match(js, /End/);
    assert.doesNotMatch(js, /data-beta-placeholder/);
    assert.doesNotMatch(js, /Beta download placeholder/);
    assert.doesNotMatch(js, /React|Vue|createApp|tailwind/i);
  });
});

describe("Talos in-site documentation contract", () => {
  const userDocSlugs = [
    "index",
    "quickstart",
    "installation",
    "model-setup",
    "first-run",
    "workspaces-and-indexing",
    "how-talos-works",
    "approvals-and-permissions",
    "local-privacy-and-artifacts",
    "file-support",
    "commands",
    "troubleshooting",
    "release-channels",
  ];

  it("ships every user doc Markdown source needed by the docs page", () => {
    const docsRoot = join(root, "..", "docs", "user");
    for (const slug of userDocSlugs) {
      const path = join(docsRoot, `${slug}.md`);
      assert.ok(existsSync(path), `missing docs/user/${slug}.md`);
      const body = readFileSync(path, "utf8");
      assert.match(body, /^#\s+/m, `docs/user/${slug}.md missing h1`);
      assert.doesNotMatch(body, /<!--|-->/, `docs/user/${slug}.md leaks HTML comments`);
      assert.doesNotMatch(body, /\bT\d{3,}\b/, `docs/user/${slug}.md leaks ticket ids`);
      assert.doesNotMatch(body, /work-cycle-docs|tickets\/(?:open|done)/i, `docs/user/${slug}.md leaks internal docs`);
    }
  });

  it("registers docs.html as a Vite page without changing the landing entry", () => {
    const config = read("vite.config.js");
    assert.match(config, /input\s*:\s*\{/);
    assert.match(config, /main\s*:\s*resolve\([^)]*"index\.html"/);
    assert.match(config, /docs\s*:\s*resolve\([^)]*"docs\.html"/);
    assert.match(config, /fs:\s*\{[\s\S]*allow:/);
  });

  it("provides a standalone docs page with grouped navigation and article shell", () => {
    const html = read("docs.html");
    assert.match(html, /<title>Talos documentation/);
    assert.match(html, /<main id="main" class="docs-main">/);
    assert.match(html, /id="docs-article"/);
    assert.match(html, /type="module"\s+src="\/src\/docs\.js"/);
    for (const group of ["Get Started", "Guides", "Reference", "Concepts"]) {
      assert.match(html, new RegExp(`>${escapeRegExp(group)}<`));
    }
    for (const slug of userDocSlugs.filter((slug) => slug !== "index")) {
      assert.match(html, new RegExp(`href="#/${escapeRegExp(slug)}"`), `missing #/${slug} docs route`);
      assert.match(html, new RegExp(`data-doc-slug="${escapeRegExp(slug)}"`), `missing ${slug} nav state`);
    }
  });

  it("renders docs from Markdown sources with a small trusted renderer", () => {
    const js = read("src/docs.js");
    assert.match(js, /import\.meta\.glob\(\s*"\.\.\/\.\.\/docs\/user\/\*\.md"/);
    assert.match(js, /query:\s*"\?raw"/);
    assert.match(js, /function renderMarkdown/);
    assert.match(js, /function escapeHtml/);
    assert.match(js, /docs-table/);
    assert.match(js, /docs-code/);
    assert.match(js, /hashchange/);
    assert.doesNotMatch(js, /React|Vue|createApp|tailwind/i);
  });

  it("links the landing docs cards into the in-site docs experience", () => {
    const html = read("index.html");
    const docs = sectionSlice(html, "docs", null);
    assert.match(docs, /href="\.\/docs\.html"/);
    for (const route of [
      "./docs.html#/quickstart",
      "./docs.html#/model-setup",
      "./docs.html#/approvals-and-permissions",
      "./docs.html#/how-talos-works",
    ]) {
      assert.match(docs, new RegExp(`href="${escapeRegExp(route)}"`));
    }
    assert.doesNotMatch(docs, /github\.com\/ai21z\/talos-cli\/blob\/v0\.9\.0-beta-dev\/docs\/architecture/);
  });

  it("does not publish unsupported install or capability claims in docs surface", () => {
    const surface = [read("docs.html"), read("src/docs.js"), ...userDocSlugs.map((slug) => readFileSync(join(root, "..", "docs", "user", `${slug}.md`), "utf8"))].join("\n");
    for (const banned of [
      "winget install works now",
      "Linux public install is supported",
      "macOS public install is supported",
      "bundled models",
      "bundled llama.cpp",
      "GitHub Wiki",
      "Talos browses the web",
      "PowerPoint is supported",
    ]) {
      assert.doesNotMatch(surface, new RegExp(escapeRegExp(banned), "i"));
    }
  });
});
