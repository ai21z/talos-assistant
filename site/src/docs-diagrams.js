let enginePromise;
let renderId = 0;

export function validateDiagramSource(source) {
  if (source.length > 20000 || !/^\s*(?:flowchart\s+(?:TB|TD|BT|LR|RL)|sequenceDiagram)\s*\n/.test(source)) {
    throw new Error("Unsupported diagram source");
  }
  if (/%%\{|^\s*---|(?:^|;)\s*(?:click|style|classDef|linkStyle|link|links)\b|@\{/im.test(source)) {
    throw new Error("Diagram configuration and interactive content are not supported");
  }
}

async function loadEngine() {
  enginePromise ??= import("mermaid").then(({ default: mermaid }) => {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      suppressErrorRendering: true,
      htmlLabels: false,
      look: "classic",
      maxTextSize: 20000,
      maxEdges: 200,
      theme: "base",
      fontFamily: "Arial, sans-serif",
      themeVariables: {
        darkMode: true,
        background: "#101611",
        primaryColor: "#18231d",
        primaryTextColor: "#f0ede6",
        primaryBorderColor: "#87a084",
        secondaryColor: "#20251c",
        tertiaryColor: "#141b17",
        lineColor: "#c98a55",
        textColor: "#f0ede6",
        mainBkg: "#18231d",
        nodeBorder: "#87a084",
        clusterBkg: "#101611",
        clusterBorder: "#60775f",
        titleColor: "#f0ede6",
        edgeLabelBackground: "#101611",
        actorBkg: "#18231d",
        actorBorder: "#87a084",
        actorTextColor: "#f0ede6",
        actorLineColor: "#60775f",
        signalColor: "#c98a55",
        signalTextColor: "#f0ede6",
        labelBoxBkgColor: "#18231d",
        labelTextColor: "#f0ede6",
        loopTextColor: "#f0ede6",
        noteBkgColor: "#20251c",
        noteTextColor: "#f0ede6",
        fontSize: "15px",
      },
      flowchart: { useMaxWidth: false, curve: "linear", nodeSpacing: 28, rankSpacing: 48, wrappingWidth: 180 },
      sequence: { useMaxWidth: false, wrap: true, width: 105, actorMargin: 28, messageMargin: 38, mirrorActors: false },
    });
    return mermaid;
  });
  return enginePromise;
}

