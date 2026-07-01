import { defineConfig } from "vite";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  server: {
    fs: {
      allow: [resolve(here, ".."), here],
    },
  },
  build: {
    sourcemap: false,
    rollupOptions: {
      input: {
        main: resolve(here, "index.html"),
        docs: resolve(here, "docs.html"),
      },
    },
  },
});
