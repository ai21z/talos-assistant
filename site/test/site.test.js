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

  it("uses the six-section map with reduced navigation labels", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const navMatch = html.match(/<nav\b[^>]*id="primary-navigation"[\s\S]*?<\/nav>/);
    assert.ok(navMatch, "missing #primary-navigation nav");
    const nav = navMatch[0];
    const main = html.slice(html.indexOf('id="main"'));
    const sections = Array.from(main.matchAll(/<section\b[^>]*\bid="([^"]+)"/g), (m) => m[1]);

    assert.deepEqual(sections, [
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

  it("renders the real Talos shield icon as the brand mark", () => {
    const html = read("index.html");
    assert.ok(existsSync(join(root, "design", "talos-icon.png")), "talos-icon.png missing");
    // The real shield emblem badges the header and footer wordmarks.
    assert.equal(
      (html.match(/<img class="brand-icon"[^>]*src="\.\/design\/talos-icon-64\.png"/g) ?? []).length,
      2,
    );
    // Favicon uses the same real icon.
    assert.match(html, /rel="icon"\s+type="image\/png"\s+href="\.\/design\/talos-icon-64\.png"/);
    // Brand marks are decorative (empty alt) inside aria-hidden wrappers.
    assert.match(html, /<img class="brand-icon"[^>]*alt=""/);
  });

  it("leads the hero with a live, replayable terminal carrying the current version", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const js = read("src/main.js");
    const hero = sectionSlice(html, "overview", "execution");
    const heroText = hero.replace(/\s+/g, " ");

    // Live terminal scaffold and replay control.
    assert.match(hero, /data-live-terminal/);
    assert.match(hero, /<pre[^>]*class="terminal-screen"[^>]*data-live-output/);
    assert.match(hero, /data-live-replay/);
    assert.match(js, /function setupLiveTerminal/);
    assert.match(js, /IntersectionObserver/);

    // The hero proof is rendered DOM, not a baked, version-locked screenshot.
    assert.doesNotMatch(hero, /<img\b[^>]*class="startup-terminal-image"/);
    assert.doesNotMatch(hero, /design\/img\.png/);

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

    assert.match(css, /\.terminal\b/);
    assert.match(css, /\.terminal-screen\b/);
    assert.match(css, /\.terminal-replay\b/);
  });

  it("keeps the Greek identity as a single restrained inscription accent", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const fonts = read("src/fonts.css");
    const pkg = JSON.parse(read("package.json"));
    const hero = sectionSlice(html, "overview", "execution");
    const publicSurface = publicText();

    assert.ok(pkg.devDependencies["@fontsource/gfs-neohellenic"], "missing self-hosted GFS Neohellenic package");
    assert.match(fonts, /gfs-neohellenic-greek-700-normal\.woff2/);
    assert.match(hero, /<span\s+class="hero-inscription-greek"\s+lang="el">\s*ΤΑΛΩΣ\s*<\/span>/);
    assert.equal((publicSurface.match(/ΤΑΛΩΣ/g) ?? []).length, 1);
    assert.doesNotMatch(publicSurface, /TAΛOS|TALΩS|TAΛΩS/);
    assert.doesNotMatch(publicSurface, /fonts\.googleapis\.com|fonts\.gstatic\.com/);
    assert.match(css, /\.hero-inscription-greek\s*\{[\s\S]*?font-family:\s*"GFS Neohellenic"/);
    assert.match(css, /\.hero-inscription-greek\s*\{[\s\S]*?text-shadow/);
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
      "Defined protected paths",
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
      "Every turn runs the same six stations",
      "One ordered flow. No skipped steps.",
      "keeps every turn on your machine",
      "The model cannot bypass them by rewording the request",
      "install now with winget",
      "Linux public beta",
      "macOS public beta",
      "bundled models",
      "bundled llama.cpp",
    ]) {
      assert.doesNotMatch(text, new RegExp(escapeRegExp(tooAbsolute), "i"));
    }
    assert.match(text, /localhost-gated by default/i);
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
      // GitHub repo links, plus the author's own site in the footer signature.
      assert.match(href, /^https:\/\/(github\.com\/ai21z\/talos-cli|zounarakis\.com)/, `unexpected external href: ${href}`);
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

  it("ships social share metadata and a png favicon", () => {
    const html = read("index.html");
    assert.match(html, /<meta\s+name="theme-color"/i);
    assert.match(html, /<meta\s+property="og:title"/i);
    assert.match(html, /<meta\s+property="og:description"/i);
    assert.match(html, /<meta\s+name="twitter:card"\s+content="summary_large_image"/i);
    assert.match(html, /rel="icon"\s+type="image\/png"\s+href="\.\/design\/talos-icon-64\.png"/);
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
  });

  it("uses native scroll with reveal-on-scroll and no scroll hijacking", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const js = read("src/main.js");

    // The old sticky full-viewport story-blend mechanic is gone.
    assert.doesNotMatch(html, /\bstory-section\b/);
    assert.doesNotMatch(css, /\bstory-section\b/);
    assert.doesNotMatch(css, /--story-opacity|--story-shift|--story-scale/);
    assert.doesNotMatch(js, /smootherStep|scrollToStorySection|--story-opacity/);

    // No scroll hijacking of any kind.
    assert.doesNotMatch(js, /addEventListener\(["'](?:wheel|touchmove)["']/);
    assert.doesNotMatch(css, /scroll-snap-type:\s*y\s+mandatory/);

    // Native scroll-spy + reveal driven by IntersectionObserver.
    assert.match(js, /function setupSectionNav/);
    assert.match(js, /function setupReveal/);
    assert.match(js, /IntersectionObserver/);
    assert.match(js, /aria-current/);
    assert.match(js, /data-section-nav/);
    assert.match(css, /\.js\s+\.reveal\s*\{[\s\S]*?opacity:\s*0/);
    assert.match(css, /\.reveal--visible\s*\{[\s\S]*?opacity:\s*1/);
  });

  it("keeps motion accessible under reduced-motion preferences", () => {
    const css = read("src/styles.css");
    assert.match(css, /scroll-margin-top/);
    assert.match(css, /prefers-reduced-motion:\s*reduce/);
    // Reduced motion forces revealed content visible and tames animation.
    assert.match(css, /prefers-reduced-motion:\s*reduce[\s\S]*\.js\s+\.reveal\s*\{[\s\S]*?opacity:\s*1/);
    assert.match(css, /prefers-reduced-motion:\s*reduce[\s\S]*animation-duration:\s*0\.01ms\s*!important/);
    assert.match(css, /prefers-reduced-motion:\s*reduce[\s\S]*transition-duration:\s*0\.01ms\s*!important/);
    // Staggered groups and the cursor sheen also stand down under reduced motion.
    assert.match(css, /prefers-reduced-motion:\s*reduce[\s\S]*\[data-reveal-stagger\]\s*>\s*\*\s*\{[\s\S]*?opacity:\s*1/);
    assert.match(css, /prefers-reduced-motion:\s*reduce[\s\S]*\.sheen\s*\{[\s\S]*?display:\s*none/);
  });

  it("renders a film-grain texture overlay for tactile depth", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    assert.match(html, /<div class="grain" aria-hidden="true">/);
    assert.match(css, /\.grain\s*\{[\s\S]*?pointer-events:\s*none/);
    assert.match(css, /\.grain\s*\{[\s\S]*?mix-blend-mode/);
  });

  it("renders persistent scroll-reactive smoke and mycelium backgrounds", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const main = read("src/main.js");
    const smoke = read("src/smoke.js");
    const mycelium = read("src/mycelium.js");
    // Two fixed full-page canvases, not hero-only elements.
    assert.match(html, /<canvas class="smoke" aria-hidden="true">/);
    assert.match(html, /<canvas class="mycelium" aria-hidden="true">/);
    assert.doesNotMatch(html, /hero-canvas|hero-glow/);
    assert.match(css, /\.smoke,\s*\.mycelium\s*\{[\s\S]*?position:\s*fixed/);
    // The divider lines between sections are gone. Continuity, not chops.
    assert.doesNotMatch(css, /\.section\s*\+\s*\.section/);
    // Wired in. Smoke is WebGL2 and scroll-reactive; mycelium is the canvas-2D layer.
    assert.match(main, /setupSmoke\(\)/);
    assert.match(main, /setupMycelium\(\)/);
    assert.match(smoke, /function setupSmoke/);
    assert.match(smoke, /webgl2/);
    assert.match(smoke, /uScroll/);
    assert.match(mycelium, /function setupMycelium/);
    // Both freeze under reduced motion and remove themselves on a silent fallback.
    assert.match(smoke, /reduce\.matches/);
    assert.match(smoke, /canvas\.remove\(\)/);
    assert.match(mycelium, /reduce\.matches/);
    assert.match(mycelium, /canvas\.remove\(\)/);
  });

  it("renders the ichor vein and its vigil nail", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const main = read("src/main.js");
    assert.match(html, /<div class="vein" aria-hidden="true">/);
    assert.match(html, /class="vein-fill"/);
    assert.match(html, /class="vein-node"/);
    assert.match(html, /class="vein-nail"/);
    assert.match(css, /\.vein\s*\{[\s\S]*?position:\s*fixed/);
    assert.match(css, /\.vein\s*\{[\s\S]*?--ichor:\s*0/);
    assert.match(css, /\.vein-fill\s*\{[\s\S]*?calc\(var\(--ichor\)/);
    // Scroll -> fill is a pure deterministic mapping, not an animation.
    assert.match(main, /function setupVein/);
    assert.match(main, /setProperty\("--ichor"/);
  });

  it("stages a skippable, fail-safe cold-boot awakening", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const main = read("src/main.js");
    // Overlay + the real Talos shield icon it forges in.
    assert.match(html, /<div class="awaken" aria-hidden="true">/);
    assert.match(html, /<img class="awaken-shield"[^>]*src="\.\/design\/talos-icon-256\.png"/);
    // Inline head gate skips automation, reduced-motion, and already-seen.
    assert.match(html, /navigator\.webdriver/);
    assert.match(html, /talosAwoke/);
    assert.match(html, /prefers-reduced-motion/);
    // Failsafe: content can NEVER stay hidden even if the module fails.
    assert.match(html, /classList\.remove\("awakening"\)/);
    // Default-hidden, only shown under html.awakening. Never traps content.
    assert.match(html, /\.awaken\s*\{\s*display:\s*none/);
    assert.match(html, /html\.awakening\s+\.awaken\s*\{\s*display:\s*grid/);
    assert.match(css, /\.awaken\s*\{[\s\S]*?position:\s*fixed/);
    assert.match(css, /\.awaken\s*\{[\s\S]*?z-index:\s*9999/);
    // Plays, sets the once-per-session flag, and is skippable.
    assert.match(main, /function setupAwakening/);
    assert.match(main, /sessionStorage\.setItem\("talosAwoke"/);
    assert.match(main, /"click",\s*"keydown",\s*"wheel",\s*"touchstart"/);
  });

  it("renders a restrained guardian parallax accent and a classical key frieze", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const main = read("src/main.js");
    // One fully-composed guardian accent in the hero (not an edge-cut watermark).
    assert.match(html, /<div class="guardian-parallax"[^>]*>\s*<img[^>]*talos-icon-256\.png/);
    // The half-cut boundary watermark and the footer emblem were removed.
    assert.doesNotMatch(html, /class="guardian-watermark"|class="footer-guardian"/);
    // Screen blend drops the dark tile; stays faint; drifts on scroll.
    assert.match(css, /\.guardian-parallax\s*\{[\s\S]*?mix-blend-mode:\s*screen/);
    assert.match(css, /\.guardian-parallax\s*\{[\s\S]*?opacity:\s*0\.[01][0-9]/);
    assert.match(css, /\.guardian-parallax\s*\{[\s\S]*?var\(--par/);
    assert.match(main, /function setupParallax/);
    // Classical key frieze still closes the footer.
    assert.match(css, /\.site-footer::before\s*\{[\s\S]*?data:image\/svg\+xml/);
  });

  it("uses a choreographed staggered reveal with a cursor sheen", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const js = read("src/main.js");
    assert.equal((html.match(/data-reveal-stagger/g) ?? []).length, 2);
    assert.match(js, /function setupStagger/);
    assert.match(js, /function setupPointerSheen/);
    assert.match(js, /--reveal-delay/);
    assert.match(js, /has-sheen/);
    assert.match(js, /pointer:\s*coarse/); // sheen skipped on touch
    assert.match(css, /\[data-reveal-stagger\]\s*>\s*\*\s*\{[\s\S]*?transition-delay:\s*var\(--reveal-delay/);
    assert.match(css, /\.sheen\s*\{[\s\S]*?pointer-events:\s*none/);
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
    assert.ok(js.includes("[auto]") && js.includes("&gt;"), "prompt should render talos [auto] > grammar");
  });

  it("keeps vanilla JavaScript behavior for tabs and the live terminal", () => {
    const js = read("src/main.js");
    assert.match(js, /terminalStates/);
    assert.match(js, /function setupTurnTabs/);
    assert.match(js, /function setupLiveTerminal/);
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
    assert.match(js, /docs-copy/);
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
      "Windows-first",
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
