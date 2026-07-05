# Installed Product Smoke

Installed-product smoke verifies the installed command, not the development classpath.

Minimum smoke:

```bash
talos --version
talos status --verbose
talos doctor
talos doctor --start
```

REPL smoke:

```text
/status --verbose
/mode
/prompt
/last trace
```

For release candidates, record:

- operating system and shell
- exact `talos --version`
- install source
- model backend and profile
- whether a managed server was started
- whether prompt-debug and trace commands worked
- any native-access or terminal warning output

