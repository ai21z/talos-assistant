import { expect, test } from "@playwright/test";

const widths = [320, 375, 390, 768, 1024, 1440];

test.beforeEach(async ({ page }) => {
  const browserIssues = [];
  page.on("console", (message) => {
    if (["error", "warning"].includes(message.type())) {
      browserIssues.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on("pageerror", (error) => browserIssues.push(`pageerror: ${error.message}`));
  page.browserIssues = browserIssues;
});

test("page renders without browser console errors and has one product h1", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/Talos/);
  await expect(page.locator("h1")).toHaveCount(1);
  await expect(page.locator("h1")).toContainText(/local-first/i);
  await expect(page.locator("h1")).toContainText(/workspace/i);
  expect(page.browserIssues).toEqual([]);
});

test("nav anchors exist and scroll to real sections", async ({ page }) => {
  await page.goto("/");
  const navLinks = page.locator(".site-nav a");
  const count = await navLinks.count();
  expect(count).toBeGreaterThan(4);

  for (let index = 0; index < count; index += 1) {
    const link = navLinks.nth(index);
    const href = await link.getAttribute("href");
    expect(href).toMatch(/^#/);
    const target = page.locator(href);
    await expect(target).toHaveCount(1);
    await link.click();
    await expect(page).toHaveURL(new RegExp(`${href}$`));
    await expect(target).toBeInViewport();
  }
});

for (const width of widths) {
  test(`has no horizontal overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });
}

test("terminal tabs switch content on click and keyboard", async ({ page }) => {
  await page.goto("/");
  const output = page.locator("#terminal-output");

  await page.getByRole("tab", { name: "Approve" }).click();
  await expect(output).toContainText("approval required");

  await page.getByRole("tab", { name: "Approve" }).press("ArrowRight");
  await expect(page.getByRole("tab", { name: "Check" })).toHaveAttribute("aria-selected", "true");
  await expect(output).toContainText("talos.run_command");

  await page.getByRole("tab", { name: "Check" }).press("ArrowLeft");
  await expect(page.getByRole("tab", { name: "Approve" })).toHaveAttribute("aria-selected", "true");

  await page.getByRole("tab", { name: "Approve" }).press("End");
  await expect(page.getByRole("tab", { name: "Trace" })).toHaveAttribute("aria-selected", "true");
  await expect(output).toContainText("/last trace");

  await page.getByRole("tab", { name: "Trace" }).press("Home");
  await expect(page.getByRole("tab", { name: "Inspect" })).toHaveAttribute("aria-selected", "true");
});

test("copy buttons are visible, focusable, and uniquely labelled", async ({ page }) => {
  await page.goto("/");
  const buttons = page.locator(".copy-button");
  const count = await buttons.count();
  expect(count).toBeGreaterThanOrEqual(6);

  const labels = [];
  for (let index = 0; index < count; index += 1) {
    const button = buttons.nth(index);
    await expect(button).toBeVisible();
    await button.focus();
    await expect(button).toBeFocused();
    labels.push(await button.getAttribute("aria-label"));
  }

  expect(new Set(labels).size).toBe(labels.length);
  expect(labels.every((label) => /^Copy .+ command$/.test(label ?? ""))).toBe(true);
});

test("placeholder beta CTA reports placeholder status without fake artifact navigation", async ({ page }) => {
  await page.goto("/");
  const originalUrl = page.url();
  await page.getByRole("button", { name: "Get beta build" }).first().click();
  await expect(page.locator(".toast")).toContainText(
    "Beta download placeholder. Build artifacts will be added later.",
  );
  expect(page.url()).toBe(originalUrl);
});

test("mobile header and nav remain usable", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 780 });
  await page.goto("/");
  const primaryNav = page.getByRole("navigation", { name: "Primary navigation" });
  await expect(primaryNav).toBeVisible();
  await expect(primaryNav.getByRole("link", { name: "Product" })).toBeVisible();
  await expect(primaryNav.getByRole("link", { name: "Beta" })).toBeVisible();
  await primaryNav.getByRole("link", { name: "Beta" }).click();
  await expect(page.locator("#beta")).toBeInViewport();
});

test("reduced-motion mode leaves content visible without reveal animations", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  const hiddenRevealCount = await page.locator(".reveal").evaluateAll((nodes) =>
    nodes.filter((node) => {
      const style = window.getComputedStyle(node);
      return style.opacity === "0" || style.visibility === "hidden";
    }).length,
  );
  expect(hiddenRevealCount).toBe(0);
  await expect(page.locator("h1")).toBeVisible();
});
