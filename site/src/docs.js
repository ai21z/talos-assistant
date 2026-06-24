import "./styles.css";
import { setupRitualMenu } from "./menu.js";

document.documentElement.classList.add("js");
setupRitualMenu();

// Import all user docs as raw strings at build time. The path is relative to
// this file: site/src -> ../../docs/user. Vite resolves the glob and inlines
// content into the bundle (no runtime fetch, no path traversal at runtime).
const docModules = import.meta.glob("../../docs/user/*.md", {
  query: "?raw",
  import: "default",
  eager: true,
});

// Map slug -> raw markdown text. "index" becomes the docs landing page.
const docsBySlug = {};
for (const [path, raw] of Object.entries(docModules)) {
  const slug = path.replace(/^.*\//, "").replace(/\.md$/, "");
  docsBySlug[slug] = raw;
}

// --- Minimal Markdown parser ----------------------------------------------
// Supports: ATX headings (#-###), paragraphs, unordered (`-`) and ordered
// (`1.`) lists, GFM-style tables, fenced code blocks, inline code, links,
// and bold/italic. Intentionally narrow: covers the patterns used in
// docs/user/*.md and nothing more. No HTML passthrough; user docs are
// authored, not hostile, but we still escape every literal value.
function escapeHtml(input) {
  return input
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderInline(text) {
  // Tokenize inline code first so it is not re-processed.
  const codeTokens = [];
  let working = text.replace(/`([^`]+)`/g, (_match, code) => {
    codeTokens.push(`<code>${escapeHtml(code)}</code>`);
    return `\u0000${codeTokens.length - 1}\u0000`;
  });

  working = escapeHtml(working);

  // Bold (**x**) and italic (*x*), bold first.
  working = working.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  working = working.replace(/(^|[^*])\*([^*]+)\*/g, "$1<em>$2</em>");

  // Links: [label](href). Rewrite internal `*.md` links to in-site hash routes.
  working = working.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_m, label, href) => {
    let safeHref = href.trim();
    let isExternal = /^https?:\/\//i.test(safeHref);
    const isAnchorOnly = safeHref.startsWith("#") && !safeHref.startsWith("#/");
    const hasUnsafeProtocol = /^[a-z][a-z0-9+.-]*:/i.test(safeHref) && !isExternal;
    if (hasUnsafeProtocol) {
      safeHref = "#/";
    }
    if (isAnchorOnly) {
      const { slug } = currentRoute();
      if (slug) {
        safeHref = `#/${slug}${safeHref}`;
      }
    } else if (!isExternal) {
      // e.g. "installation.md" or "installation.md#section"
      const mdMatch = safeHref.match(/^([^#?]+)\.md(#.*)?$/);
      if (mdMatch) {
        safeHref = `#/${mdMatch[1]}${mdMatch[2] || ""}`;
      }
    }
    isExternal = /^https?:\/\//i.test(safeHref);
    const target = isExternal ? ` target="_blank" rel="noopener"` : "";
    return `<a href="${escapeHtml(safeHref)}"${target}>${label}</a>`;
  });

  // Restore inline code tokens.
  working = working.replace(/\u0000(\d+)\u0000/g, (_m, i) => codeTokens[Number(i)]);
  return working;
}

function slugifyHeading(text) {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
}

function renderMarkdown(md) {
  const lines = md.replace(/\r\n/g, "\n").split("\n");
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    // Fenced code block
    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      const lang = fence[1] || "text";
      const buf = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        buf.push(lines[i]);
        i++;
      }
      i++; // consume closing fence
      out.push(
        `<pre class="docs-code" data-lang="${escapeHtml(lang)}"><button type="button" class="docs-copy" aria-label="Copy code">Copy</button><code>${escapeHtml(
          buf.join("\n"),
        )}</code></pre>`,
      );
      continue;
    }

    // Headings
    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      const text = heading[2].trim();
      const id = slugifyHeading(text);
      out.push(`<h${level} id="${id}">${renderInline(text)}</h${level}>`);
      i++;
      continue;
    }

    // Table: a header row followed by a separator row of dashes/pipes.
    if (
      line.includes("|") &&
      i + 1 < lines.length &&
      /^\s*\|?\s*:?-{2,}.*\|/.test(lines[i + 1])
    ) {
      const split = (row) =>
        row
          .replace(/^\s*\|/, "")
          .replace(/\|\s*$/, "")
          .split("|")
          .map((cell) => cell.trim());
      const headers = split(line);
      i += 2; // consume header + separator
      const rows = [];
      while (i < lines.length && lines[i].includes("|") && lines[i].trim() !== "") {
        rows.push(split(lines[i]));
        i++;
      }
      out.push(
        `<div class="docs-table-wrap"><table class="docs-table"><thead><tr>${headers
          .map((h) => `<th>${renderInline(h)}</th>`)
          .join("")}</tr></thead><tbody>${rows
          .map(
            (row) =>
              `<tr>${row.map((cell) => `<td>${renderInline(cell)}</td>`).join("")}</tr>`,
          )
          .join("")}</tbody></table></div>`,
      );
      continue;
    }

    // Unordered list
    if (/^\s*-\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*-\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*-\s+/, ""));
        i++;
      }
      out.push(`<ul>${items.map((it) => `<li>${renderInline(it)}</li>`).join("")}</ul>`);
      continue;
    }

    // Ordered list
    if (/^\s*\d+\.\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+\.\s+/, ""));
        i++;
      }
      out.push(`<ol>${items.map((it) => `<li>${renderInline(it)}</li>`).join("")}</ol>`);
      continue;
    }

    // Blank line
    if (line.trim() === "") {
      i++;
      continue;
    }

    // Paragraph. Collect contiguous non-blank lines that aren't block starts.
    const buf = [line];
    i++;
    while (i < lines.length) {
      const next = lines[i];
      if (next.trim() === "") break;
      if (/^#{1,4}\s+/.test(next)) break;
      if (/^```/.test(next)) break;
      if (/^\s*-\s+/.test(next)) break;
      if (/^\s*\d+\.\s+/.test(next)) break;
      buf.push(next);
      i++;
    }
    out.push(`<p>${renderInline(buf.join(" "))}</p>`);
  }
  return out.join("\n");
}

// --- Routing --------------------------------------------------------------
const article = document.getElementById("docs-article");
const navLinks = Array.from(document.querySelectorAll("[data-doc-slug]"));
const STATUS_NOTE_HTML = `
<aside class="docs-callout docs-callout--beta" role="note">
  <p><strong>Beta status.</strong> Talos has a planned Windows packaged public beta and a
  Linux source/developer beta path. Public installer is planned, not live. Current reliable
  path is source/developer setup.</p>
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

  article.innerHTML = renderMarkdown(md);
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
    Windows packaged beta; Linux source/developer beta. Source-backed, paired with concrete limits.
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
