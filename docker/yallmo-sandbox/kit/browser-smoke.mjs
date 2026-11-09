// No network, provider credentials, host browser profiles, or persistent sessions.
import assert from "node:assert/strict";
import { chromium } from "playwright";

assert.notEqual(process.getuid?.(), 0, "Run the browser smoke test as the non-root agent user");
const browser = await chromium.launch({ headless: true, channel: "chromium" });
try {
  const page = await browser.newPage();
  await page.setContent("<!doctype html><title>Yallmo browser smoke</title><h1>Playwright ready</h1>");
  assert.equal(await page.title(), "Yallmo browser smoke");
  assert.equal(await page.locator("h1").innerText(), "Playwright ready");
  const screenshot = await page.screenshot();
  assert.equal(screenshot.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
  console.log(`PASS: non-root Chromium/Chrome for Testing renders DOM and screenshots (${browser.version()})`);
} finally {
  await browser.close();
}