export function validateDiagramCss(css) {
  if (/@import|@font-face|\\|expression\s*\(/i.test(css)) throw new Error("External diagram CSS is not supported");
  for (const [, raw] of css.matchAll(/url\s*\(([^)]*)\)/gi)) {
    const target = raw.trim().replace(/^(['"])(.*)\1$/, "$2");
    if (!/^#[\w-]+$/.test(target)) throw new Error("External diagram reference is not supported");
  }
}

export function parseDiagramSvg(markup) {
  const parsed = new DOMParser().parseFromString(markup, "image/svg+xml");
  const svg = parsed.documentElement;
  const tags = new Set(["svg", "g", "defs", "marker", "path", "rect", "circle", "ellipse", "polygon", "polyline", "line", "text", "tspan", "title", "desc", "style", "filter", "feDropShadow", "symbol", "use"]);
  if (svg.localName !== "svg" || parsed.querySelector("parsererror")) throw new Error("Invalid diagram SVG");
  for (const element of [svg, ...svg.querySelectorAll("*")]) {
    if (element.namespaceURI !== "http://www.w3.org/2000/svg" || !tags.has(element.localName)) {
      throw new Error(`Unsupported diagram element ${element.localName}`);
    }
    for (const attribute of element.attributes) {
      if (attribute.namespaceURI === "http://www.w3.org/XML/1998/namespace" && attribute.localName === "base") throw new Error("SVG base overrides are not supported");
      if (/^on/i.test(attribute.name)) throw new Error("Interactive SVG is not supported");
      if (/href$/i.test(attribute.name) && !/^#[\w-]+$/.test(attribute.value)) throw new Error("External SVG is not supported");
      if (["style", "fill", "stroke", "filter", "clip-path", "mask", "marker-start", "marker-mid", "marker-end"].includes(attribute.name)) {
        validateDiagramCss(attribute.value);
      }
    }
    if (element.localName === "style") validateDiagramCss(element.textContent);
  }
  svg.setAttribute("role", "img");
  svg.removeAttribute("style");
  return document.importNode(svg, true);
}

export async function renderDiagrams(article, isCurrent = () => true) {
  const figures = [...article.querySelectorAll('.docs-diagram[data-diagram-state="pending"]')];
  if (!figures.length) return;
  const current = (figure) => isCurrent() && figure.isConnected;
  for (const figure of figures) {
    if (!current(figure)) return;
    let stage;
    try {
      const source = figure.querySelector(".docs-diagram-source code").textContent;
      validateDiagramSource(source);
      const engine = await loadEngine();
      await document.fonts.ready;
      if (!current(figure)) return;
      stage = document.createElement("div");
      stage.className = "docs-diagram-stage";
      stage.setAttribute("aria-hidden", "true");
      document.body.append(stage);
      const { svg: markup } = await engine.render(`talos-diagram-${++renderId}`, source, stage);
      if (!current(figure)) return;
      const svg = parseDiagramSvg(markup);
      const width = svg.viewBox.baseVal.width;
      if (!Number.isFinite(width) || width <= 0) throw new Error("Invalid diagram dimensions");
      svg.style.setProperty("--diagram-width", `${Math.ceil(width)}px`);
      const viewport = figure.querySelector(".docs-diagram-viewport");
      if (!svg.hasAttribute("aria-labelledby")) svg.setAttribute("aria-label", viewport.getAttribute("aria-label"));
      viewport.replaceChildren(svg);
      viewport.hidden = false;
      figure.querySelector(".docs-diagram-expand").hidden = false;
      figure.querySelector(".docs-diagram-fit").hidden = false;
      figure.querySelector(".docs-diagram-status").textContent = "Scroll to explore, or expand for a larger view.";
      figure.dataset.diagramState = "ready";
    } catch (error) {
      if (import.meta.env?.DEV) console.warn("Documentation diagram unavailable", error.message);
      if (!current(figure)) return;
      figure.dataset.diagramState = "error";
      figure.querySelector(".docs-diagram-status").textContent = "Diagram unavailable. The description and source remain available.";
      figure.querySelector(".docs-diagram-source").open = true;
    } finally {
      stage?.remove();
    }
  }
}

export function closeDiagram(restoreFocus = true) {
  const dialog = document.querySelector(".docs-diagram-dialog");
  if (!dialog) return;
  dialog.dataset.restoreFocus = String(restoreFocus);
  dialog.close();
}

export function setupDiagramExpansion(article) {
  article.addEventListener("click", (event) => {
    const fit = event.target.closest(".docs-diagram-fit");
    if (fit) {
      const viewport = fit.closest(".docs-diagram").querySelector(".docs-diagram-viewport");
      fit.setAttribute("aria-pressed", String(viewport.classList.toggle("is-fitted")));
      return;
    }
    const button = event.target.closest(".docs-diagram-expand");
    if (!button) return;
    const figure = button.closest(".docs-diagram");
    const viewport = figure.querySelector(".docs-diagram-viewport");
    const svg = viewport.querySelector("svg");
    if (!svg) return;
    closeDiagram();
    const dialog = document.createElement("dialog");
    dialog.className = "docs-diagram-dialog";
    dialog.setAttribute("aria-label", figure.querySelector("figcaption").textContent);
    const header = document.createElement("div");
    header.className = "docs-diagram-dialog-header";
    const title = document.createElement("p");
    title.textContent = figure.querySelector("figcaption").textContent;
    const close = document.createElement("button");
    close.type = "button";
    close.textContent = "Close diagram";
    close.addEventListener("click", () => dialog.close());
    header.append(title, close);
    const canvas = document.createElement("div");
    canvas.className = "docs-diagram-viewport";
    canvas.tabIndex = 0;
    canvas.setAttribute("role", "region");
    canvas.setAttribute("aria-label", "Expanded diagram, scroll to explore");
    const fitWidth = document.createElement("button");
    fitWidth.type = "button";
    fitWidth.textContent = "Fit width";
    fitWidth.setAttribute("aria-pressed", "false");
    fitWidth.addEventListener("click", () => {
      fitWidth.setAttribute("aria-pressed", String(canvas.classList.toggle("is-fitted")));
    });
    header.insertBefore(fitWidth, close);
    canvas.append(svg);
    dialog.append(header, canvas);
    document.body.append(dialog);
    dialog.addEventListener("close", () => {
      viewport.append(svg);
      dialog.remove();
      if (button.isConnected && dialog.dataset.restoreFocus !== "false") button.focus({ preventScroll: true });
    }, { once: true });
    dialog.showModal();
    close.focus();
  });
}
