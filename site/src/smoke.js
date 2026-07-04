// The bronze dust. The old molten-bronze "forge" smoke, reused here as warm
// dust that hangs in the hero (top + right) and recedes as you scroll down.
// It is transparent over the CSS floor, so a frozen/faded frame can never
// repaint later sections with an opaque canvas background.

const VERT = `#version 300 es
void main(){ vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }`;

const FRAG = `#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uScroll;
out vec4 frag;
float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p);
  float a = hash(i), b = hash(i + vec2(1.0, 0.0)), c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p){ float v = 0.0, a = 0.5; for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.0; a *= 0.5; } return v; }
void main(){
  vec2 uv = gl_FragCoord.xy / uRes.xy;
  vec2 p = vec2(uv.x * uRes.x / uRes.y, uv.y) * 1.5;
  p.y += uScroll * 1.4;                   // drift as the page descends
  float t = uTime * 0.024;
  vec2 q = vec2(fbm(p + t), fbm(p + vec2(5.2, 1.3) - t));
  float n = fbm(p + 1.6 * q);

  vec3 dust   = vec3(0.80, 0.56, 0.31);     // the old bronze
  vec3 dustLo = vec3(0.34, 0.23, 0.12);

  // dust lives in the hero (top + right) and fades out by ~60% scroll
  float presence = (1.0 - smoothstep(0.0, 0.6, uScroll))
                 * smoothstep(0.0, 0.85, uv.y)
                 * mix(0.45, 1.0, smoothstep(0.25, 1.0, uv.x));
  float field = smoothstep(0.42, 0.9, n);
  vec3 col = mix(dustLo, dust, smoothstep(0.42, 0.86, n));
  float vig = smoothstep(1.3, 0.32, length((uv - 0.5) * vec2(0.8, 1.18)));
  float alpha = field * presence * mix(0.1, 0.2, vig);
  frag = vec4(col, alpha);
}`;

export function setupSmoke() {
  const canvas = document.querySelector(".smoke");
  if (!canvas) return;
  let gl = null;
  try {
    gl = canvas.getContext("webgl2", {
      alpha: true, antialias: false, depth: false, stencil: false,
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

  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  const SCALE = 0.5;
  let lowPower = false;
  const dbg = gl.getExtension("WEBGL_debug_renderer_info");
  if (dbg) {
    const r = String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) || "");
    lowPower = /swiftshader|llvmpipe|software|basic render|microsoft basic/i.test(r);
  }

  let lost = false;
  function resize() {
    if (lost) return;
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
  function draw(ms) {
    if (lost) return;
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.uniform1f(uTime, ms * 0.001);
    gl.uniform1f(uScroll, progress());
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  let raf = 0;
  let running = false;
  function loop(t) { draw(t); raf = requestAnimationFrame(loop); }
  function start() { if (running || reduce.matches || lowPower) return; running = true; raf = requestAnimationFrame(loop); }
  function stop() { running = false; if (raf) cancelAnimationFrame(raf); raf = 0; }

  resize();
  if (reduce.matches || lowPower) draw(6000);
  else start();

  canvas.addEventListener("webglcontextlost", (e) => { lost = true; e.preventDefault(); stop(); }, false);
  window.addEventListener("resize", () => { resize(); if (!running) draw(performance.now()); });
  // When frozen on a software renderer, still track scroll so the dust recedes.
  if (lowPower && !reduce.matches) {
    window.addEventListener("scroll", () => { if (!running) draw(performance.now()); }, { passive: true });
  }
  document.addEventListener("visibilitychange", () => { if (document.hidden) stop(); else start(); });
  reduce.addEventListener("change", () => { if (reduce.matches) { stop(); draw(6000); } else start(); });
}
