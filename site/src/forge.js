// The forge — a single persistent WebGL2 field that lives behind the whole
// page. Molten bronze that flows continuously, drifts with scroll position
// (parallax), glows hotter and streaks with scroll velocity, and shifts mood
// per section (warm hero, calm middle, molten core, resolved base). Replaces
// the section divider lines: continuity, not chops. Rendered at reduced
// resolution for cost, kept dark so body text stays readable, frozen to one
// frame under reduced motion, and removed silently if WebGL2 is unavailable
// (the CSS body gradient remains as the fallback background).

const VERT = `#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}`;

const FRAG = `#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uScroll;
uniform float uVel;
out vec4 frag;

float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p);
  float a = hash(i), b = hash(i + vec2(1.0, 0.0)), c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p){
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.0; a *= 0.5; }
  return v;
}

void main(){
  vec2 uv = gl_FragCoord.xy / uRes.xy;
  vec2 p = vec2(uv.x * uRes.x / uRes.y, uv.y) * 1.55;
  p.y += uScroll * 2.4;                 // parallax: descend through the field
  p.x *= 1.0 - uVel * 0.22;             // streak horizontally on fast scroll
  float t = uTime * 0.03;

  vec2 q = vec2(fbm(p + t), fbm(p + vec2(5.2, 1.3) - t));
  float n = fbm(p + 1.5 * q);
  float fil = fbm(p * 1.4 + 2.0 * q);

  // mood: warm hero (top), calm middle, molten core, resolved base
  float hero = smoothstep(0.16, 0.0, uScroll);
  float core = clamp(1.0 - abs(uScroll - 0.56) * 1.5, 0.0, 1.0);
  float heat = clamp(max(hero * 0.85, core), 0.0, 1.0);

  vec3 bg       = vec3(0.022, 0.028, 0.035);
  vec3 bronze   = vec3(0.80, 0.56, 0.31);
  vec3 bronzeLo = vec3(0.33, 0.22, 0.11);
  vec3 cyan     = vec3(0.37, 0.69, 0.81);

  vec3 col = mix(bronzeLo, bronze, smoothstep(0.42, 0.86, n));
  col = mix(col, cyan, smoothstep(0.80, 0.97, fil) * 0.16 * (1.0 - heat));
  float field = smoothstep(0.40, 0.92, n);
  float amp = 0.20 + heat * 0.26 + uVel * 0.30;
  col = mix(bg, col, field * amp);

  // keep the central reading column calmer; let the forge breathe in the gutters
  float gutter = smoothstep(0.16, 0.5, abs(uv.x - 0.5));
  col *= mix(0.6, 1.15, gutter);

  float vig = smoothstep(1.3, 0.32, length((uv - 0.5) * vec2(0.72, 1.18)));
  col *= mix(0.5, 1.0, vig);

  col = mix(bg, col, 0.97);
  frag = vec4(col, 1.0);
}`;

export function setupForge() {
  const canvas = document.querySelector(".forge");
  if (!canvas) return;

  let gl = null;
  try {
    gl = canvas.getContext("webgl2", {
      alpha: false, antialias: false, depth: false, stencil: false,
      premultipliedAlpha: false, powerPreference: "low-power",
    });
  } catch (_) { gl = null; }
  if (!gl) { canvas.remove(); return; }

  const compile = (type, src) => {
    const sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) { gl.deleteShader(sh); return null; }
    return sh;
  };
  const vs = compile(gl.VERTEX_SHADER, VERT);
  const fs = compile(gl.FRAGMENT_SHADER, FRAG);
  if (!vs || !fs) { canvas.remove(); return; }
  const prog = gl.createProgram();
  gl.attachShader(prog, vs); gl.attachShader(prog, fs); gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) { canvas.remove(); return; }
  gl.useProgram(prog);

  const uRes = gl.getUniformLocation(prog, "uRes");
  const uTime = gl.getUniformLocation(prog, "uTime");
  const uScroll = gl.getUniformLocation(prog, "uScroll");
  const uVel = gl.getUniformLocation(prog, "uVel");

  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  const SCALE = 0.55;

  // Don't run a continuous full-screen shader on a software renderer (no real
  // GPU) — it would peg the CPU. Freeze to a single static frame instead.
  let lowPower = false;
  const dbg = gl.getExtension("WEBGL_debug_renderer_info");
  if (dbg) {
    const renderer = String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) || "");
    lowPower = /swiftshader|llvmpipe|software|basic render|microsoft basic/i.test(renderer);
  }

  function resize() {
    const w = Math.max(1, Math.round(window.innerWidth * SCALE));
    const h = Math.max(1, Math.round(window.innerHeight * SCALE));
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.uniform2f(uRes, canvas.width, canvas.height);
  }

  function progress() {
    const sh = document.documentElement.scrollHeight - window.innerHeight;
    return sh > 0 ? Math.min(1, Math.max(0, window.scrollY / sh)) : 0;
  }

  let raf = 0;
  let running = false;
  let lastY = window.scrollY;
  let vel = 0;

  function draw(timeMs) {
    const y = window.scrollY;
    const target = Math.min(1, Math.abs(y - lastY) / (window.innerHeight * 0.6));
    lastY = y;
    vel += (target - vel) * 0.08;
    gl.uniform1f(uTime, timeMs * 0.001);
    gl.uniform1f(uScroll, progress());
    gl.uniform1f(uVel, vel);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  function loop(t) { draw(t); raf = requestAnimationFrame(loop); }
  function start() { if (running || reduce.matches || lowPower) return; running = true; raf = requestAnimationFrame(loop); }
  function stop() { running = false; if (raf) cancelAnimationFrame(raf); raf = 0; }

  resize();
  canvas.classList.add("is-ready");
  if (reduce.matches || lowPower) draw(6000); else start();

  canvas.addEventListener("webglcontextlost", (e) => { e.preventDefault(); stop(); }, false);
  window.addEventListener("resize", () => { resize(); if (!running) draw(performance.now()); });
  document.addEventListener("visibilitychange", () => { if (document.hidden) stop(); else start(); });
  reduce.addEventListener("change", () => { if (reduce.matches) { stop(); draw(6000); } else start(); });
}
