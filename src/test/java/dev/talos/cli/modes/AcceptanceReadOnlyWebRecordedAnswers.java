package dev.talos.cli.modes;

/** Exact projected 2026-09-08 acceptance witnesses; inert finalizer data, not live-model tests. */
final class AcceptanceReadOnlyWebRecordedAnswers {
    private AcceptanceReadOnlyWebRecordedAnswers() {}

    static final String A1 = String.join("\n",
            "## Diagnosis",
            "",
            "**Why the Calculate button fails:**",
            "The `index.html` defines the button with `id=\"calculate\"`:",
            "```html",
            "",
            "```",
            "",
            "But `script.js` listens for clicks on `#calc`:",
            "```js",
            "document.querySelector(\"#calc\").addEventListener(\"click\", ...)",
            "```",
            "",
            "This is a **mismatched selector** — the button never fires because the event listener is attached to a non-existent element.",
            "",
            "**Correct selector:** `#calculate`",
            "",
            "---",
            "",
            "## JavaScript Files Distinction",
            "",
            "- **`script.js`** — Active BMI calculator logic (but broken due to wrong selector).",
            "- **`scripts.js`** — A sentinel file containing only a comment and a constant (`\"plural-script-unchanged\"`), unrelated to the calculator.",
            "",
            "---",
            "",
            "## BMI Calculation (72 kg, 1.8 m)",
            "",
            "BMI = weight (kg) / (height (m))²  ",
            "= 72 / (1.8 × 1.8)  ",
            "= 72 / 3.24  ",
            "= **22.2**",
            "",
            "---",
            "",
            "To fix the bug, change the selector in `script.js` from `#calc` to `#calculate`.");
    static final String A1_SHA256 = "d4ddf736891f58b8f30375ff52b298d537e7ff6a0261e6f2b1cc6dc3fd353544";

    static final String A2 = String.join("\n",
            "The reviewer’s claim is **correct**: `script.js` does **not** reference `#calc`. It uses:",
            "",
            "```js",
            "document.querySelector(\"#calc\")",
            "```",
            "",
            "But in `index.html`, the button’s ID is:",
            "",
            "```html",
            "",
            "```",
            "",
            "So the selector `#calc` does **not** match any element, and the click listener is never attached to the actual button. That’s why the Calculate button fails.",
            "",
            "**Correct selector:** `#calculate");
    static final String A2_SHA256 = "0b32fa337dfd966e5ef4f0031712f71d106295cc391246fb5385747aaeed9deb";

    // Exact later A1 provider answer. Its HTML line-5 citation is wrong; this
    // witness tests bounded selector/source-role preservation, not prose truth.
    static final String A1_LISTENER = String.join("\n",
            "### Why the Calculate button fails",
            "The HTML defines the button with `id=\"calculate\"` (line 5 of index.html).  ",
            "`script.js` listens for a click on `#calc` (line 2 of script.js).  ",
            "Because the event listener is attached to a non-existent element, the button never fires.",
            "",
            "**Correct selector:** `#calculate`",
            "",
            "### BMI calculation (72 kg, 1.8 m)",
            "BMI = weight (kg) / (height (m))²  ",
            "= 72 / (1.8 × 1.8)  ",
            "= 72 / 3.24  ",
            "= **22.2** (rounded to one decimal)",
            "",
            "### Distinction between the two JavaScript files",
            "- **`script.js`**: Intended BMI calculator logic, but has a wrong event selector (`#calc` instead of `#calculate`).",
            "- **`scripts.js`**: An unrelated sentinel file containing a placeholder string; not part of the calculator and must not be changed.");
    static final String A1_LISTENER_SHA256 = "5b1f50bbfb4e3620c74ba01b6dcc3b04d868cd893d154dd1509d1be6f3c13805";
}
