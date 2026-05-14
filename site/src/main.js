import "./styles.css";

document.documentElement.classList.add("js");

const terminalStates = {
  inspect: `talos [auto] > what does this workspace do?

runtime:
  classify request
  expose read-only tools
  inspect local files inside the workspace

allowed tools:
  talos.list_dir, talos.read_file, talos.grep, talos.retrieve

write status:
  no mutation tools exposed`,
  approve: `talos [auto] > create docs/summary.md from this repo

contract:
  target: docs/summary.md
  expected action: talos.write_file

approval required:
  action: write operation
  risk: mutates one workspace file
  preview: docs/summary.md

choice:
  allow? [y=yes, a=yes for session, N=no] _`,
  verify: `talos [auto] > run the approved Gradle test command profile

command profile:
  gradle_test

tool:
  talos.run_command

checks:
  runtime command result
  bounded profile output
  pass/fail status from process evidence

result:
  runtime-owned outcome report`,
  trace: `talos [auto] > /last trace

trace:
  prompt frame
  tool surface
  approvals
  tool calls
  verification results

debug:
  prompt-debug evidence available when enabled`,
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
  const output = document.querySelector("#terminal-output code");
  const panel = document.querySelector("#terminal-output");
  const tabs = Array.from(document.querySelectorAll("[data-terminal-state]"));
  const activeTab = tabs.find((tab) => tab.dataset.terminalState === nextState);

  if (!output || !panel || !activeTab || !terminalStates[nextState]) return;

  output.textContent = terminalStates[nextState];
  panel.setAttribute("aria-labelledby", activeTab.id);

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
