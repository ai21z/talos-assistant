import "./styles.css";
import { setupRitualMenu } from "./menu.js";
import { escapeHtml, renderMarkdown } from "./docs-markdown.js";

document.documentElement.classList.add("js");
setupRitualMenu();

// Import all maintained docs as raw strings at build time. The path is relative to
// this file: site/src -> ../../docs. Vite resolves the glob and inlines
// content into the bundle (no runtime fetch, no path traversal at runtime).
const docModules = import.meta.glob("../../docs/**/*.md", {
  query: "?raw",
  import: "default",
  eager: true,
});

// Map slug -> raw markdown text. "index" becomes the docs landing page.
const docsBySlug = {};
const docsRootPrefix = "../../docs/";
for (const [path, raw] of Object.entries(docModules)) {
  const slug = path.startsWith(docsRootPrefix)
    ? path.slice(docsRootPrefix.length).replace(/\.md$/, "")
    : path.replace(/^.*\//, "").replace(/\.md$/, "");
  docsBySlug[slug] = raw;
}

// --- Routing --------------------------------------------------------------
const article = document.getElementById("docs-article");
const navLinks = Array.from(document.querySelectorAll("[data-doc-slug]"));
const STATUS_NOTE_HTML = `
<aside class="docs-callout docs-callout--beta" role="note">
  <p><strong>Beta status.</strong> Talos 0.10.8 installs from GitHub Release assets.
  Windows x64 is unsigned and requires <code>-AllowUnsigned</code>. Ubuntu/WSL x64
  uses the runtime-bundled installer and starts <code>talos setup wizard</code>.
  To upgrade, rerun the installer with <code>--force</code> and the pinned version.</p>
</aside>`;

function currentRoute() {
  const hash = window.location.hash.replace(/^#\/?/, "").trim();
  const anchorIndex = hash.indexOf("#");
  if (anchorIndex === -1) {
    return { slug: hash || "", anchor: "" };
  }
  return {
    slug: hash.slice(0, anchorIndex).trim(),
    anchor: hash.slice(anchorIndex + 1).trim(),
  };
}

function scrollToArticle(anchor = "") {
  if (anchor) {
    const target = document.getElementById(anchor);
    if (target) {
      target.scrollIntoView({ block: "start", behavior: "auto" });
      return;
    }
  }
  window.scrollTo({ top: 0, behavior: "auto" });
}

function setActiveLink(slug) {
  for (const link of navLinks) {
    const isActive = link.dataset.docSlug === slug;
    if (isActive) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  }
}

function renderRoute() {
  const { slug, anchor } = currentRoute();
  setActiveLink(slug);

  if (slug === "" || slug === "index") {
    article.innerHTML = renderLandingHtml();
    document.title = "Talos documentation | Local-first CLI workspace operator";
    scrollToArticle(anchor);
    return;
  }

  const md = docsBySlug[slug];
  if (!md) {
    article.innerHTML = `
<h1>Page not found</h1>
<p>The documentation page <code>${escapeHtml(slug)}</code> does not exist.</p>
<p><a href="#/">Return to the documentation overview</a>.</p>`;
    document.title = "Not found | Talos documentation";
    return;
  }

  article.innerHTML = renderMarkdown(md, { currentSlug: () => currentRoute().slug });
  const firstHeading = article.querySelector("h1");
  document.title = firstHeading
    ? `${firstHeading.textContent.trim()} | Talos documentation`
    : "Talos documentation";
  article.scrollTo?.({ top: 0 });
  article.parentElement?.scrollTo?.({ top: 0 });
  scrollToArticle(anchor);
}

function renderLandingHtml() {
  // The docs landing reuses content from docs/index.md but is laid out
  // as a curated start surface rather than a raw rendering.
  const cards = [
    {
      group: "Start here",
      items: [
        ["Installation", "getting-started/installation", "Windows, Ubuntu/WSL, upgrade, and source setup paths."],
        ["Quickstart", "getting-started/quickstart", "Check the command, configure a model, and start a first session."],
        ["First run", "getting-started/first-run", "Read the banner, prompt, and status output."],
        ["Model setup", "getting-started/model-setup", "Connect Talos to a local model engine."],
      ],
    },
    {
      group: "Using Talos",
      items: [
        ["Commands", "user/commands", "Top-level CLI and REPL slash commands."],
        ["Modes", "user/modes", "Ask, Plan, Agent, and Auto behavior."],
        ["Permissions and approvals", "user/permissions-and-approvals", "When Talos asks before acting."],
        ["Privacy and artifacts", "user/privacy-and-artifacts", "Private mode and local evidence."],
        ["Supported files", "user/supported-files", "Which file types are in beta scope."],
        ["Troubleshooting", "user/troubleshooting", "Diagnose install, model, and runtime issues."],
      ],
    },
    {
      group: "Reference",
      items: [
        ["CLI", "reference/cli", "Installed command reference."],
        ["Config", "reference/config", "Model, retrieval, host, and profile settings."],
        ["Model profiles", "reference/model-profiles", "Accepted and experimental local model profiles."],
        ["Release channels", "reference/release-channels", "Public release assets, QA staging, and upgrades."],
      ],
    },
    {
      group: "Architecture",
      items: [
        ["Overview", "architecture/overview", "The main execution spine."],
        ["Execution model", "architecture/execution-model", "Contract, posture, tools, approval, verification."],
        ["Trust boundaries", "architecture/trust-boundaries", "Local safety and evidence boundaries."],
        ["Package map", "architecture/package-map", "High-level Java package ownership."],
      ],
    },
    {
      group: "Development",
      items: [
        ["Developer setup", "development/developer-setup", "Local tools for source contributors."],
        ["Build and test", "development/build-and-test", "Local build and verification commands."],
        ["CI/CD", "development/ci-cd", "Current workflow and staging policy."],
        ["Quality reports", "development/quality-reports", "Local report outputs and review evidence."],
        ["Release process", "development/release-process", "Release QA and artifact boundaries."],
        ["Runtime artifacts", "development/runtime-artifacts", "Local traces, logs, and evidence sinks."],
      ],
    },
  ];

  const cardHtml = cards
    .map(
      (g) => `
<section class="docs-landing-group" aria-label="${escapeHtml(g.group)}">
  <h2>${escapeHtml(g.group)}</h2>
  <ul class="docs-landing-cards" role="list">
    ${g.items
      .map(
        ([title, slug, blurb]) => `
    <li>
      <a class="docs-landing-card" href="#/${slug}">
        <h3>${escapeHtml(title)}</h3>
        <p>${escapeHtml(blurb)}</p>
      </a>
    </li>`,
      )
      .join("")}
  </ul>
</section>`,
    )
    .join("\n");

  return `
<header class="docs-hero">
  <p class="eyebrow">Talos documentation</p>
  <h1>Start Talos on your machine.</h1>
  <p class="docs-lede">
    Use these docs to install Talos, connect a local model, learn the REPL,
    and check the evidence behind a turn. They describe the current beta state
    directly, including what is packaged and what remains a manual setup step.
  </p>
  <p class="docs-start-path">
    Recommended order:
    <a href="#/getting-started/quickstart">Quickstart</a>,
    <a href="#/getting-started/model-setup">model setup</a>, then
    <a href="#/getting-started/first-run">first run</a>.
  </p>
</header>
${STATUS_NOTE_HTML}
${cardHtml}`;
}

window.addEventListener("hashchange", renderRoute);
renderRoute();

// Copy-to-clipboard for rendered code blocks (delegated; survives re-render).
if (article && navigator.clipboard) {
  article.addEventListener("click", (event) => {
    const button = event.target.closest(".docs-copy");
    if (!button) return;
    const code = button.parentElement?.querySelector("code");
    if (!code) return;
    navigator.clipboard.writeText(code.textContent).then(() => {
      button.textContent = "Copied";
      button.dataset.copied = "true";
      window.setTimeout(() => {
        button.textContent = "Copy";
        delete button.dataset.copied;
      }, 1600);
    });
  });
}

// Mobile sidebar toggle
const sidebarToggle = document.querySelector(".docs-sidebar-toggle");
const sidebarNav = document.getElementById("docs-nav");
if (sidebarToggle && sidebarNav) {
  sidebarToggle.addEventListener("click", () => {
    const expanded = sidebarToggle.getAttribute("aria-expanded") === "true";
    sidebarToggle.setAttribute("aria-expanded", String(!expanded));
    sidebarNav.classList.toggle("docs-nav--open", !expanded);
  });
  // Close after a nav click on mobile.
  sidebarNav.addEventListener("click", (event) => {
    if (event.target instanceof HTMLAnchorElement) {
      sidebarToggle.setAttribute("aria-expanded", "false");
      sidebarNav.classList.remove("docs-nav--open");
    }
  });
}
