import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { setupSmoke } from "../src/smoke.js";

// These tests execute the production lifecycle with bounded DOM/GL/RAF fakes.
// They do not exercise a browser, GPU, shader compiler, or rendered pixels.
function events(properties = {}) {
  const listeners = new Map();
  return Object.assign(properties, {
    addEventListener(type, listener) {
      const registered = listeners.get(type) ?? [];
      registered.push(listener);
      listeners.set(type, registered);
    },
    emit(type, details = {}) {
      const event = { type, defaultPrevented: false, ...details };
      event.preventDefault = () => { event.defaultPrevented = true; };
      for (const listener of listeners.get(type) ?? []) listener(event);
      return event;
    },
  });
}

function withSmoke(options, check) {
  const calls = [];
  const pending = new Map();
  const cancelled = [];
  const backbufferWrites = [];
  let nextFrame = 0;
  const gl = {
    VERTEX_SHADER: 1, FRAGMENT_SHADER: 2, COMPILE_STATUS: 3,
    LINK_STATUS: 4, COLOR_BUFFER_BIT: 5, TRIANGLES: 6,
    createShader(type) { return { type }; },
    getShaderParameter() { return true; },
    createProgram() { return {}; },
    getProgramParameter() { return true; },
    getUniformLocation(_program, name) { return name; },
    getExtension(name) {
      return name === "WEBGL_debug_renderer_info" ? { UNMASKED_RENDERER_WEBGL: 7 } : null;
    },
    getParameter() { return options.lowPower ? "Google SwiftShader" : "Hardware renderer"; },
  };
  for (const name of ["shaderSource", "compileShader", "deleteShader", "attachShader", "linkProgram",
    "useProgram", "viewport", "uniform2f", "clearColor", "clear", "uniform1f", "drawArrays"]) {
    gl[name] = (...args) => { calls.push({ name, args }); };
  }
  const canvas = events({
    isConnected: true,
    removals: 0,
    contextRequests: [],
    getContext(type, attributes) {
      this.contextRequests.push({ type, attributes });
      if (options.contextThrows) throw new Error("Synthetic unavailable context");
      return options.noContext ? null : gl;
    },
    remove() { this.isConnected = false; this.removals += 1; },
  });
  for (const [name, initial] of [["width", 300], ["height", 150]]) {
    let value = initial;
    Object.defineProperty(canvas, name, {
      get() { return value; },
      set(next) { value = next; backbufferWrites.push({ name, value: next }); },
    });
  }
  const reduced = events({ matches: options.reduced ?? false });
  const window = events({
    innerWidth: 1280, innerHeight: 720, scrollY: 0,
    matchMedia(query) {
      assert.equal(query, "(prefers-reduced-motion: reduce)");
      return reduced;
    },
  });
  const document = events({
    hidden: false,
    documentElement: { scrollHeight: 1440 },
    querySelector(selector) {
      assert.equal(selector, ".smoke");
      return options.noCanvas || !canvas.isConnected ? null : canvas;
    },
  });
  const overrides = {
    window, document,
    requestAnimationFrame(callback) {
      const id = ++nextFrame;
      pending.set(id, callback);
      return id;
    },
    cancelAnimationFrame(id) { cancelled.push(id); pending.delete(id); },
  };
  const original = new Map(Object.keys(overrides).map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  try {
    for (const [name, value] of Object.entries(overrides)) {
      Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });
    }
    setupSmoke();
    check({
      canvas, gl, window, document, reduced, calls, pending, cancelled, backbufferWrites,
      count: name => calls.filter(call => call.name === name).length,
      lose: () => canvas.emit("webglcontextlost"),
      frame(time = 16) {
        assert.equal(pending.size, 1, "one scheduled smoke frame is expected");
        const [id, callback] = pending.entries().next().value;
        pending.delete(id);
        callback(time);
      },
    });
  } finally {
    for (const [name, descriptor] of original) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
}

