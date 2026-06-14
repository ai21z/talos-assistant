import "@fontsource/gfs-neohellenic/greek-700.css";
import "./styles.css";
import { setupForge } from "./forge.js";

document.documentElement.classList.add("js");

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

// ---------------------------------------------------------------------------
// Semantic lane grammar. Glyphs match
// src/main/java/dev/talos/cli/ui/SemanticGlyphSet.java safe Unicode:
//   bullet •  arrow →  success ✓  warning !  error x  rail │ ┌ └  dot ·
// Prompt matches src/main/java/dev/talos/cli/ui/PromptRenderer.java: "talos [auto] >".
// ---------------------------------------------------------------------------
const PROMPT_PREFIX =
  '<span class="t-prompt-name">talos</span> <span class="t-prompt-mode">[auto]</span> &gt; ';

// ---- Live hero terminal: streams one real Inspect turn, then replays --------
const heroTurn = {
  question: "what does this workspace do?",
  lines: [
    "",
    '<span class="t-cyan">•</span> route   <span class="t-muted">ask · read-only · workspace bounded</span>',
    '<span class="t-cyan">→</span> inspect <span class="t-muted">README.md, src/, docs/</span>',
    '<span class="t-green">✓</span> read    <span class="t-muted">4 files · 38 ms</span>',
    "",
    '<span class="t-rail">┌─ answer ───────────────────────────────────────────</span>',
    '<span class="t-rail">│</span> Local-first CLI workspace operator. Java 21 sources',
    '<span class="t-rail">│</span> under <span class="t-cyan">src/</span>; architecture notes under <span class="t-cyan">docs/</span>.',
    '<span class="t-rail">└─ turn 1 · 1.2 s · <span class="t-muted">/last trace</span></span>',
  ],
};

function setupLiveTerminal() {
  const root = document.querySelector("[data-live-terminal]");
  if (!root) return;
  const output = root.querySelector("[data-live-output]");
  const state = root.querySelector("[data-live-state]");
  const replay = root.querySelector("[data-live-replay]");
  if (!output) return;

  let timers = [];
  const clearTimers = () => {
    timers.forEach((id) => window.clearTimeout(id));
    timers = [];
  };
  const after = (ms, fn) => timers.push(window.setTimeout(fn, ms));
  const setState = (label) => {
    if (state) state.textContent = label;
  };

  const fullHtml = () =>
    `${PROMPT_PREFIX}${heroTurn.question}\n${heroTurn.lines.join("\n")}`;

  function renderStatic() {
    output.innerHTML = fullHtml();
    setState("ready");
  }

  function play() {
    clearTimers();
    if (reduceMotion.matches) {
      renderStatic();
      return;
    }
    setState("run");
    let typed = "";
    const caret = '<span class="terminal-caret" aria-hidden="true"></span>';
    const drawPrompt = () => {
      output.innerHTML = `${PROMPT_PREFIX}${typed}${caret}`;
    };
    drawPrompt();

    // Phase 1 — type the question.
    for (let i = 0; i < heroTurn.question.length; i += 1) {
      after(160 + i * 34, () => {
        typed += heroTurn.question[i];
        drawPrompt();
      });
    }

    // Phase 2 — reveal the lanes one at a time.
    const typeEnd = 160 + heroTurn.question.length * 34 + 240;
    let acc = typeEnd;
    const base = `${PROMPT_PREFIX}${heroTurn.question}`;
    const shown = [];
    heroTurn.lines.forEach((line, index) => {
      const blank = line === "";
      acc += blank ? 90 : 230;
      after(acc, () => {
        shown.push(line);
        const isLast = index === heroTurn.lines.length - 1;
        const tail = isLast ? "" : `\n${caret}`;
        output.innerHTML = `${base}\n${shown.join("\n")}${tail}`;
        if (isLast) setState("ready");
      });
    });
  }

  if (replay) replay.addEventListener("click", play);
  reduceMotion.addEventListener("change", renderStatic);

  // Start when the terminal scrolls into view (it is above the fold on load).
  if ("IntersectionObserver" in window && !reduceMotion.matches) {
    let played = false;
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting && !played) {
            played = true;
            play();
            io.disconnect();
          }
        });
      },
      { threshold: 0.4 },
    );
    io.observe(root);
  } else {
    renderStatic();
  }
}

