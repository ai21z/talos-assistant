import "./styles.css";

document.documentElement.classList.add("js");

// Terminal turn examples — semantic lane grammar.
// Glyphs match src/main/java/dev/talos/cli/ui/SemanticGlyphSet.java safe Unicode:
//   bullet •  arrow →  success ✓  warning !  error x  rail │  dot ·
// Prompt matches src/main/java/dev/talos/cli/ui/PromptRenderer.java: "talos [auto] >".
const terminalStates = {
  inspect: [
    '<span class="t-prompt-name">talos</span> <span class="t-prompt-mode">[auto]</span> &gt; what does this workspace do?',
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
    '<span class="t-prompt-name">talos</span> <span class="t-prompt-mode">[auto]</span> &gt; create docs/summary.md from this repo',
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
    '<span class="t-prompt-name">talos</span> <span class="t-prompt-mode">[auto]</span> &gt; run the approved gradle test command',
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
    '<span class="t-prompt-name">talos</span> <span class="t-prompt-mode">[auto]</span> &gt; /last trace',
    "",
    '<span class="t-bronze">trace</span>',
    '<span class="t-muted">  prompt frame      auto · workspace bounded</span>',
    '<span class="t-muted">  tool surface      list_dir, read_file, grep, retrieve, write_file</span>',
    '<span class="t-muted">  tool calls        read_file × 2 · write_file × 1</span>',
    '<span class="t-amber">  approvals         write docs/summary.md · accepted</span>',
    '<span class="t-green">  verification      readback ok · expected target matched</span>',
    "",
    '<span class="t-bronze">debug</span>',
    '<span class="t-muted">  prompt-debug      available · use /prompt-debug last</span>',
  ].join("\n"),
};

const toast = document.querySelector(".toast");
let toastTimer;

function showToast(message) {
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add("toast--visible");
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toast.classList.remove("toast--visible");
  }, 3000);
}

function setTerminalState(nextState) {
  const panel = document.querySelector("#terminal-output");
  const status = document.querySelector("#terminal-status");
  const tabs = Array.from(document.querySelectorAll("[data-terminal-state]"));
  const activeTab = tabs.find((tab) => tab.dataset.terminalState === nextState);

  if (!panel || !activeTab || !terminalStates[nextState]) return;

  // innerHTML is safe here: all source strings are hard-coded constants above.
  panel.innerHTML = terminalStates[nextState];
  panel.setAttribute("aria-labelledby", activeTab.id);
  if (status) {
    status.textContent = `${activeTab.textContent.trim()} turn selected.`;
  }

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

const tabs = Array.from(document.querySelectorAll("[data-terminal-state]"));
tabs.forEach((tab) => {
  tab.addEventListener("click", () => setTerminalState(tab.dataset.terminalState));
  tab.addEventListener("keydown", (event) => handleTabKey(event, tabs));
});

// Render the initial Inspect turn so the static markup does not have to embed colored HTML.
if (tabs.length) {
  setTerminalState("inspect");
}

document.querySelectorAll("[data-copy]").forEach((button) => {
  button.addEventListener("click", async () => {
    const command = button.dataset.copy;
    if (!command) return;

    try {
      await navigator.clipboard.writeText(command);
      showToast("Command copied.");
    } catch {
      showToast("Copy unavailable in this browser.");
    }
  });
});

const revealTargets = document.querySelectorAll(".reveal");
if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("reveal--visible");
        observer.unobserve(entry.target);
      });
    },
    { threshold: 0.14 },
  );

  revealTargets.forEach((target) => observer.observe(target));
} else {
  revealTargets.forEach((target) => target.classList.add("reveal--visible"));
}