describe("smoke lifecycle with simulated DOM and GL", { concurrency: false }, () => {
  it("retains normal animation and pre-loss resize behavior", () => {
    withSmoke({}, fixture => {
      assert.equal(fixture.canvas.contextRequests.length, 1);
      assert.equal(fixture.canvas.contextRequests[0].type, "webgl2");
      assert.equal(fixture.canvas.contextRequests[0].attributes.alpha, true);
      assert.equal(fixture.canvas.isConnected, true);
      assert.deepEqual([fixture.canvas.width, fixture.canvas.height], [640, 360]);
      assert.equal(fixture.pending.size, 1);
      assert.equal(fixture.count("drawArrays"), 0);
      fixture.frame();
      assert.equal(fixture.count("drawArrays"), 1);
      assert.equal(fixture.pending.size, 1);
      fixture.window.innerWidth = 1000;
      fixture.window.innerHeight = 600;
      fixture.window.emit("resize");
      assert.deepEqual([fixture.canvas.width, fixture.canvas.height], [500, 300]);
      assert.equal(fixture.count("viewport"), 2);
      assert.equal(fixture.count("drawArrays"), 1);
    });
  });

  it("retains reduced-motion static frames and pre-loss preference changes", () => {
    withSmoke({ reduced: true }, fixture => {
      assert.equal(fixture.pending.size, 0);
      assert.equal(fixture.count("drawArrays"), 1);
      fixture.window.emit("resize");
      assert.equal(fixture.count("drawArrays"), 2);
      fixture.reduced.matches = false;
      fixture.reduced.emit("change");
      assert.equal(fixture.pending.size, 1);
      fixture.reduced.matches = true;
      fixture.reduced.emit("change");
      assert.equal(fixture.pending.size, 0);
      assert.equal(fixture.count("drawArrays"), 3);
    });
  });

  it("retains low-power static drawing, scrolling and resize without animation", () => {
    withSmoke({ lowPower: true }, fixture => {
      assert.equal(fixture.pending.size, 0);
      assert.equal(fixture.count("drawArrays"), 1);
      fixture.window.emit("resize");
      fixture.window.scrollY = 100;
      fixture.window.emit("scroll");
      fixture.document.emit("visibilitychange");
      assert.equal(fixture.count("drawArrays"), 3);
      assert.equal(fixture.pending.size, 0);
    });
  });

  it("pauses a healthy hidden tab and resumes one animation when visible", () => {
    withSmoke({}, fixture => {
      const frameId = fixture.pending.keys().next().value;
      fixture.document.hidden = true;
      fixture.document.emit("visibilitychange");
      assert.equal(fixture.pending.size, 0);
      assert.deepEqual(fixture.cancelled, [frameId]);
      assert.equal(fixture.count("drawArrays"), 0);
      fixture.document.hidden = false;
      fixture.document.emit("visibilitychange");
      fixture.document.emit("visibilitychange");
      assert.equal(fixture.pending.size, 1, "repeated visible events must not duplicate the loop");
      fixture.frame();
      assert.equal(fixture.count("drawArrays"), 1);
      assert.equal(fixture.pending.size, 1);
      assert.equal(fixture.canvas.isConnected, true);
    });
  });

  it("keeps a lost low-power context inert across scroll, resize and lifecycle events", () => {
    withSmoke({ lowPower: true }, fixture => {
      fixture.lose();
      const operations = fixture.calls.length;
      const writes = fixture.backbufferWrites.length;
      fixture.window.scrollY = 100;
      fixture.window.emit("scroll");
      fixture.window.innerWidth = 1920;
      fixture.window.innerHeight = 1080;
      fixture.window.emit("resize");
      fixture.reduced.matches = true;
      fixture.reduced.emit("change");
      fixture.reduced.matches = false;
      fixture.reduced.emit("change");
      fixture.document.hidden = true;
      fixture.document.emit("visibilitychange");
      fixture.document.hidden = false;
      fixture.document.emit("visibilitychange");
      assert.equal(fixture.calls.length, operations);
      assert.equal(fixture.backbufferWrites.length, writes);
      assert.equal(fixture.pending.size, 0);
      assert.equal(fixture.canvas.isConnected, false);
      assert.equal(fixture.canvas.removals, 1);
    });
  });

  it("removes the terminally lost decoration and cancels its pending animation", () => {
    withSmoke({}, fixture => {
      const frameId = fixture.pending.keys().next().value;
      const event = fixture.lose();
      assert.equal(fixture.pending.size, 0);
      assert.deepEqual(fixture.cancelled, [frameId]);
      assert.equal(fixture.canvas.isConnected, false);
      assert.equal(fixture.canvas.removals, 1);
      assert.equal(event.defaultPrevented, false, "terminal fallback does not request context restoration");
    });
  });

  it("does not restart after loss when the tab becomes visible", () => {
    withSmoke({}, fixture => {
      fixture.lose();
      const operations = fixture.calls.length;
      fixture.document.hidden = true;
      fixture.document.emit("visibilitychange");
      fixture.document.hidden = false;
      fixture.document.emit("visibilitychange");
      assert.equal(fixture.pending.size, 0);
      assert.equal(fixture.calls.length, operations);
    });
  });

  it("does not restart or draw after loss when reduced motion changes", () => {
    withSmoke({ reduced: true }, fixture => {
      fixture.lose();
      const operations = fixture.calls.length;
      fixture.reduced.matches = false;
      fixture.reduced.emit("change");
      assert.equal(fixture.pending.size, 0);
      fixture.reduced.matches = true;
      fixture.reduced.emit("change");
      assert.equal(fixture.pending.size, 0);
      assert.equal(fixture.calls.length, operations);
    });
  });

  it("makes a retained pre-loss callback inert without drawing or rescheduling", () => {
    withSmoke({}, fixture => {
      const callback = fixture.pending.values().next().value;
      fixture.lose();
      const operations = fixture.calls.length;
      callback(16);
      assert.equal(fixture.calls.length, operations);
      assert.equal(fixture.pending.size, 0);
    });
  });

  it("does not change the backbuffer or call GL on post-loss resize", () => {
    withSmoke({ reduced: true }, fixture => {
      fixture.lose();
      const operations = fixture.calls.length;
      const writes = fixture.backbufferWrites.length;
      const dimensions = [fixture.canvas.width, fixture.canvas.height];
      fixture.window.innerWidth = 1920;
      fixture.window.innerHeight = 1080;
      fixture.window.emit("resize");
      assert.deepEqual([fixture.canvas.width, fixture.canvas.height], dimensions);
      assert.equal(fixture.backbufferWrites.length, writes);
      assert.equal(fixture.calls.length, operations);
      assert.equal(fixture.pending.size, 0);
    });
  });

  it("does nothing when the decorative canvas is missing", () => {
    withSmoke({ noCanvas: true }, fixture => {
      assert.equal(fixture.canvas.contextRequests.length, 0);
      assert.equal(fixture.canvas.removals, 0);
      assert.equal(fixture.calls.length, 0);
      assert.equal(fixture.pending.size, 0);
    });
  });

  for (const options of [{ noContext: true }, { contextThrows: true }]) {
    it(`removes the decoration when context creation ${options.noContext ? "returns null" : "throws"}`, () => {
      withSmoke(options, fixture => {
        assert.equal(fixture.canvas.contextRequests.length, 1);
        assert.equal(fixture.canvas.removals, 1);
        assert.equal(fixture.canvas.isConnected, false);
        assert.equal(fixture.calls.length, 0);
        assert.equal(fixture.pending.size, 0);
      });
    });
  }
});
