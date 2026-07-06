import "./fonts.css";
import "./styles.css";
import { setupSmoke } from "./smoke.js";
import { setupMycelium } from "./mycelium.js";
import { setupRitualMenu } from "./menu.js";

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
    '<span class="t-rail">┌─ answer ─────────────────────</span>',
    '<span class="t-rail">│</span> Local-first CLI workspace operator.',
    '<span class="t-rail">│</span> Java 21 sources under <span class="t-cyan">src/</span>. Notes under <span class="t-cyan">docs/</span>.',
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

    // Phase 1. Type the question.
    for (let i = 0; i < heroTurn.question.length; i += 1) {
      after(160 + i * 34, () => {
        typed += heroTurn.question[i];
        drawPrompt();
      });
    }

    // Phase 2. Reveal the lanes one at a time.
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
    '<span class="t-rail">┌─ answer ─────────────────────</span>',
    '<span class="t-rail">│</span> Local-first CLI workspace operator.',
    '<span class="t-rail">│</span> Java 21 sources under <span class="t-cyan">src/</span>. Notes under <span class="t-cyan">docs/</span>.',
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
    '<span class="t-amber">│</span> Allow?  <span class="t-body">[y=yes, a=yes for session, N=no]</span> _',
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

// ---- Planned install command tabs ------------------------------------------
function activeSetupCommand() {
  return document.querySelector("#setup-command code")?.textContent ?? "";
}

async function copyTextToClipboard(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  textarea.style.top = "0";
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand("copy");
  textarea.remove();
  if (!copied) throw new Error("copy command failed");
}

function setSetupCopyState(copied) {
  const copy = document.querySelector("[data-setup-copy]");
  if (!copy) return;
  copy.dataset.copied = String(copied);
  copy.title = copied ? "Copied" : "Copy command";
}

function setSetupPlatform(nextPlatform) {
  const panel = document.querySelector("#setup-command");
  const code = panel?.querySelector("code");
  const tabs = Array.from(document.querySelectorAll("[data-setup-platform]"));
  const activeTab = tabs.find((tab) => tab.dataset.setupPlatform === nextPlatform);
  const command = activeTab?.dataset.setupCommand;
  const copy = document.querySelector("[data-setup-copy]");

  if (!panel || !code || !activeTab || !command) return;

  code.textContent = command;
  panel.setAttribute("aria-labelledby", activeTab.id);
  if (copy) {
    copy.setAttribute("aria-label", `Copy ${activeTab.textContent.trim()} install command`);
    setSetupCopyState(false);
  }

  tabs.forEach((tab) => {
    const selected = tab === activeTab;
    tab.setAttribute("aria-selected", String(selected));
    tab.tabIndex = selected ? 0 : -1;
  });
}

function handleSetupTabKey(event, tabs) {
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
  setSetupPlatform(nextTab.dataset.setupPlatform);
}

function setupInstallTabs() {
  const tabs = Array.from(document.querySelectorAll("[data-setup-platform]"));
  if (!tabs.length) return;
  const copy = document.querySelector("[data-setup-copy]");
  tabs.forEach((tab) => {
    tab.addEventListener("click", () => setSetupPlatform(tab.dataset.setupPlatform));
    tab.addEventListener("keydown", (event) => handleSetupTabKey(event, tabs));
  });
  if (copy) {
    let resetTimer = 0;
    copy.addEventListener("click", async () => {
      window.clearTimeout(resetTimer);
      try {
        await copyTextToClipboard(activeSetupCommand());
        setSetupCopyState(true);
        resetTimer = window.setTimeout(() => setSetupCopyState(false), 1600);
      } catch {
        setSetupCopyState(false);
      }
    });
  }
  setSetupPlatform("windows");
}

