// The ritual menu. On mobile the Talos head re-summons the guardian: tapping it
// opens a full-screen, cold-boot-style section menu. Shared by index.html and
// docs.html. The overlay is inert (visibility:hidden) until opened, so no `hidden`
// attribute juggling and the open/close can cross-fade. Focus is trapped while
// open, Escape and the backdrop close it, and the body is scroll-locked.
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

export function setupRitualMenu() {
  const trigger = document.querySelector(".menu-trigger");
  const menu = document.querySelector("#ritual-menu");
  if (!trigger || !menu) return;
  const root = document.documentElement;
  const closeBtn = menu.querySelector(".ritual-close");
  let lastFocus = null;

  const focusable = () => Array.from(menu.querySelectorAll('a[href], button:not([disabled])'));

  const onKey = (e) => {
    if (e.key === "Escape") { e.preventDefault(); close(); return; }
    if (e.key !== "Tab") return;
    const items = focusable();
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };

  const open = () => {
    if (menu.classList.contains("is-open")) return;
    lastFocus = document.activeElement;
    menu.classList.add("is-open");
    trigger.setAttribute("aria-expanded", "true");
    root.classList.add("menu-open");
    // is-open flips visibility to visible immediately (transition-delay 0), so the
    // close button is focusable synchronously here.
    (closeBtn || menu).focus();
    document.addEventListener("keydown", onKey);
  };

  const close = () => {
    if (!menu.classList.contains("is-open")) return;
    menu.classList.remove("is-open");
    trigger.setAttribute("aria-expanded", "false");
    root.classList.remove("menu-open");
    document.removeEventListener("keydown", onKey);
    // The trigger is the only opener, so return focus there (falls back to whatever
    // had focus before, for safety).
    const back = trigger || lastFocus;
    if (back && typeof back.focus === "function") back.focus();
  };

  trigger.addEventListener("click", () => (menu.classList.contains("is-open") ? close() : open()));
  if (closeBtn) closeBtn.addEventListener("click", close);
  menu.addEventListener("click", (e) => {
    if (e.target === menu) close();
    else if (e.target.closest("a[href]")) close();
  });
}
