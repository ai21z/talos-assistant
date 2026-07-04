# Windows DPAPI Custody Transport

Status: accepted boundary for 0.10.8 stabilization; future hardening requires a separate design and test pass.

## Current Behavior

Windows secret-store master-key custody is implemented by `WindowsDpapiKeyCustody`.

The on-disk master-key blob is protected with DPAPI `CurrentUser`. The current bridge to DPAPI is a non-interactive PowerShell helper process. Java sends base64 input to the helper over the child process stdin pipe, the helper calls `System.Security.Cryptography.ProtectedData`, and Java reads base64 output from the child process stdout pipe.

For `protect`, the raw master key crosses the Java-to-helper stdin pipe before DPAPI protects it. For `unprotect`, the raw master key crosses the helper-to-Java stdout pipe after DPAPI unprotects it.

This stdout is a redirected child process pipe, not Talos user-console output. Talos does not write the raw key to traces, prompt-debug artifacts, provider bodies, or user-facing logs. The exposure boundary is still real: same-user process instrumentation, endpoint tooling, or host-level process capture could observe helper process pipes.

## Decision

The PowerShell bridge remains acceptable for the current beta stabilization lane because:

- it improves at-rest custody on Windows compared with raw key storage;
- custody failures fail closed;
- protected blobs are round-trip verified before replacing existing key material;
- no new native/JNA dependency is introduced during release stabilization;
- public privacy docs now bound the same-user process-monitoring exposure explicitly.

This decision does not claim hardware-backed custody, tamper resistance, or protection from same-user host instrumentation.

## Future Hardening Bar

A future lower-exposure implementation must be a separate ticket with:

- a design note comparing native Windows DPAPI access, JNA, Windows credential APIs, and any no-new-dependency alternative;
- Windows-focused regression coverage for protect, unprotect, migration, custody failure, and no-plaintext persistence;
- an explicit dependency and distribution review if a native/JNA dependency is added;
- unchanged fail-closed behavior on custody failure;
- no regression to protected-path handling, permission policy, checkpoints, trace redaction, or outcome truth.

The future implementation should reduce helper-process transport exposure if the added dependency/platform complexity is justified by evidence.
