// The mycelium. Colonies of fungal growth joined by ominous threads. Each
// colony glows with a nebula (green, teal, or a rare bronze dust), its hyphae
// fat at the core and fine at the tips. As you scroll down the green colonies
// colonise and expand while the bronze dust in the hero recedes, counter-
// motion that keeps it alive. Filaments reach toward the cursor; signals fire
// between colonies. Canvas 2D, no GPU. Static frame under reduced motion.

const PALETTE = {
  green: { neb: [95, 130, 95], fil: [135, 160, 132] },
  teal: { neb: [50, 92, 84], fil: [86, 138, 126] },
};
const W_THICK = 1.9;
const W_THIN = 0.35;

export function setupMycelium() {
  const canvas = document.querySelector(".mycelium");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    canvas.remove();
    return;
  }
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  const coarse = window.matchMedia("(pointer: coarse)").matches;
  // Canvas-2D exposes no renderer string, so the low-power escape is heuristic
  // (very few cores) plus an adaptive watchdog that freezes on sustained jank.
  const lowPower = (navigator.hardwareConcurrency || 8) <= 2;

  const neb = document.createElement("canvas");
  const nctx = neb.getContext("2d");
  const NEB_SCALE = 0.4;

  let W = 0;
  let H = 0;
  let colonies = [];
  let nodes = [];
  let intra = [];
  let inter = [];
  let signals = [];
  const pointer = { x: -1, y: -1, active: false };
  let growth = 0;

  const rand = (a, b) => a + Math.random() * (b - a);
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const localExpand = (c) => (c.dir > 0 ? growth : 1 - growth);
  const widthOf = (n, c) => lerp(W_THICK, W_THIN, clamp(Math.hypot(n.x - c.x, n.y - c.y) / c.r, 0, 1));

  function spawnSignal() {
    const e = inter[(Math.random() * inter.length) | 0] || [0, 0];
    return { e, t: Math.random() * 0.3, speed: rand(0.32, 0.6) };
  }

  function makeColony(i) {
    // Green/teal colonies only. The bronze dust now lives in the smoke layer.
    const cx = rand(W * 0.07, W * 0.93);
    const cy = rand(H * 0.07, H * 0.93);
    const r = rand(70, 150);
    const color = [PALETTE.green, PALETTE.green, PALETTE.green, PALETTE.teal][(Math.random() * 4) | 0];
    const dir = Math.random() < 0.3 ? -1 : 1;
    const col = { x: cx, y: cy, r, flash: 0, color, dir, nodes: [], blobs: [] };
    const nc = Math.round(rand(5, 9));
    for (let j = 0; j < nc; j += 1) {
      const ang = rand(0, 6.2832);
      const d = rand(8, r);
      const x = cx + Math.cos(ang) * d;
      const y = cy + Math.sin(ang) * d;
      col.nodes.push({ x, y, bx: x, by: y, col: i });
    }
    const bc = Math.round(rand(4, 6));
    for (let k = 0; k < bc; k += 1) {
      col.blobs.push({
        ox: rand(-r * 0.5, r * 0.5),
        oy: rand(-r * 0.5, r * 0.5),
        r: rand(r * 0.55, r * 1.2),
        ph: rand(0, 6.2832),
        sp: rand(0.15, 0.4),
      });
    }
    return col;
  }

  function build() {
    const colCount = clamp(Math.round((W * H) / 220000), 5, 9);
    colonies = [];
    for (let i = 0; i < colCount; i += 1) colonies.push(makeColony(i));

    nodes = [];
    colonies.forEach((c) => c.nodes.forEach((n) => nodes.push(n)));

    intra = [];
    const seen = new Set();
    colonies.forEach((c) => {
      c.nodes.forEach((n) => {
        c.nodes
          .filter((m) => m !== n)
          .map((m) => ({ m, d: (m.x - n.x) ** 2 + (m.y - n.y) ** 2 }))
          .sort((a, b) => a.d - b.d)
          .slice(0, 2)
          .forEach(({ m }) => {
            const a = nodes.indexOf(n);
            const b = nodes.indexOf(m);
            const key = `${Math.min(a, b)}-${Math.max(a, b)}`;
            if (!seen.has(key)) {
              seen.add(key);
              intra.push([a, b]);
            }
          });
      });
    });

    inter = [];
    const iseen = new Set();
    colonies.forEach((c, i) => {
      colonies
        .map((d, j) => ({ j, dist: (d.x - c.x) ** 2 + (d.y - c.y) ** 2 }))
        .filter((o) => o.j !== i)
        .sort((a, b) => a.dist - b.dist)
        .slice(0, 2)
        .forEach((o) => {
          const key = `${Math.min(i, o.j)}-${Math.max(i, o.j)}`;
          if (!iseen.has(key)) {
            iseen.add(key);
            inter.push([Math.min(i, o.j), Math.max(i, o.j)]);
          }
        });
    });

    signals = [];
    for (let i = 0; i < Math.max(3, inter.length); i += 1) signals.push(spawnSignal());
  }

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 1.5);
    W = window.innerWidth;
    H = window.innerHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    canvas.style.width = `${W}px`;
    canvas.style.height = `${H}px`;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    neb.width = Math.max(1, Math.round(W * NEB_SCALE));
    neb.height = Math.max(1, Math.round(H * NEB_SCALE));
    build();
  }

  function updateNodes() {
    const reach = pointer.active && !coarse ? 220 : 0;
    nodes.forEach((n) => {
      const c = colonies[n.col];
      const le = localExpand(c);
      let tx = n.bx + (n.bx - c.x) * le * 0.55;
      let ty = n.by + (n.by - c.y) * le * 0.55;
      if (reach) {
        const dx = pointer.x - n.bx;
        const dy = pointer.y - n.by;
        const dist = Math.hypot(dx, dy);
        if (dist < reach) {
          const f = (1 - dist / reach) * 0.5;
          tx += dx * f;
          ty += dy * f;
        }
      }
      n.x += (tx - n.x) * 0.08;
      n.y += (ty - n.y) * 0.08;
    });
  }

  function taper(ax, ay, aw, bx, by, bw) {
    const dx = bx - ax;
    const dy = by - ay;
    const len = Math.hypot(dx, dy) || 1;
    const px = -dy / len;
    const py = dx / len;
    ctx.beginPath();
    ctx.moveTo(ax + px * aw, ay + py * aw);
    ctx.lineTo(ax - px * aw, ay - py * aw);
    ctx.lineTo(bx - px * bw, by - py * bw);
    ctx.lineTo(bx + px * bw, by + py * bw);
    ctx.closePath();
    ctx.fill();
  }

  function drawNebula(t, breath) {
    nctx.clearRect(0, 0, neb.width, neb.height);
    nctx.globalCompositeOperation = "lighter";
    colonies.forEach((c) => {
      const le = localExpand(c);
      const intensity = (0.5 + 0.45 * breath) * (1 + le * 0.7) + c.flash * 0.8;
      const [r0, g0, b0] = c.color.neb;
      const baseA = 0.032;
      c.blobs.forEach((b) => {
        const dx = Math.cos(t * b.sp + b.ph) * c.r * 0.12;
        const dy = Math.sin(t * b.sp * 0.8 + b.ph) * c.r * 0.12;
        const x = (c.x + b.ox + dx) * NEB_SCALE;
        const y = (c.y + b.oy + dy) * NEB_SCALE;
        const r = b.r * (1 + le * 0.5) * NEB_SCALE;
        const g = nctx.createRadialGradient(x, y, 0, x, y, r);
        g.addColorStop(0, `rgba(${r0}, ${g0}, ${b0}, ${baseA * intensity})`);
        g.addColorStop(0.5, `rgba(${r0}, ${g0}, ${b0}, ${baseA * 0.5 * intensity})`);
        g.addColorStop(1, `rgba(${r0}, ${g0}, ${b0}, 0)`);
        nctx.fillStyle = g;
        nctx.beginPath();
        nctx.arc(x, y, r, 0, 6.2832);
        nctx.fill();
      });
      c.flash *= 0.93;
    });
    nctx.globalCompositeOperation = "source-over";
    ctx.globalAlpha = 0.55;
    ctx.drawImage(neb, 0, 0, neb.width, neb.height, 0, 0, W, H);
    ctx.globalAlpha = 1;
  }

  function drawNetwork(breath) {
    // hyphae, tapered strips, fat at the core, fine at the tips
    intra.forEach(([a, b]) => {
      const na = nodes[a];
      const nb = nodes[b];
      const c = colonies[na.col];
      const f = c.color.fil;
      ctx.fillStyle = `rgba(${f[0]}, ${f[1]}, ${f[2]}, ${0.06 * breath})`;
      taper(na.x, na.y, widthOf(na, c), nb.x, nb.y, widthOf(nb, c));
    });
    // thick core hubs
    colonies.forEach((c) => {
      const f = c.color.fil;
      ctx.fillStyle = `rgba(${f[0]}, ${f[1]}, ${f[2]}, ${0.08 * breath})`;
      ctx.beginPath();
      ctx.arc(c.x, c.y, 2.2, 0, 6.2832);
      ctx.fill();
    });
    // ominous threads between colonies
    ctx.lineWidth = 1;
    ctx.strokeStyle = `rgba(140, 168, 138, ${0.05 * breath})`;
    ctx.beginPath();
    inter.forEach(([i, j]) => {
      ctx.moveTo(colonies[i].x, colonies[i].y);
      ctx.lineTo(colonies[j].x, colonies[j].y);
    });
    ctx.stroke();
    // fine tip nodes, the acmes. Kept faint so they never fight diagram text
    nodes.forEach((n) => {
      const c = colonies[n.col];
      const f = c.color.fil;
      ctx.fillStyle = `rgba(${f[0]}, ${f[1]}, ${f[2]}, ${0.07 * breath})`;
      ctx.beginPath();
      ctx.arc(n.x, n.y, Math.max(0.6, widthOf(n, c) * 0.7), 0, 6.2832);
      ctx.fill();
    });
  }

  function drawSignals() {
    signals.forEach((s) => {
      const a = colonies[s.e[0]];
      const b = colonies[s.e[1]];
      if (!a || !b) return;
      const x = a.x + (b.x - a.x) * s.t;
      const y = a.y + (b.y - a.y) * s.t;
      const tx = a.x + (b.x - a.x) * Math.max(0, s.t - 0.08);
      const ty = a.y + (b.y - a.y) * Math.max(0, s.t - 0.08);
      const grad = ctx.createLinearGradient(tx, ty, x, y);
      grad.addColorStop(0, "rgba(170, 205, 165, 0)");
      grad.addColorStop(1, "rgba(170, 205, 165, 0.2)");
      ctx.strokeStyle = grad;
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(tx, ty);
      ctx.lineTo(x, y);
      ctx.stroke();
      const g = ctx.createRadialGradient(x, y, 0, x, y, 5.5);
      g.addColorStop(0, "rgba(180, 215, 175, 0.26)");
      g.addColorStop(1, "rgba(180, 215, 175, 0)");
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(x, y, 5.5, 0, 6.2832);
      ctx.fill();
    });
  }

  let raf = 0;
  let t0 = 0;
  let running = false;
  let degraded = false;
  let lastFrameT = 0;
  let slow = 0;
  let sraf = 0;

  function step(t) {
    // Adaptive watchdog: if the device cannot sustain the loop, freeze it.
    if (lastFrameT) {
      if (t - lastFrameT > 40) slow += 1;
      else if (slow > 0) slow -= 2;
      if (slow >= 48) { degrade(); return; }
    }
    lastFrameT = t;
    const sec = (t - t0) / 1000;
    const breath = 0.7 + 0.3 * Math.sin(sec * 0.55);
    const maxScroll = document.documentElement.scrollHeight - window.innerHeight;
    const prog = maxScroll > 0 ? clamp(window.scrollY / maxScroll, 0, 1) : 0;
    growth = clamp(growth + (prog - growth) * 0.05, 0, 1);

    updateNodes();
    ctx.clearRect(0, 0, W, H);
    drawNebula(sec, breath);
    drawNetwork(breath);
    signals.forEach((s) => {
      s.t += s.speed * 0.008;
      if (s.t >= 1) {
        if (colonies[s.e[1]]) colonies[s.e[1]].flash = 1;
        Object.assign(s, spawnSignal());
      }
    });
    drawSignals();
    raf = requestAnimationFrame(step);
  }

  function start() {
    if (running || reduce.matches || degraded) return;
    running = true;
    t0 = performance.now();
    lastFrameT = 0;
    raf = requestAnimationFrame(step);
  }
  function stop() {
    running = false;
    if (raf) cancelAnimationFrame(raf);
    raf = 0;
  }
  function staticFrame() {
    const maxScroll = document.documentElement.scrollHeight - window.innerHeight;
    growth = maxScroll > 0 ? clamp(window.scrollY / maxScroll, 0, 1) : 0;
    updateNodes();
    ctx.clearRect(0, 0, W, H);
    drawNebula(4, 0.85);
    drawNetwork(0.85);
    drawSignals();
  }
  function staticScroll() {
    if (sraf) return;
    sraf = requestAnimationFrame(() => { sraf = 0; if (degraded) staticFrame(); });
  }
  function degrade() {
    if (degraded) return;
    degraded = true;
    stop();
    staticFrame();
    // Keep the scroll-driven growth alive without animating, like the smoke layer.
    window.addEventListener("scroll", staticScroll, { passive: true });
  }

  resize();
  if (reduce.matches) staticFrame();
  else if (lowPower) degrade();
  else start();

  window.addEventListener("resize", () => {
    resize();
    if (!running) staticFrame();
  });
  if (!coarse) {
    window.addEventListener(
      "pointermove",
      (e) => {
        pointer.x = e.clientX;
        pointer.y = e.clientY;
        pointer.active = true;
      },
      { passive: true },
    );
    window.addEventListener("pointerleave", () => {
      pointer.active = false;
    });
    document.addEventListener("mouseout", (e) => {
      if (!e.relatedTarget) pointer.active = false;
    });
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) stop();
    else start();
  });
  reduce.addEventListener("change", () => {
    if (reduce.matches) {
      stop();
      staticFrame();
    } else {
      start();
    }
  });
}
