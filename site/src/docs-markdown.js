export function escapeHtml(input) {
  return input
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function currentSlugFrom(options) {
  if (typeof options.currentSlug === "function") {
    return options.currentSlug();
  }
  return options.currentSlug || "";
}

function renderInline(text, options = {}) {
  const codeTokens = [];
  let working = text.replace(/`([^`]+)`/g, (_match, code) => {
    codeTokens.push(`<code>${escapeHtml(code)}</code>`);
    return `\u0000${codeTokens.length - 1}\u0000`;
  });

  working = escapeHtml(working);
  working = working.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  working = working.replace(/(^|[^*])\*([^*]+)\*/g, "$1<em>$2</em>");

  working = working.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_m, label, href) => {
    let safeHref = href.trim();
    let isExternal = /^https?:\/\//i.test(safeHref);
    const isAnchorOnly = safeHref.startsWith("#") && !safeHref.startsWith("#/");
    const hasUnsafeProtocol = /^[a-z][a-z0-9+.-]*:/i.test(safeHref) && !isExternal;
    if (hasUnsafeProtocol) {
      safeHref = "#/";
    }
    if (isAnchorOnly) {
      const slug = currentSlugFrom(options);
      if (slug) {
        safeHref = `#/${slug}${safeHref}`;
      }
    } else if (!isExternal) {
      const mdMatch = safeHref.match(/^([^#?]+)\.md(#.*)?$/);
      if (mdMatch) {
        safeHref = `#/${mdMatch[1]}${mdMatch[2] || ""}`;
      }
    }
    isExternal = /^https?:\/\//i.test(safeHref);
    const target = isExternal ? ` target="_blank" rel="noopener"` : "";
    return `<a href="${escapeHtml(safeHref)}"${target}>${label}</a>`;
  });

  return working.replace(/\u0000(\d+)\u0000/g, (_m, i) => codeTokens[Number(i)]);
}

function slugifyHeading(text) {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
}

function collectListItems(lines, start, markerPattern) {
  const items = [];
  let i = start;

  while (i < lines.length) {
    const first = lines[i].match(markerPattern);
    if (!first) break;

    const itemLines = [first[1].trim()];
    i++;

    while (i < lines.length) {
      const next = lines[i];
      if (next.trim() === "") break;
      if (/^\s*-\s+/.test(next)) break;
      if (/^\s*\d+\.\s+/.test(next)) break;
      if (/^#{1,4}\s+/.test(next)) break;
      if (/^```/.test(next)) break;

      const continuation = next.match(/^\s{2,}(.+)$/);
      if (!continuation) break;

      itemLines.push(continuation[1].trim());
      i++;
    }

    items.push(itemLines.join(" "));
  }

  return { items, nextIndex: i };
}

export function renderMarkdown(md, options = {}) {
  const lines = md.replace(/\r\n/g, "\n").split("\n");
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      const lang = fence[1] || "text";
      const buf = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        buf.push(lines[i]);
        i++;
      }
      i++;
      out.push(
        `<pre class="docs-code" data-lang="${escapeHtml(lang)}"><button type="button" class="docs-copy" aria-label="Copy code">Copy</button><code>${escapeHtml(
          buf.join("\n"),
        )}</code></pre>`,
      );
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      const text = heading[2].trim();
      const id = slugifyHeading(text);
      out.push(`<h${level} id="${id}">${renderInline(text, options)}</h${level}>`);
      i++;
      continue;
    }

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
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].includes("|") && lines[i].trim() !== "") {
        rows.push(split(lines[i]));
        i++;
      }
      out.push(
        `<div class="docs-table-wrap"><table class="docs-table"><thead><tr>${headers
          .map((h) => `<th>${renderInline(h, options)}</th>`)
          .join("")}</tr></thead><tbody>${rows
          .map(
            (row) =>
              `<tr>${row.map((cell) => `<td>${renderInline(cell, options)}</td>`).join("")}</tr>`,
          )
          .join("")}</tbody></table></div>`,
      );
      continue;
    }

    if (/^\s*-\s+/.test(line)) {
      const result = collectListItems(lines, i, /^\s*-\s+(.+)$/);
      i = result.nextIndex;
      out.push(`<ul>${result.items.map((it) => `<li>${renderInline(it, options)}</li>`).join("")}</ul>`);
      continue;
    }

    if (/^\s*\d+\.\s+/.test(line)) {
      const result = collectListItems(lines, i, /^\s*\d+\.\s+(.+)$/);
      i = result.nextIndex;
      out.push(`<ol>${result.items.map((it) => `<li>${renderInline(it, options)}</li>`).join("")}</ol>`);
      continue;
    }

    if (line.trim() === "") {
      i++;
      continue;
    }

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
    out.push(`<p>${renderInline(buf.join(" "), options)}</p>`);
  }
  return out.join("\n");
}
