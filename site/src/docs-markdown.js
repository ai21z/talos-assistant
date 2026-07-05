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

function resolveMarkdownSlug(currentSlug, href) {
  const [pathPart, anchor = ""] = href.split("#");
  const parts = currentSlug ? currentSlug.split("/").slice(0, -1) : [];
  for (const part of pathPart.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") {
      parts.pop();
    } else {
      parts.push(part);
    }
  }
  return `#/${parts.join("/").replace(/\.md$/, "")}${anchor ? `#${anchor}` : ""}`;
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
        safeHref = resolveMarkdownSlug(currentSlugFrom(options), safeHref);
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

function unique(items) {
  const seen = new Set();
  const result = [];
  for (const item of items) {
    const normalized = item.trim();
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    result.push(normalized);
  }
  return result;
}

function extractMermaidLabels(source) {
  const labels = [];
  const labelPattern = /[\[{]\"([^\"]+)\"[\]}]/g;
  for (const line of source.split("\n")) {
    let match;
    while ((match = labelPattern.exec(line)) !== null) {
      labels.push(match[1]);
    }
  }
  return unique(labels);
}

function parseFlowNodeRef(raw) {
  const text = raw.trim().replace(/;$/, "");
  const match = text.match(
    /^([A-Za-z][\w-]*)(?:\s*(?:\["([^"]+)"\]|\{"([^"]+)"\}|\("([^"]+)"\)|\(([^)]+)\)))?$/,
  );
  if (!match) return null;
  return {
    id: match[1],
    label: match[2] || match[3] || match[4] || match[5] || "",
  };
}

function parseFlowEdge(line) {
  const trimmed = line.trim();
  const labeled = trimmed.match(/^(.+?)\s+--\s+(.+?)\s+-->\s+(.+)$/);
  if (labeled) {
    const from = parseFlowNodeRef(labeled[1]);
    const to = parseFlowNodeRef(labeled[3]);
    if (!from || !to) return null;
    return { from, to, label: labeled[2].trim() };
  }

  const arrow = trimmed.match(/^(.+?)\s*(-->|==>|-.->|->)\s*(.+)$/);
  if (!arrow) return null;

  const from = parseFlowNodeRef(arrow[1]);
  const to = parseFlowNodeRef(arrow[3]);
  if (!from || !to) return null;
  return { from, to, label: "" };
}

function parseFlowchart(source) {
  const declarations = new Map();
  const nodeDeclaration = /\b([A-Za-z][\w-]*)\s*(?:\["([^"]+)"\]|\{"([^"]+)"\})/g;

  for (const line of source.split("\n")) {
    const trimmed = line.trim();
    if (/^(flowchart|subgraph|end\b)/i.test(trimmed)) continue;

    let declaration;
    while ((declaration = nodeDeclaration.exec(line)) !== null) {
      declarations.set(declaration[1], declaration[2] || declaration[3] || declaration[1]);
    }
  }

  const nodesById = new Map();
  const nodes = [];
  const edges = [];
  const upsertNode = (nodeRef) => {
    const label = nodeRef.label || declarations.get(nodeRef.id) || nodeRef.id;
    const existing = nodesById.get(nodeRef.id);
    if (existing) {
      if (nodeRef.label && existing.label === nodeRef.id) {
        existing.label = label;
      }
      return existing;
    }
    const node = { id: nodeRef.id, label };
    nodesById.set(node.id, node);
    nodes.push(node);
    return node;
  };

  for (const line of source.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || /^(flowchart|subgraph|end\b)/i.test(trimmed)) continue;
    const edge = parseFlowEdge(trimmed);
    if (!edge) continue;
    const from = upsertNode(edge.from);
    const to = upsertNode(edge.to);
    edges.push({ from: from.id, to: to.id, label: edge.label });
  }

  return { nodes, edges, nodesById };
}

function isLinearFlow(edges) {
  return edges.length > 0 && edges.every((edge, index) => index === 0 || edge.from === edges[index - 1].to);
}

function renderFlowNode(node, index) {
  return `<div class="docs-flow-node">
    <span class="docs-diagram-step">${index + 1}</span>
    <span>${escapeHtml(node.label)}</span>
  </div>`;
}

function renderFlowArrow(from, to, label = "") {
  const labelHtml = label ? `<span>${escapeHtml(label)}</span>` : "";
  return `<span class="docs-flow-arrow" aria-label="${escapeHtml(`${from.label} to ${to.label}`)}">${labelHtml}</span>`;
}

function renderFlowchartDiagram(source) {
  const flow = parseFlowchart(source);
  if (flow.edges.length > 0) {
    if (isLinearFlow(flow.edges)) {
      const parts = [];
      const first = flow.nodesById.get(flow.edges[0].from);
      if (first) {
        parts.push(renderFlowNode(first, 0));
      }
      flow.edges.forEach((edge, index) => {
        const from = flow.nodesById.get(edge.from);
        const to = flow.nodesById.get(edge.to);
        if (!from || !to) return;
        parts.push(renderFlowArrow(from, to, edge.label));
        parts.push(renderFlowNode(to, index + 1));
      });
      return `<figure class="docs-diagram docs-diagram--flowchart">
  <figcaption>Flow</figcaption>
  <div class="docs-flow-path">${parts.join("")}</div>
</figure>`;
    }

    const edgeHtml = flow.edges
      .map((edge, index) => {
        const from = flow.nodesById.get(edge.from);
        const to = flow.nodesById.get(edge.to);
        if (!from || !to) return "";
        return `<li class="docs-flow-edge">
          ${renderFlowNode(from, index)}
          ${renderFlowArrow(from, to, edge.label)}
          ${renderFlowNode(to, index + 1)}
        </li>`;
      })
      .join("");
    return `<figure class="docs-diagram docs-diagram--flowchart">
  <figcaption>Flow</figcaption>
  <ol class="docs-flow-edge-list">${edgeHtml}</ol>
</figure>`;
  }

  const nodes = extractMermaidLabels(source);
  const nodeHtml = nodes
    .map((label, index) => `<li><span>${index + 1}</span>${escapeHtml(label)}</li>`)
    .join("");
  return `<figure class="docs-diagram docs-diagram--flowchart">
  <figcaption>Flow</figcaption>
  <ol class="docs-diagram-nodes">${nodeHtml}</ol>
</figure>`;
}

function renderSequenceDiagram(source) {
  const lines = source.split("\n").map((line) => line.trim()).filter(Boolean);
  const participants = new Map();
  const messages = [];

  for (const line of lines) {
    const participant = line.match(/^participant\s+(\S+)\s+as\s+(.+)$/i);
    if (participant) {
      participants.set(participant[1], participant[2].trim());
      continue;
    }

    const message = line.match(/^(.+?)\s*(-->>|->>|-->|->|--x|-x|--\)|-\))\s*(.+?)\s*:\s*(.+)$/);
    if (message) {
      messages.push({
        from: participants.get(message[1]) || message[1],
        to: participants.get(message[3]) || message[3],
        text: message[4].trim(),
      });
    }
  }

  const participantNames = unique([
    ...Array.from(participants.values()),
    ...messages.flatMap((message) => [message.from, message.to]),
  ]);
  const participantHtml = participantNames
    .map((name) => `<li class="docs-sequence-actor">${escapeHtml(name)}</li>`)
    .join("");
  const messageHtml = messages
    .map(
      (message, index) => `<li class="docs-sequence-message">
        <span class="docs-diagram-step">${index + 1}</span>
        <strong>${escapeHtml(message.from)}</strong>
        <span class="docs-sequence-arrow" aria-label="${escapeHtml(`${message.from} to ${message.to}`)}"></span>
        <strong>${escapeHtml(message.to)}</strong>
        <p>${escapeHtml(message.text)}</p>
      </li>`,
    )
    .join("");

  return `<figure class="docs-diagram docs-diagram--sequence">
  <figcaption>Turn pipeline</figcaption>
  <div class="docs-sequence-lanes">
    <ul class="docs-sequence-actors">${participantHtml}</ul>
    <ol class="docs-sequence-messages">${messageHtml}</ol>
  </div>
</figure>`;
}

function renderMermaidDiagram(source) {
  const trimmed = source.trim();
  if (/^sequenceDiagram\b/i.test(trimmed)) {
    return renderSequenceDiagram(trimmed);
  }
  if (/^flowchart\b/i.test(trimmed)) {
    return renderFlowchartDiagram(trimmed);
  }
  return `<figure class="docs-diagram docs-diagram--generic">
  <figcaption>Diagram</figcaption>
  <ol class="docs-diagram-nodes">${extractMermaidLabels(trimmed)
    .map((label, index) => `<li><span>${index + 1}</span>${escapeHtml(label)}</li>`)
    .join("")}</ol>
</figure>`;
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
      if (lang.toLowerCase() === "mermaid") {
        out.push(renderMermaidDiagram(buf.join("\n")));
        continue;
      }
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