// ---- Scroll-spy nav + reveal-on-scroll (native scroll, no hijacking) --------
function setupSectionNav() {
  const navLinks = Array.from(document.querySelectorAll("[data-section-nav]"));
  const sections = Array.from(document.querySelectorAll("main section[id]"));
  if (!navLinks.length || !sections.length) return;

  const setActive = (id) => {
    navLinks.forEach((link) => {
      const on = link.getAttribute("href") === `#${id}`;
      link.classList.toggle("is-active", on);
      if (on) link.setAttribute("aria-current", "page");
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

// ---- Staggered reveal, child cascades on the card grids -------------------
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
    document.querySelectorAll(".boundary-band, .doc-card, .terminal-card"),
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

// ---- The ichor vein, scroll fills the guardian's single vein ---------------
function setupVein() {
  const vein = document.querySelector(".vein");
  if (!vein) return;
  let raf = 0;
  const update = () => {
    raf = 0;
    const max = document.documentElement.scrollHeight - window.innerHeight;
    const p = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
    const v = p.toFixed(4);
    vein.style.setProperty("--ichor", v);
    // Also publish on :root so the mobile top-edge ichor filament can read it.
    document.documentElement.style.setProperty("--ichor", v);
  };
  const schedule = () => {
    if (!raf) raf = requestAnimationFrame(update);
  };
  update();
  window.addEventListener("scroll", schedule, { passive: true });
  window.addEventListener("resize", schedule);
}

// ---- The vein rail, section dots ride the spine at their scroll position ----
function setupVeinRail() {
  const rail = document.querySelector(".vein-rail");
  if (!rail) return;
  const stops = Array.from(rail.querySelectorAll(".vein-stop"));
  if (!stops.length) return;
  const VEIN_LABEL_REVEAL_Y = 96;
  let fracs = stops.map(() => 0);

  const layout = () => {
    const max = document.documentElement.scrollHeight - window.innerHeight;
    fracs = stops.map((stop) => {
      const sec = document.querySelector(stop.getAttribute("href"));
      if (!sec || max <= 0) return 0;
      const top = sec.getBoundingClientRect().top + window.scrollY;
      return Math.min(0.985, Math.max(0, top / max));
    });
    stops.forEach((stop, i) => { stop.style.top = `${(fracs[i] * 100).toFixed(2)}%`; });
    rail.classList.add("is-ready");
  };
  const paint = () => {
    const max = document.documentElement.scrollHeight - window.innerHeight;
    const p = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
    rail.classList.toggle("has-scrolled", window.scrollY >= VEIN_LABEL_REVEAL_Y);
    stops.forEach((stop, i) => stop.classList.toggle("is-reached", p >= fracs[i] - 0.0005));
  };
  let raf = 0;
  const onScroll = () => {
    if (!raf) raf = requestAnimationFrame(() => { raf = 0; paint(); });
  };

  layout();
  paint();
  window.addEventListener("scroll", onScroll, { passive: true });
  document.addEventListener("scroll", onScroll, { passive: true, capture: true });
  window.addEventListener("resize", () => { layout(); paint(); });
  window.addEventListener("load", () => { layout(); paint(); });
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(() => { layout(); paint(); });
  }
}

// ---- Guardian parallax, the emblem drifts slower than the scroll ----------
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

// ---- The awakening, guardian cold-boot, then reveal ------------------------
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
      /* private mode. Fine, it just replays next load */
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

// ---- Hero inscription, the TALOS acrostic cycles its guarantee word -------
function setupInscription() {
  const el = document.querySelector("[data-inscription-cycle]");
  if (!el) return;
  const words = ["TRACED", "APPROVED", "LOCAL", "OBSERVABLE", "SCOPED"];
  let i = 0;
  el.textContent = words[0];
  if (reduceMotion.matches) return;
  window.setInterval(() => {
    el.style.opacity = "0";
    window.setTimeout(() => {
      i = (i + 1) % words.length;
      el.textContent = words[i];
      el.style.opacity = "1";
    }, 350);
  }, 1700);
}

// ---- The execution cycle dial, a pulse fires through the six stations ------
function setupCycle() {
  const dial = document.querySelector(".dial");
  if (!dial) return;
  const detail = document.querySelector("[data-cycle-detail]");
  const stepEl = detail && detail.querySelector(".cycle-detail-step");
  const textEl = detail && detail.querySelector(".cycle-detail-text");
  const stations = Array.from(dial.querySelectorAll(".dial-station"));
  const pulse = dial.querySelector(".dial-pulse");
  const controls = document.querySelector("[data-cycle-controls]");
  const playBtn = controls && controls.querySelector("[data-cycle-play]");
  const prevBtn = controls && controls.querySelector("[data-cycle-prev]");
  const nextBtn = controls && controls.querySelector("[data-cycle-next]");
  const statusEl = controls && controls.querySelector("[data-cycle-status]");
  const R = 140;
  const CX = 210;
  const CY = 210;
  const steps = [
    ["01", "Classify", "Resolve the request into a bounded task contract and expected target."],
    ["02", "Inspect", "Gather read-only workspace evidence before proposing action."],
    ["03", "Approve", "Show mutation intent, target path, and risk before local writes."],
    ["04", "Mutate", "Run only the approved file, workspace, or command operation."],
    ["05", "Verify", "Read back files or inspect command output before reporting success."],
    ["06", "Trace", "Keep prompts, tool calls, approvals, and outcomes inspectable."],
  ];
  const angle = (i) => ((i * 60 - 90) * Math.PI) / 180;
  let active = -1;
  let playing = false;
  let hidden = false;
  let inView = true;
  let theta = angle(0);
  let raf = 0;

  const placePulse = () => {
    if (!pulse) return;
    pulse.setAttribute("cx", (CX + R * Math.cos(theta)).toFixed(1));
    pulse.setAttribute("cy", (CY + R * Math.sin(theta)).toFixed(1));
  };
  const setActive = (i) => {
    if (i === active) return;
    active = i;
    stations.forEach((el, k) => el.classList.toggle("is-active", k === i));
    if (stepEl) stepEl.innerHTML = `<span class="cycle-detail-num">${steps[i][0]}</span> ${steps[i][1]}`;
    if (textEl) textEl.textContent = steps[i][2];
  };
  const selectStep = (i) => {
    theta = angle(i);
    setActive(i);
    placePulse();
  };
  const reflectMode = () => {
    if (controls) controls.dataset.playing = playing ? "true" : "false";
    dial.classList.toggle("is-paused", !playing);
    if (playBtn) {
      playBtn.setAttribute("aria-label", playing ? "Pause" : "Play");
      playBtn.setAttribute("aria-pressed", playing ? "true" : "false");
    }
    if (statusEl) statusEl.textContent = playing ? "auto" : "manual";
  };
  const shouldRun = () => playing && !hidden && inView;
  const frame = () => {
    theta += 0.0065;
    placePulse();
    let best = 0;
    let bd = Infinity;
    for (let i = 0; i < 6; i += 1) {
      const d = Math.abs(Math.atan2(Math.sin(theta - angle(i)), Math.cos(theta - angle(i))));
      if (d < bd) { bd = d; best = i; }
    }
    setActive(best);
    raf = shouldRun() ? requestAnimationFrame(frame) : 0;
  };
  const syncLoop = () => {
    if (shouldRun() && !raf) raf = requestAnimationFrame(frame);
    else if (!shouldRun() && raf) { cancelAnimationFrame(raf); raf = 0; }
  };
  const play = () => {
    if (playing || reduceMotion.matches) return;
    playing = true;
    reflectMode();
    syncLoop();
  };
  const pause = () => {
    playing = false;
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
    reflectMode();
  };
  const toggle = () => (playing ? pause() : play());

  stations.forEach((el, i) => {
    el.setAttribute("role", "button");
    el.setAttribute("tabindex", "0");
    el.setAttribute("aria-label", `Step ${steps[i][0]}, ${steps[i][1]}`);
    el.addEventListener("mouseenter", () => { if (!playing) setActive(i); });
    el.addEventListener("click", () => { pause(); selectStep(i); });
    el.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pause(); selectStep(i); }
    });
  });
  if (playBtn) playBtn.addEventListener("click", toggle);
  if (prevBtn) prevBtn.addEventListener("click", () => { pause(); selectStep((active + 5) % 6); });
  if (nextBtn) nextBtn.addEventListener("click", () => { pause(); selectStep((active + 1) % 6); });

  selectStep(0);

  if (reduceMotion.matches) {
    if (playBtn) playBtn.disabled = true;
    reflectMode();
    return;
  }

  // Stop the autoplay loop while the section is off-screen or the tab is hidden.
  document.addEventListener("visibilitychange", () => {
    hidden = document.hidden;
    syncLoop();
  });
  const execSection = document.querySelector("#execution");
  if (execSection && "IntersectionObserver" in window) {
    const io = new IntersectionObserver(
      (entries) => {
        inView = entries.some((e) => e.isIntersecting);
        syncLoop();
      },
      { threshold: 0 },
    );
    io.observe(execSection);
  }
  play();
}

