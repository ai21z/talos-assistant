import { defineConfig, devices } from "@playwright/test";

const previewHost = "127.0.0.1";
const previewPort = Number.parseInt(process.env.TALOS_SITE_E2E_PORT ?? "41739", 10);
if (!Number.isInteger(previewPort) || previewPort < 1 || previewPort > 65535) {
  throw new Error(`Invalid TALOS_SITE_E2E_PORT: ${process.env.TALOS_SITE_E2E_PORT}`);
}

const previewURL = `http://${previewHost}:${previewPort}`;
const baseURL = process.env.TALOS_SITE_E2E_BASE_URL ?? previewURL;
const skipWebServer = process.env.TALOS_SITE_E2E_SKIP_WEBSERVER === "1";
if (process.env.TALOS_SITE_E2E_BASE_URL && !skipWebServer) {
  throw new Error("TALOS_SITE_E2E_BASE_URL requires TALOS_SITE_E2E_SKIP_WEBSERVER=1");
}

export default defineConfig({
  testDir: "./test/e2e",
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  use: {
    baseURL,
    trace: "retain-on-failure",
  },
  webServer: skipWebServer
    ? undefined
    : {
        command: `npm run preview -- --host ${previewHost} --port ${previewPort} --strictPort`,
        url: previewURL,
        reuseExistingServer: false,
        timeout: 60_000,
      },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