// ---- Interactive turn examples (tabbed) -------------------------------------
const terminalStates = {
  inspect: [
    `${PROMPT_PREFIX}what does this workspace do?`,
    "",
    '<span class="t-cyan">•</span> route   <span class="t-muted">ask · read-only · workspace bounded</span>',
    '<span class="t-cyan">→</span> inspect <span class="t-muted">README.md, src/, docs/</span>',
    '<span class="t-green">✓</span> read    <span class="t-muted">4 files · 38 ms</span>',
    "",
    '<span class="t-rail">┌─ answer ───────────────────────────────────────────</span>',
    '<span class="t-rail">│</span> Local-first CLI workspace operator. Java 21 sources',
    '<span class="t-rail">│</span> under <span class="t-cyan">src/</span>; architecture notes under <span class="t-cyan">docs/</span>.',
    '<span class="t-rail">└─ turn 1 · 1.2 s · <span class="t-muted">/last trace</span></span>',
  ].join("\n"),

  approve: [
    `${PROMPT_PREFIX}create docs/summary.md from this repo`,
    "",
    '<span class="t-cyan">•</span> route   <span class="t-muted">edit · workspace bounded</span>',
    '<span class="t-cyan">→</span> inspect <span class="t-muted">README.md, build.gradle.kts</span>',
    '<span class="t-green">✓</span> read    <span class="t-muted">2 files · 22 ms</span>',
    "",
    '<span class="t-amber">┌─ approval required ────────────────────────────────</span>',
    '<span class="t-amber">│</span> action  <span class="t-body">write file</span>',
    '<span class="t-amber">│</span> target  <span class="t-body">docs/summary.md</span>',
    '<span class="t-amber">│</span> risk    <span class="t-body">creates one workspace file</span>',
    '<span class="t-amber">│</span> allow?  <span class="t-body">[y = yes · a = yes for session · N = no]</span> _',
    '<span class="t-amber">└────────────────────────────────────────────────────</span>',
  ].join("\n"),

  verify: [
    `${PROMPT_PREFIX}run the approved gradle test command`,
    "",
    '<span class="t-cyan">•</span> route   <span class="t-muted">command · profile gradle_test</span>',
    '<span class="t-cyan">→</span> exec    <span class="t-muted">talos.run_command · bounded</span>',
    '<span class="t-green">✓</span> command <span class="t-muted">exit 0 · 4.6 s</span>',
    '<span class="t-green">✓</span> verify  <span class="t-muted">12 tests passed · 0 failed</span>',
    "",
    '<span class="t-rail">┌─ answer ───────────────────────────────────────────</span>',
    '<span class="t-rail">│</span> Gradle test profile passed. Twelve tests ran, none failed.',
    '<span class="t-rail">│</span> Verification grounded in command output, not model claim.',
    '<span class="t-rail">└─ turn 7 · 5.1 s · <span class="t-muted">/last trace</span></span>',
  ].join("\n"),

  trace: [
    `${PROMPT_PREFIX}/last trace`,
    "",
    '<span class="t-bronze">trace</span>',
    '<span class="t-muted">  prompt frame      auto · workspace bounded</span>',
    '<span class="t-muted">  tool surface      list_dir, read_file, grep, retrieve, write_file</span>',
    '<span class="t-muted">  tool calls        read_file × 2 · write_file × 1</span>',
    '<span class="t-amber">  approvals         write docs/summary.md · accepted</span>',
    '<span class="t-green">  verification      readback ok · expected target matched</span>',
  ].join("\n"),
};

function setTerminalState(nextState) {
  const panel = document.querySelector("#terminal-output");
  const status = document.querySelector("#terminal-status");
  const tabs = Array.from(document.querySelectorAll("[data-terminal-state]"));
  const activeTab = tabs.find((tab) => tab.dataset.terminalState === nextState);

  if (!panel || !activeTab || !terminalStates[nextState]) return;

  // innerHTML is safe here: all source strings are hard-coded constants above.
  panel.innerHTML = terminalStates[nextState];
  panel.setAttribute("aria-labelledby", activeTab.id);
  if (status) status.textContent = `${activeTab.textContent.trim()} turn selected.`;

  tabs.forEach((tab) => {
    const selected = tab === activeTab;
    tab.setAttribute("aria-selected", String(selected));
    tab.tabIndex = selected ? 0 : -1;
  });
}

function handleTabKey(event, tabs) {
  const currentIndex = tabs.indexOf(event.currentTarget);
  const lastIndex = tabs.length - 1;
  let nextIndex = currentIndex;

  if (event.key === "ArrowRight") nextIndex = currentIndex === lastIndex ? 0 : currentIndex + 1;
  if (event.key === "ArrowLeft") nextIndex = currentIndex === 0 ? lastIndex : currentIndex - 1;
  if (event.key === "Home") nextIndex = 0;
  if (event.key === "End") nextIndex = lastIndex;
  if (nextIndex === currentIndex && !["Home", "End"].includes(event.key)) return;

  event.preventDefault();
  const nextTab = tabs[nextIndex];
  nextTab.focus();
  setTerminalState(nextTab.dataset.terminalState);
}

function setupTurnTabs() {
  const tabs = Array.from(document.querySelectorAll("[data-terminal-state]"));
  if (!tabs.length) return;
  tabs.forEach((tab) => {
    tab.addEventListener("click", () => setTerminalState(tab.dataset.terminalState));
    tab.addEventListener("keydown", (event) => handleTabKey(event, tabs));
  });
  setTerminalState("inspect");
}

// ---- Scroll-spy nav + reveal-on-scroll (native scroll, no hijacking) --------
function setupSectionNav() {
  const navLinks = Array.from(document.querySelectorAll("[data-section-nav]"));
  const sections = Array.from(document.querySelectorAll("main section[id]"));
  if (!navLinks.length || !sections.length) return;

  const linkFor = (id) => navLinks.find((link) => link.getAttribute("href") === `#${id}`);
  const setActive = (id) => {
    navLinks.forEach((link) => {
      if (link === linkFor(id)) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    });
  };

  const io = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio);
      if (visible[0]) setActive(visible[0].target.id);
    },
    { rootMargin: "-45% 0px -45% 0px", threshold: [0, 0.25, 0.5, 1] },
  );
  sections.forEach((section) => io.observe(section));
}

