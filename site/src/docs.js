import "./styles.css";
import { setupRitualMenu } from "./menu.js";
import { escapeHtml, renderMarkdown } from "./docs-markdown.js";

document.documentElement.classList.add("js");
setupRitualMenu();

// Import all user docs as raw strings at build time. The path is relative to
// this file: site/src -> ../../docs/user. Vite resolves the glob and inlines
// content into the bundle (no runtime fetch, no path traversal at runtime).
const docModules = import.meta.glob("../../docs/user/**/*.md", {
  query: "?raw",
  import: "default",
  eager: true,
});

// Map slug -> raw markdown text. "index" becomes the docs landing page.
const docsBySlug = {};
const docsRootPrefix = "../../docs/user/";
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
  <p><strong>Beta status.</strong> Talos has planned Windows x64 installer and
  Ubuntu/WSL x64 tarball targets. These public paths are not live until GitHub
  Release assets exist. Current reliable path is source/developer setup.</p>
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
  // The docs landing reuses content from docs/user/index.md but is laid out
  // as a curated start surface rather than a raw rendering.
  const cards = [
    {
      group: "Start here",
      items: [
        ["Quickstart", "quickstart", "Source/developer setup to first session."],
        ["Installation", "installation", "Current install state and planned public beta."],
        ["Model Setup", "model-setup", "Configure a local model engine."],
        ["First Run", "first-run", "Understand the startup banner and prompt."],
        ["Beta Best Practices", "beta-best-practices", "Use beta Talos safely and effectively."],
      ],
    },
    {
      group: "Trust and safety",
      items: [
        ["Approvals And Permissions", "approvals-and-permissions", "When Talos asks before acting."],
        ["Local Privacy And Artifacts", "local-privacy-and-artifacts", "Private mode and local evidence."],
        ["File Support", "file-support", "Which file types are safe to use."],
      ],
    },
    {
      group: "Reference",
      items: [
        ["Commands", "commands", "Top-level CLI and REPL slash commands."],
        ["Workspaces And Indexing", "workspaces-and-indexing", "Workspace boundary and index state."],
        ["Retrieval And Vectors", "retrieval-and-vectors", "RAG, BM25, vectors, and disabled-vector behavior."],
        ["Troubleshooting", "troubleshooting", "Diagnose install, model, and runtime issues."],
        ["Release Channels", "release-channels", "Beta status and planned release artifacts."],
      ],
    },
    {
      group: "Concepts",
      items: [
        ["How Talos Works", "how-talos-works", "The execution contract behind every turn."],
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
  <h1>Local-first CLI workspace operator docs.</h1>
  <p class="docs-lede">
    Setup, commands, approvals, privacy, and troubleshooting for the current
    source/developer setup plus planned Windows and Ubuntu/WSL x64 public
    artifact targets. The docs include current limits.
  </p>
  <p class="docs-start-path">
    Start here:
    <a href="#/quickstart">Quickstart</a>
    <span aria-hidden="true">→</span>
    <a href="#/model-setup">Model Setup</a>
    <span aria-hidden="true">→</span>
    <a href="#/first-run">First Run</a>.
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