// ---- The capability tree, a living trunk -> branches -> leaves of real work --
function setupScope() {
  const svg = document.querySelector(".scope");
  const detail = document.querySelector("[data-scope-detail]");
  if (!svg || !detail) return;
  const NS = "http://www.w3.org/2000/svg";
  const kindEl = detail.querySelector(".scope-detail-kind");
  const nameEl = detail.querySelector(".scope-detail-name");
  const textEl = detail.querySelector(".scope-detail-text");
  const metaEl = detail.querySelector(".scope-detail-meta");

  const root = {
    name: "Talos",
    text: "A local-by-default operator for your workspace. It reads before acting, asks before writing, and keeps model turns on localhost unless remote endpoints are explicitly allowed.",
    meta: "localhost-gated by default · workspace-bounded · approved writes · local trace",
  };
  const branches = [
    {
      name: "Explore the workspace",
      text: "Read-only grounding. Talos looks before it touches anything.",
      meta: "boundary  allow (read)",
      leaves: [
        { name: "Folders & files", text: "List a project tree and open any text file.", meta: "list_dir · read_file" },
        { name: "Search", text: "Find text and symbols across the workspace.", meta: "grep" },
        { name: "Changes", text: "Review what the last turn touched.", meta: "/last trace · diffs" },
      ],
    },
    {
      name: "Summarize documents",
      text: "Explain what a file holds, in plain language.",
      meta: "boundary  allow (read)",
      leaves: [
        { name: "Code & text", text: "Source code, Markdown, and plain text.", meta: "read_file" },
        { name: "Data files", text: "JSON, YAML, TOML, and CSV.", meta: "read_file" },
        { name: "PDF, Word, Excel", text: "PDFs, Word (.docx), and Excel (.xlsx), extracted entirely on your machine.", meta: "read_file · on-device extract" },
        { name: "PowerPoint", v1: true, text: "Reading .pptx decks. On the v1 roadmap.", meta: "planned for v1" },
        { name: "Sensitive docs", v1: true, text: "Private documents already stay on your machine today. A guided sensitive-paperwork workflow is on the v1 roadmap.", meta: "on-device today · workflow v1" },
      ],
    },
    {
      name: "Edit code",
      text: "Propose and preview, then write only what you approve.",
      meta: "boundary  ask (write)",
      leaves: [
        { name: "Reviewed edits", text: "Single-file changes behind explicit approval.", meta: "read_file · write_file" },
        { name: "Across files", text: "Coordinated edits spanning several files.", meta: "write_file" },
      ],
    },
    {
      name: "Run commands",
      text: "Run tests and builds through approved, configured profiles.",
      meta: "boundary  ask",
      leaves: [
        { name: "Tests", text: "Run a test profile and read the real output.", meta: "run_command" },
        { name: "Builds", text: "Run a build and confirm it before claiming success.", meta: "run_command" },
      ],
    },
  ];

  // ---- flatten the tree into a node + link graph ----
  const nodes = [root];
  const links = [];
  root.idx = 0; root.type = "trunk"; root.r = 22; root.kindText = "local operator";
  branches.forEach((b) => {
    b.type = "branch"; b.r = 12; b.kindText = "available now"; b.idx = nodes.length;
    nodes.push(b);
    links.push({ a: 0, b: b.idx, dist: 162 });
    b.leaves.forEach((lf) => {
      lf.type = "leaf"; lf.r = 7; lf.kindText = lf.v1 ? "scheduled for v1" : "available now"; lf.idx = nodes.length;
      nodes.push(lf);
      links.push({ a: b.idx, b: lf.idx, dist: 108, v1: lf.v1 });
    });
  });

  // ---- deterministic seed (no Math.random, so the layout is stable) ----
  const W = 620, H = 600, CX = W / 2, CY = H / 2;
  const PADX = 66, PADT = 54, PADB = 64;
  root.x = CX; root.y = CY - 120;
  branches.forEach((b, i) => {
    const ang = (i / branches.length) * Math.PI * 2 - Math.PI / 2;
    b.x = CX + Math.cos(ang) * 135;
    b.y = CY + Math.sin(ang) * 135;
    b.leaves.forEach((lf, j) => {
      const a = ang + (j - (b.leaves.length - 1) / 2) * 0.55;
      lf.x = CX + Math.cos(a) * 235;
      lf.y = CY + Math.sin(a) * 235;
    });
  });
  nodes.forEach((n) => { n.vx = 0; n.vy = 0; n.fixed = false; n.phase = n.idx * 1.7; });

  // ---- build the svg ----
  const make = (tag, attrs) => {
    const el = document.createElementNS(NS, tag);
    Object.keys(attrs).forEach((k) => el.setAttribute(k, attrs[k]));
    return el;
  };
  const edgeG = make("g", { class: "tree-edges" });
  links.forEach((l) => {
    l.el = make("line", { class: `tree-link${l.v1 ? " tree-link--v1" : ""}` });
    edgeG.appendChild(l.el);
  });
  svg.appendChild(edgeG);

  let active = null;
  const show = (d) => {
    if (d === active) return;
    active = d;
    nodes.forEach((n) => n.g.classList.toggle("is-active", n === d));
    if (kindEl) { kindEl.textContent = d.kindText; kindEl.classList.toggle("is-v1", !!d.v1); }
    if (nameEl) nameEl.textContent = d.name;
    if (textEl) textEl.textContent = d.text;
    if (metaEl) metaEl.textContent = d.meta;
  };

  nodes.forEach((n) => {
    const g = make("g", {
      class: `tree-node tree-node--${n.type}${n.v1 ? " is-v1" : ""}`,
      tabindex: "0",
      role: "button",
      "aria-label": `${n.name}, ${n.kindText}`,
    });
    g.appendChild(make("circle", { r: n.r }));
    const lab = make("text", { class: `tree-${n.type}-label`, x: 0, y: n.r + 15, "text-anchor": "middle" });
    lab.appendChild(document.createTextNode(n.name));
    if (n.v1) {
      const tag = make("tspan", { class: "leaf-tag", dx: "5" });
      tag.appendChild(document.createTextNode("v1"));
      lab.appendChild(tag);
    }
    g.appendChild(lab);
    n.g = g;
    g.addEventListener("mouseenter", () => show(n));
    g.addEventListener("focus", () => show(n));
    attachDrag(g, n);
    svg.appendChild(g);
  });

  const render = () => {
    nodes.forEach((n) => n.g.setAttribute("transform", `translate(${n.x.toFixed(1)},${n.y.toFixed(1)})`));
    links.forEach((l) => {
      const a = nodes[l.a];
      const b = nodes[l.b];
      l.el.setAttribute("x1", a.x.toFixed(1));
      l.el.setAttribute("y1", a.y.toFixed(1));
      l.el.setAttribute("x2", b.x.toFixed(1));
      l.el.setAttribute("y2", b.y.toFixed(1));
    });
  };

  // ---- force layout: repulsion + link springs + collision spacing, with a
  //      gentle perpetual drift so the graph keeps breathing. No centering pull;
  //      the whole graph is just softly recentered so it cannot wander off. ----
  const REPEL = 600;
  const LINK = 0.05;
  const FRICTION = 0.86;
  const IDLE = 0.02;
  const RECENTER = 0.06;
  const GAP = 62;
  let raf = 0;
  let running = false;
  let inView = true;
  let frame = 0;
  const tick = () => {
    frame += 1;
    const t = frame;
    for (let i = 0; i < nodes.length; i += 1) {
      for (let j = i + 1; j < nodes.length; j += 1) {
        const a = nodes[i];
        const b = nodes[j];
        let dx = b.x - a.x;
        let dy = b.y - a.y;
        let d2 = dx * dx + dy * dy;
        if (d2 < 1) d2 = 1;
        const dist = Math.sqrt(d2);
        const m = REPEL / d2;
        const ux = dx / dist;
        const uy = dy / dist;
        a.vx -= ux * m; a.vy -= uy * m;
        b.vx += ux * m; b.vy += uy * m;
      }
    }
    links.forEach((l) => {
      const a = nodes[l.a];
      const b = nodes[l.b];
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const f = ((dist - l.dist) / dist) * LINK;
      a.vx += dx * f; a.vy += dy * f;
      b.vx -= dx * f; b.vy -= dy * f;
    });
    nodes.forEach((n) => {
      if (n.fixed) return;
      n.vx += Math.sin(t * 0.018 + n.phase) * IDLE;
      n.vy += Math.cos(t * 0.015 + n.phase * 1.3) * IDLE;
    });
    nodes.forEach((n) => {
      if (n.fixed) { n.vx = 0; n.vy = 0; return; }
      n.vx *= FRICTION; n.vy *= FRICTION;
      n.x += n.vx; n.y += n.vy;
    });
    // collision: keep nodes (and their labels) clearly apart
    for (let pass = 0; pass < 2; pass += 1) {
      for (let i = 0; i < nodes.length; i += 1) {
        for (let j = i + 1; j < nodes.length; j += 1) {
          const a = nodes[i];
          const b = nodes[j];
          const dx = b.x - a.x;
          const dy = b.y - a.y;
          const dist = Math.sqrt(dx * dx + dy * dy) || 1;
          const sep = a.r + b.r + GAP;
          if (dist < sep) {
            const push = ((sep - dist) / dist) / 2;
            const ox = dx * push;
            const oy = dy * push;
            if (!a.fixed) { a.x -= ox; a.y -= oy; }
            if (!b.fixed) { b.x += ox; b.y += oy; }
          }
        }
      }
    }
    nodes.forEach((n) => {
      n.x = Math.max(PADX, Math.min(W - PADX, n.x));
      n.y = Math.max(PADT, Math.min(H - PADB, n.y));
    });
    if (!drag) {
      let mx = 0;
      let my = 0;
      nodes.forEach((n) => { mx += n.x; my += n.y; });
      mx = (CX - mx / nodes.length) * RECENTER;
      my = (CY - my / nodes.length) * RECENTER;
      nodes.forEach((n) => { n.x += mx; n.y += my; });
    }
  };
  const loop = () => { if (!running) return; tick(); render(); raf = requestAnimationFrame(loop); };
  const start = () => { if (running || reduceMotion.matches || !inView) return; running = true; raf = requestAnimationFrame(loop); };
  const stop = () => { running = false; if (raf) cancelAnimationFrame(raf); raf = 0; };
  const settle = (count) => { for (let k = 0; k < count; k += 1) tick(); render(); };

  // ---- drag: click-hold to move a node; a click without movement selects it ----
  const point = (e) => {
    const p = svg.createSVGPoint();
    p.x = e.clientX; p.y = e.clientY;
    const m = svg.getScreenCTM();
    return m ? p.matrixTransform(m.inverse()) : { x: e.clientX, y: e.clientY };
  };
  let drag = null;
  let moved = false;
  let sx = 0;
  let sy = 0;
  function attachDrag(g, n) {
    g.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      drag = n; n.fixed = true; moved = false;
      const p = point(e); sx = p.x; sy = p.y; n.x = p.x; n.y = p.y;
      g.classList.add("is-dragging");
      try { g.setPointerCapture(e.pointerId); } catch (_) { /* unsupported */ }
      if (reduceMotion.matches) render(); else start();
    });
    g.addEventListener("pointermove", (e) => {
      if (drag !== n) return;
      const p = point(e); n.x = p.x; n.y = p.y;
      if (Math.hypot(p.x - sx, p.y - sy) > 4) moved = true;
      if (reduceMotion.matches) render(); else start();
    });
    const end = (e) => {
      if (drag !== n) return;
      drag = null; n.fixed = false;
      g.classList.remove("is-dragging");
      try { g.releasePointerCapture(e.pointerId); } catch (_) { /* noop */ }
      if (!moved) show(n);
      if (reduceMotion.matches) settle(90); else start();
    };
    g.addEventListener("pointerup", end);
    g.addEventListener("pointercancel", end);
  }

  // ---- init: settle to a clean static layout immediately, then (unless reduced
  //      motion) let it breathe gently while the section is on screen. ----
  render();
  show(root);
  settle(300);
  if (reduceMotion.matches) return;
  const sec = document.querySelector("#good-fits");
  if (sec && "IntersectionObserver" in window) {
    const io = new IntersectionObserver((entries) => {
      inView = entries.some((x) => x.isIntersecting);
      if (inView) start(); else stop();
    }, { threshold: 0 });
    io.observe(sec);
  } else {
    start();
  }
  document.addEventListener("visibilitychange", () => { if (document.hidden) stop(); else start(); });
}

setupSmoke();
setupMycelium();
setupAwakening();
setupCycle();
setupScope();
setupVein();
setupVeinRail();
setupRitualMenu();
setupParallax();
setupLiveTerminal();
setupInscription();
setupTurnTabs();
setupInstallTabs();
setupSectionNav();
setupReveal();
setupStagger();
setupPointerSheen();
