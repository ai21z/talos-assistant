import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const read = (path) => readFileSync(join(root, path), "utf8");
const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const publicFiles = ["index.html", "docs.html", "src/main.js", "src/styles.css"];
const publicText = () => publicFiles.map(read).join("\n");
const currentPublicName = "Aris Zounarakis";
const currentPublicEmail = "aris@zounarakis.com";
const oldPublicName = `${"Viss"}${"arion"} Zounarakis`;
const oldPublicEmail = `${"viss"}${"arion"}@zounarakis.com`;
const oldPublicIdentityPatterns = [
  new RegExp(escapeRegExp(oldPublicName), "i"),
  new RegExp(escapeRegExp(oldPublicEmail), "i"),
];

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

function userDocSlugsFromDisk() {
  const docsRoot = join(root, "..", "docs", "user");
  return walkFiles(docsRoot)
    .filter((file) => file.endsWith(".md"))
    .map((file) => relative(docsRoot, file).replaceAll("\\", "/").replace(/\.md$/, ""))
    .sort();
}

function markdownLinks(md) {
  return Array.from(md.matchAll(/\[[^\]]+\]\(([^)]+)\)/g), (match) => match[1].trim());
}

function linkedDocSlug(sourceSlug, href) {
  const hrefWithoutAnchor = href.split("#")[0].trim();
  if (!hrefWithoutAnchor || hrefWithoutAnchor.startsWith("#")) return null;
  if (/^[a-z][a-z0-9+.-]*:/i.test(hrefWithoutAnchor)) return null;
  if (!hrefWithoutAnchor.endsWith(".md")) return null;

  const parts = sourceSlug.split("/").slice(0, -1);
  for (const part of hrefWithoutAnchor.split("/")) {
    if (part === "." || part === "") continue;
    if (part === "..") {
      parts.pop();
    } else {
      parts.push(part);
    }
  }
  return parts.join("/").replace(/\.md$/, "");
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

function cssRule(css, selector) {
  const pattern = new RegExp(`${escapeRegExp(selector)}\\s*\\{([\\s\\S]*?)\\}`);
  const match = css.match(pattern);
  assert.ok(match, `missing CSS rule for ${selector}`);
  return match[1];
}

function cssVar(css, name) {
  const match = css.match(new RegExp(`${escapeRegExp(name)}\\s*:\\s*(#[0-9a-f]{6})`, "i"));
  assert.ok(match, `missing CSS variable ${name}`);
  return match[1];
}

function contrastRatio(foreground, background) {
  const luminance = (hex) => {
    const value = hex.replace("#", "");
    const [r, g, b] = [0, 2, 4].map((offset) => parseInt(value.slice(offset, offset + 2), 16) / 255);
    const linear = (channel) => channel <= 0.03928
      ? channel / 12.92
      : ((channel + 0.055) / 1.055) ** 2.4;
    return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
  };
  const [lighter, darker] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (lighter + 0.05) / (darker + 0.05);
}

describe("Talos landing page static contract", () => {
  it("uses the final site package name and required scripts", () => {
    const pkg = JSON.parse(read("package.json"));
    assert.equal(pkg.name, "talos-site");
    assert.equal(pkg.scripts.dev, "vite");
    assert.equal(pkg.scripts.build, "vite build");
    assert.equal(pkg.scripts.preview, "vite preview");
    assert.equal(pkg.scripts.test, "npm run test:static");
    assert.equal(pkg.scripts["test:static"], "node --test test/site.test.js test/docs-renderer.test.js");
    assert.equal(pkg.scripts["test:deploy-surface"], "node --test test/deploy-surface.test.js");
    assert.equal(pkg.scripts["test:e2e"], "playwright test");
  });

  it("keeps production source maps disabled and emits no .map files after build", () => {
    assert.match(read("vite.config.js"), /sourcemap:\s*false/);
    const mapFiles = walkFiles(join(root, "dist")).filter((file) => file.endsWith(".map"));
    assert.deepEqual(mapFiles, []);
  });

  it("runs the deploy-surface leak check after building the staged site", () => {
    const workflow = readFileSync(join(root, "..", ".github", "workflows", "release-staging.yml"), "utf8");
    const buildIndex = workflow.indexOf("npm run build --prefix site");
    const leakCheckIndex = workflow.indexOf("npm run test:deploy-surface --prefix site");

    assert.ok(buildIndex >= 0, "release staging must build the site");
    assert.ok(leakCheckIndex > buildIndex, "release staging must scan site/dist after building");
  });

  it("isolates Playwright preview evidence from unrelated local servers", () => {
    const index = read("index.html");
    const docs = read("docs.html");
    const config = read("playwright.config.js");
    const e2e = read("test/e2e/site.spec.js");

    assert.match(index, /<body\b[^>]*\bdata-talos-site="landing"/);
    assert.match(docs, /<body\b[^>]*\bdata-talos-site="docs"/);
    assert.match(config, /TALOS_SITE_E2E_PORT/);
    assert.match(config, /TALOS_SITE_E2E_BASE_URL/);
    assert.match(config, /TALOS_SITE_E2E_SKIP_WEBSERVER/);
    assert.match(config, /--strictPort/);
    assert.match(config, /reuseExistingServer:\s*false/);
    assert.doesNotMatch(config, /reuseExistingServer:\s*!process\.env\.CI/);
    assert.doesNotMatch(config, /127\.0\.0\.1:4173/);
    assert.match(e2e, /expectTalosPage/);
    assert.match(e2e, /data-talos-site/);
  });

  it("publishes the current owner identity on public site and install surfaces", () => {
    const publicSurfaces = {
      "site/index.html": read("index.html"),
      "site/docs.html": read("docs.html"),
      "README.md": readFileSync(join(root, "..", "README.md"), "utf8"),
      "docs/public-installation.md": readFileSync(join(root, "..", "docs", "public-installation.md"), "utf8"),
      "docs/user/installation.md": readFileSync(join(root, "..", "docs", "user", "installation.md"), "utf8"),
    };

    for (const [file, body] of Object.entries(publicSurfaces)) {
      assert.doesNotMatch(body, oldPublicIdentityPatterns[0], `${file} still publishes the old owner name`);
      assert.doesNotMatch(body, oldPublicIdentityPatterns[1], `${file} still publishes the old owner email`);
    }

    for (const file of ["site/index.html", "site/docs.html"]) {
      assert.match(publicSurfaces[file], new RegExp(escapeRegExp(currentPublicName)), `${file} missing current owner name`);
      assert.match(publicSurfaces[file], new RegExp(escapeRegExp(currentPublicEmail)), `${file} missing current owner email`);
    }

    for (const file of ["README.md", "docs/public-installation.md", "docs/user/installation.md"]) {
      assert.match(publicSurfaces[file], new RegExp(escapeRegExp(currentPublicName)), `${file} missing current publisher name`);
    }
  });

  it("uses one product-specific h1 grounded in Talos trust evidence", () => {
    const html = read("index.html");
    const h1Matches = Array.from(html.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/gi));
    assert.equal(h1Matches.length, 1);
    const h1Text = h1Matches[0][1].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    assert.equal(h1Text, "The local CLI that verifies before it claims success.");
    assert.doesNotMatch(h1Text, /operator/i);
    assert.notEqual(h1Text.toUpperCase(), "TALOS");
  });

  it("uses a decisive sticky header and readable trust accent colors", () => {
    const css = read("src/styles.css");
    const headerRule = cssRule(css, ".site-header");
    const headerAlpha = Number(headerRule.match(/background:\s*rgba\(\s*8,\s*9,\s*11,\s*([0-9.]+)\s*\)/)?.[1]);
    assert.ok(headerAlpha >= 0.9, `site header alpha ${headerAlpha} is too transparent`);
    assert.match(headerRule, /box-shadow:/, "sticky header needs a separation shadow over scrolled content");

    const background = cssVar(css, "--bg");
    assert.ok(contrastRatio(cssVar(css, "--red"), background) >= 4.5, "--red must be readable on the page background");
    assert.match(cssRule(css, ".state--deny"), /background:\s*rgba\(\s*215,\s*95,\s*95,\s*0\.(?:1[2-9]|[2-9]\d)\s*\)/);
    assert.match(cssRule(css, ".t-rail"), /color:\s*var\(--bronze\)/);
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
    const setupTabs = Array.from(hero.matchAll(/class="setup-tab"/g));

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
      "TalosLocal.Talos",
      "Windows",
      "Linux",
      "curl -fsSL https://taloslocal.com/install.sh | sh",
      "Install commands go live when the first GitHub Release assets are published",
      "setup wizard then guides model and llama.cpp configuration",
    ]) {
      assert.match(hero, new RegExp(escapeRegExp(copy), "i"));
    }

    assert.equal(setupTabs.length, 2, "hero install preview should expose exactly two platform tabs");
    assert.doesNotMatch(hero, /Get beta build/i);
    assert.doesNotMatch(hero, /data-beta-placeholder/i);
    assert.doesNotMatch(hero, /data-copy=/i);
    assert.doesNotMatch(hero, /talos setup models/i);
    assert.doesNotMatch(hero, /talos status --verbose/i);
    assert.doesNotMatch(hero, /TalosProject\.TalosCLI/i);
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

  it("leads the hero with the TALOS wordmark and a cycling acrostic word", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const js = read("src/main.js");
    const hero = sectionSlice(html, "overview", "execution");
    const publicSurface = publicText();

    // English TALOS wordmark replaces the former Greek inscription.
    assert.match(hero, /<span\s+class="hero-inscription-mark">\s*TALOS\s*<\/span>/);
    // No leftover Greek wordmark, and no mixed Latin/Greek letterforms.
    assert.doesNotMatch(publicSurface, /ΤΑΛΩΣ|TAΛOS|TALΩS|TAΛΩS/);
    // Rule plus the cycling acrostic slot, driven from main.js.
    assert.match(hero, /class="hero-inscription-rule"/);
    assert.match(hero, /data-inscription-cycle/);
    assert.match(js, /function setupInscription/);
    for (const word of ["TRACED", "APPROVED", "LOCAL", "OBSERVABLE", "SCOPED"]) {
      assert.match(js, new RegExp(`"${word}"`));
    }
    // Honest subtitle replaces the old "bronze guardian" tag.
    assert.match(hero, /hero-inscription-sub/);
    assert.doesNotMatch(publicSurface, /bronze guardian/i);
    // Styling hooks for the new inscription.
    assert.match(css, /\.hero-inscription-mark\s*\{/);
    assert.match(css, /\.hero-inscription-cycle\s*\{/);
    // Fonts stay self-hosted, no third-party font CDNs.
    assert.doesNotMatch(publicSurface, /fonts\.googleapis\.com|fonts\.gstatic\.com/);
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
      "TalosLocal.Talos",
      "curl -fsSL https://taloslocal.com/install.sh | sh",
      "Ubuntu/WSL x64 tarball target",
      "Install commands go live when the first GitHub Release assets are published",
      "setup wizard then guides model and llama.cpp configuration",
      "installation docs",
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
    assert.doesNotMatch(text, /TalosProject\.TalosCLI/i);
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
      assert.match(href, /^https:\/\/(github\.com\/ai21z\/talos-assistant|zounarakis\.com)/, `unexpected external href: ${href}`);
      assert.doesNotMatch(href, /github\.com\/ai21z\/talos-cli/, `stale GitHub repo href: ${href}`);
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

  it("keeps the smoke canvas transparent so scrolled sections cannot wash out", () => {
    const smoke = read("src/smoke.js");
    assert.match(smoke, /alpha:\s*true/);
    assert.doesNotMatch(smoke, /alpha:\s*false/);
    assert.match(smoke, /clearColor\(\s*0,\s*0,\s*0,\s*0\s*\)/);
    assert.match(smoke, /frag\s*=\s*vec4\(col,\s*alpha\)/);
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

  it("keeps desktop vein section labels hidden until scroll and unavailable on mobile", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const main = read("src/main.js");
    const railMatch = html.match(/<nav class="vein-rail"[\s\S]*?<\/nav>/);
    assert.ok(railMatch, "missing vein rail");

    for (const label of ["Overview", "Execution", "Turn UI", "Local Boundaries", "Good Fits", "Docs"]) {
      assert.match(railMatch[0], new RegExp(`<span class="vein-stop-label">${escapeRegExp(label)}</span>`));
    }

    assert.match(main, /has-scrolled/);
    assert.match(main, /VEIN_LABEL_REVEAL_Y/);
    assert.match(css, /\.vein-rail\.has-scrolled\s+\.vein-stop-label/);
    assert.match(css, /\.vein-rail\.has-scrolled\s+\.vein-stop(?:\.is-active|\[aria-current="page"\])\s+\.vein-stop-label/);
    assert.match(css, /@media\s*\(max-width:\s*920px\)\s*\{[\s\S]*?\.vein-rail\s*\{\s*display:\s*none/);
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
  const sidebarDocSlugs = [
    "index",
    "quickstart",
    "installation",
    "model-setup",
    "first-run",
    "workspaces-and-indexing",
    "retrieval-and-vectors",
    "beta-best-practices",
    "how-talos-works",
    "approvals-and-permissions",
    "local-privacy-and-artifacts",
    "file-support",
    "commands",
    "troubleshooting",
    "release-channels",
  ];
  const userDocSlugs = userDocSlugsFromDisk();

  it("ships every public user doc Markdown source needed by the docs page", () => {
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

  it("keeps every public user doc routable or explicitly excluded", () => {
    assert.deepEqual(userDocSlugs, [
      "approvals-and-permissions",
      "beta-best-practices",
      "commands",
      "file-support",
      "first-run",
      "how-talos-works",
      "index",
      "installation",
      "local-privacy-and-artifacts",
      "model-profiles/deepseek-v2lite-q4km",
      "model-profiles/gpt-oss-20b",
      "model-profiles/qwen2.5-coder-14b",
      "model-profiles/qwen36vf-q4km",
      "model-profiles/qwen36vf-q6k",
      "model-setup",
      "quickstart",
      "release-channels",
      "retrieval-and-vectors",
      "troubleshooting",
      "workspaces-and-indexing",
    ]);

    const js = read("src/docs.js");
    assert.match(js, /import\.meta\.glob\(\s*"\.\.\/\.\.\/docs\/user\/\*\*\/\*\.md"/);
    assert.match(js, /docs\/user\//);
    assert.match(js, /replace\(\/\\\.md\$\//);
  });

  it("resolves internal Markdown links to routable docs pages", () => {
    const docsRoot = join(root, "..", "docs", "user");
    const routedSlugs = new Set(userDocSlugs);
    const missing = [];

    for (const sourceSlug of userDocSlugs) {
      const body = readFileSync(join(docsRoot, `${sourceSlug}.md`), "utf8");
      for (const href of markdownLinks(body)) {
        const targetSlug = linkedDocSlug(sourceSlug, href);
        if (targetSlug && !routedSlugs.has(targetSlug)) {
          missing.push(`docs/user/${sourceSlug}.md -> ${href} (${targetSlug})`);
        }
      }
    }

    assert.deepEqual(missing, []);
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
    assert.match(html, /data-docs-fallback/);
    assert.match(html, /Loading bundled documentation/);
    assert.match(html, /source Markdown on GitHub/);
    assert.match(html, /type="module"\s+src="\/src\/docs\.js"/);
    for (const group of ["Get Started", "Guides", "Reference", "Concepts"]) {
      assert.match(html, new RegExp(`>${escapeRegExp(group)}<`));
    }
    for (const slug of sidebarDocSlugs.filter((slug) => slug !== "index")) {
      assert.match(html, new RegExp(`href="#/${escapeRegExp(slug)}"`), `missing #/${slug} docs route`);
      assert.match(html, new RegExp(`data-doc-slug="${escapeRegExp(slug)}"`), `missing ${slug} nav state`);
    }
  });

  it("renders docs from Markdown sources with a small trusted renderer", () => {
    const js = read("src/docs.js");
    const renderer = read("src/docs-markdown.js");
    assert.match(js, /import\.meta\.glob\(\s*"\.\.\/\.\.\/docs\/user\/\*\*\/\*\.md"/);
    assert.match(js, /from "\.\/docs-markdown\.js"/);
    assert.match(js, /query:\s*"\?raw"/);
    assert.match(renderer, /export function renderMarkdown/);
    assert.match(renderer, /export function escapeHtml/);
    assert.match(renderer, /collectListItems/);
    assert.match(renderer, /docs-table/);
    assert.match(renderer, /docs-code/);
    assert.match(renderer, /docs-copy/);
    assert.match(js, /hashchange/);
    assert.doesNotMatch(js, /React|Vue|createApp|tailwind/i);
    assert.doesNotMatch(renderer, /React|Vue|createApp|tailwind/i);
  });

  it("curates the docs landing with every top-level guide linked from docs/user/index.md", () => {
    const js = read("src/docs.js");
    for (const slug of [
      "quickstart",
      "installation",
      "model-setup",
      "first-run",
      "workspaces-and-indexing",
      "retrieval-and-vectors",
      "beta-best-practices",
      "how-talos-works",
      "approvals-and-permissions",
      "local-privacy-and-artifacts",
      "file-support",
      "commands",
      "troubleshooting",
      "release-channels",
    ]) {
      assert.match(js, new RegExp(`\\["[^"]+",\\s*"${escapeRegExp(slug)}"`), `docs landing missing ${slug}`);
    }
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

  it("tells direct model-setup users where to get a compatible llama-server", () => {
    const docsRoot = join(root, "..", "docs", "user");
    const surface = ["quickstart", "installation", "model-setup", "troubleshooting"]
      .map((slug) => readFileSync(join(docsRoot, `${slug}.md`), "utf8"))
      .join("\n")
      .replace(/\s+/g, " ");

    for (const required of [
      "Where to get `llama-server`",
      "https://github.com/ggml-org/llama.cpp/releases/tag/b9860",
      "llama-b9860-bin-ubuntu-x64.tar.gz",
      "Ubuntu x64 (CPU)",
      "Windows x64 (CPU)",
      "talos setup wizard",
      "talos setup models",
      "Talos does not claim arbitrary latest upstream builds are verified",
    ]) {
      assert.match(surface, new RegExp(escapeRegExp(required), "i"));
    }
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

  it("README points users to the user-docs entry point", () => {
    const readme = readFileSync(join(root, "..", "README.md"), "utf8");
    assert.match(readme, /docs\/user\/index\.md/);
    assert.match(readme, /docs\/user\/quickstart\.md/);
    assert.match(readme, /docs\/public-installation\.md/);
  });
});