function setupReveal() {
  const targets = Array.from(document.querySelectorAll(".reveal"));
  if (!targets.length) return;
  if (reduceMotion.matches || !("IntersectionObserver" in window)) {
    targets.forEach((target) => target.classList.add("reveal--visible"));
    return;
  }
  const reveal = (target) => target.classList.add("reveal--visible");
  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        reveal(entry.target);
        io.unobserve(entry.target);
      });
    },
    { threshold: 0.12, rootMargin: "0px 0px -8% 0px" },
  );
  targets.forEach((target) => io.observe(target));

  // Safety net: never leave content hidden if the observer never fires for an
  // element (e.g. printing, deep-link jumps, or assistive scroll modes).
  window.setTimeout(() => targets.forEach(reveal), 2200);
}

// ---- Staggered reveal — child cascades on the card grids -------------------
function setupStagger() {
  const groups = Array.from(document.querySelectorAll("[data-reveal-stagger]"));
  if (!groups.length) return;

  const revealGroup = (group) => {
    Array.from(group.children).forEach((child, index) => {
      child.style.setProperty("--reveal-delay", `${index * 70}ms`);
    });
    group.classList.add("is-in");
  };

  if (reduceMotion.matches || !("IntersectionObserver" in window)) {
    groups.forEach((group) => group.classList.add("is-in"));
    return;
  }

  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        revealGroup(entry.target);
        io.unobserve(entry.target);
      });
    },
    { threshold: 0.1, rootMargin: "0px 0px -6% 0px" },
  );
  groups.forEach((group) => io.observe(group));

  // Safety net: never strand a group hidden if the observer never fires.
  window.setTimeout(() => groups.forEach(revealGroup), 2600);
}

// ---- Cursor-tracking sheen on cards (fine pointers only) -------------------
function setupPointerSheen() {
  if (reduceMotion.matches) return;
  if (window.matchMedia("(pointer: coarse)").matches) return;
  const cards = Array.from(
    document.querySelectorAll(".contract-step, .boundary-band, .use-case, .doc-card, .terminal-card"),
  );
  cards.forEach((card) => {
    card.classList.add("has-sheen");
    const sheen = document.createElement("span");
    sheen.className = "sheen";
    sheen.setAttribute("aria-hidden", "true");
    card.appendChild(sheen);
    card.addEventListener("pointermove", (event) => {
      const rect = card.getBoundingClientRect();
      card.style.setProperty("--mx", `${event.clientX - rect.left}px`);
      card.style.setProperty("--my", `${event.clientY - rect.top}px`);
    });
  });
}

// ---- The ichor vein — scroll fills the guardian's single vein ---------------
function setupVein() {
  const vein = document.querySelector(".vein");
  if (!vein) return;
  let raf = 0;
  const update = () => {
    raf = 0;
    const max = document.documentElement.scrollHeight - window.innerHeight;
    const p = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
    vein.style.setProperty("--ichor", p.toFixed(4));
  };
  const schedule = () => {
    if (!raf) raf = requestAnimationFrame(update);
  };
  update();
  window.addEventListener("scroll", schedule, { passive: true });
  window.addEventListener("resize", schedule);
}

// ---- Guardian parallax — the emblem drifts slower than the scroll ----------
function setupParallax() {
  const el = document.querySelector(".guardian-parallax");
  if (!el || reduceMotion.matches) return;
  let raf = 0;
  const update = () => {
    raf = 0;
    el.style.setProperty("--par", `${window.scrollY * 0.18}px`);
  };
  const schedule = () => {
    if (!raf) raf = requestAnimationFrame(update);
  };
  update();
  window.addEventListener("scroll", schedule, { passive: true });
}

// ---- The awakening — guardian cold-boot, then reveal ------------------------
function setupAwakening() {
  const root = document.documentElement;
  const overlay = document.querySelector(".awaken");
  if (!overlay || !root.classList.contains("awakening")) return;

  let finished = false;
  const events = ["click", "keydown", "wheel", "touchstart"];
  function finish() {
    if (finished) return;
    finished = true;
    overlay.classList.add("awaken--out");
    try {
      sessionStorage.setItem("talosAwoke", "1");
    } catch (e) {
      /* private mode — fine, it just replays next load */
    }
    window.setTimeout(() => {
      root.classList.remove("awakening");
      overlay.remove();
    }, 480);
    events.forEach((e) => window.removeEventListener(e, finish));
  }
  events.forEach((e) => window.addEventListener(e, finish, { passive: true }));
  window.setTimeout(finish, 1550);
}

setupForge();
setupAwakening();
setupVein();
setupParallax();
setupLiveTerminal();
setupTurnTabs();
setupSectionNav();
setupReveal();
setupStagger();
setupPointerSheen();
