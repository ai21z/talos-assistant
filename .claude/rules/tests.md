---
paths:
  - "src/test/**/*.java"
  - "src/e2eTest/**/*.java"
  - "src/integrationTest/**/*.java"
---

# Test conventions (Talos)

Full doctrine: `AGENTS.md` sections Work-Test Cycle (Inner Dev Loop), Ticket And Regression Discipline.

- Use TDD for behavior changes: write a focused failing test, observe the failure, implement the smallest fix, rerun focused tests.
- Stay in the inner loop for active coding: focused unit tests, targeted deterministic E2E only when relevant. Do not bump the version per edit.
- Every confirmed runtime-owned or policy-owned failure becomes a deterministic regression test where practical. Convert live failure evidence into a deterministic test before closeout when practical.
- Do not weaken the security, privacy, trace, or approval integration suites to make a change pass. If a guard test fails, fix the code or the guard deliberately, with evidence, not by deleting the assertion.
- Run focused tests on this host with `--no-daemon`, for example:
  - `.\gradlew.bat test --tests "dev.talos.<...>Test" --no-daemon`
  - `.\gradlew.bat e2eTest --tests "dev.talos.harness.<...>Test" --no-daemon`
- A passing focused test is a readiness signal. Release-grade evidence comes only from the candidate loop on a clean committed tree.
