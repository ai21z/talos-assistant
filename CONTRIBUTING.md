# Contributing to Talos

**Version:** `v0.9.0-beta`  
**Last verified commit:** `ec2f6e9`

Thank you for your interest in contributing to Talos! This guide outlines the development workflow, coding standards, and contribution process for the project.

---

## Branch Policy

**Development for release-level code should be on the `v0.9.0-beta-dev` branch until our team releases it.**

### Branch Structure

- **`v0.9.0-beta-dev`** - Active development branch for v0.9.0-beta release
- **`main`** - Stable release branch (protected)
- **Feature branches** - Short-lived branches off `v0.9.0-beta-dev`

### Workflow

```powershell
# 1. Start from development branch
git checkout v0.9.0-beta-dev
```

```powershell
git pull origin v0.9.0-beta-dev
```

```powershell
# 2. Create feature branch
git checkout -b feature/your-feature-name
```

```powershell
# 3. Work on your changes
# ... make commits ...
```

```powershell
# 4. Push and create MR to v0.9.0-beta-dev
git push origin feature/your-feature-name
```

```
# Create MR via GitLab UI targeting v0.9.0-beta-dev
```

---

## Getting Started

### Prerequisites

- **Java 21+** with Vector API support
- **Git** for version control
- **Ollama** running locally for testing
- **PowerShell** (recommended for Windows development)

### Development Setup

```powershell
# Clone the repository
git clone <repository-url>
```

```powershell
cd talos
```

```powershell
# Switch to development branch
git checkout v0.9.0-beta-dev
```

```powershell
# Build and test
.\gradlew clean build
```

```powershell
# Install locally for testing
.\gradlew installDist
```

```powershell
pwsh tools\install-windows.ps1
```

### Verify Setup

```powershell
# Run unit tests
.\gradlew test
```

```powershell
# Run smoke tests
talos --version
```

```powershell
talos status
```

```powershell
# Quick integration test
cd C:\some\test\project
```

```powershell
talos rag-index --stats
```

```powershell
talos rag-ask "What files are in this project?"
```

---

## Development Workflow

### 1. Code Changes

**Key areas to understand:**
- **CLI commands**: `src/main/java/dev/talos/cli/cmds/`
- **REPL modes**: `src/main/java/dev/talos/cli/modes/`
- **RAG pipeline**: `src/main/java/dev/talos/core/rag/`
- **Configuration**: `src/main/resources/config/default-config.yaml`

**Coding standards:**
- Follow existing Java code style
- Use meaningful variable names
- Add Javadoc for public APIs
- Prefer composition over inheritance
- Keep methods focused and testable

### 2. Testing Requirements

**Unit tests** (required for all new code):
```powershell
# Run specific test class
.\gradlew test --tests "dev.talos.core.rag.RagFlowSmokeTest"
```

```powershell
# Run all tests with coverage
.\gradlew test jacocoTestReport
```

**Integration tests** (for CLI and RAG changes):
```powershell
# Test CLI commands
talos setup --help
```

```powershell
talos rag-index --stats
```

```powershell
talos rag-ask "test question"
```

```powershell
# Test REPL commands
talos
```

```
/help
/status
/mode rag
/k 5
/q
```

### 3. Documentation Updates

**Update documentation** for user-facing changes:
- **README.md** - CLI usage, configuration, troubleshooting
- **docs/TECHNICAL_ANALYSIS_v0.9.0-beta.md** - Architecture changes
- **Javadoc** - Public API documentation
- **Configuration** - Update default-config.yaml comments

### 4. Security Review

**Security checklist** (critical for acceptance):
- [ ] No external network calls without `net.enabled` check
- [ ] All user input sanitized (SQL, file paths, shell commands)
- [ ] No secrets in logs or error messages
- [ ] File system access respects workspace boundaries
- [ ] Ollama connections validate localhost-only (unless `allow_remote`)

### 5. Performance Considerations

**Performance guidelines:**
- Use streaming for interactive responses
- Implement proper connection pooling for HTTP clients
- Cache embeddings to avoid redundant computation
- Respect configured timeout and rate limits
- Profile memory usage for large workspaces

---

## Merge Request Process

### Before Submitting

**Pre-submission checklist:**
- [ ] Code builds successfully (`.\gradlew clean build`)
- [ ] All tests pass (`.\gradlew test`)
- [ ] No new security vulnerabilities introduced
- [ ] Documentation updated for user-facing changes
- [ ] PowerShell examples use one command per line (no `&&` chaining)
- [ ] Configuration changes include proper defaults and validation

### MR Requirements

**Title format:** Use Conventional Commits style
```
feat: add support for PDF parsing in rag indexing
fix: resolve Ollama timeout handling in batch embeddings
docs: update installation guide for Java 21 requirement
refactor: simplify mode controller routing logic
```

**Description template:**
```markdown
## Summary
Brief description of what this MR does.

## Changes Made
- Specific change 1
- Specific change 2  
- Configuration/API changes (if any)

## Testing Done
- Unit tests: [pass/fail]
- Integration tests: [describe testing done]
- Manual testing: [describe manual verification]

## Security Impact
- No external network calls added: [yes/no]
- Input validation added for new inputs: [yes/no/n/a]
- Backward compatibility maintained: [yes/no/n/a]

## Documentation Updated
- [ ] README.md (if user-facing)
- [ ] Technical analysis (if architectural)
- [ ] Javadoc (if public API)
```

### Review Criteria

**Automatic checks:**
- GitLab CI pipeline passes
- No merge conflicts with target branch
- Branch up-to-date with `v0.9.0-beta-dev`

**Manual review focus:**
- Code quality and maintainability
- Security posture (local-only, no telemetry)
- Performance impact on large workspaces
- Backward compatibility with existing configurations
- Test coverage for new functionality

---

## Commit Guidelines

### Commit Message Format

Follow **Conventional Commits** specification:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, missing semicolons, etc.)
- `refactor`: Code refactoring (no functionality change)
- `test`: Adding or updating tests
- `chore`: Maintenance tasks (build, CI, dependencies)
- `perf`: Performance improvements
- `security`: Security fixes or improvements

**Examples:**
```
feat(cli): add --bm25-only flag to disable vector search

fix(rag): handle empty search results gracefully in RagService

docs: update README with multi-workspace usage examples

refactor(embed): extract batch processing to separate class

test(index): add comprehensive file filtering tests

security(ollama): validate localhost-only connections by default
```

### Commit Best Practices

- **Keep commits focused** on single logical changes
- **Write clear commit messages** explaining the "why", not just "what"
- **Reference issues** when applicable: `fixes #123`
- **Avoid breaking changes** in patch releases
- **Test each commit** - should build and pass basic tests

---

## Code Style Guide

### Java Conventions

```java
// Class names: PascalCase
public class RagService {
    
    // Constants: SCREAMING_SNAKE_CASE  
    private static final int DEFAULT_TOP_K = 6;
    
    // Methods: camelCase
    public RagAnswer askQuestion(String query, int topK) {
        // Local variables: camelCase
        List<SearchResult> results = searchService.search(query, topK);
        
        // Use meaningful names
        String assembledPrompt = promptBuilder.build(query, results);
        return llmClient.generate(assembledPrompt);
    }
}
```

**Import organization:**
1. Java standard library (`java.*`, `javax.*`)
2. Third-party libraries (alphabetical)
3. Project imports (`dev.talos.*`)

### Configuration Style

```yaml
# Use lowercase with underscores for keys
rag:
  top_k: 6                    # Numbers without quotes
  include_patterns:           # Arrays with dashes
    - "**/*.md"
    - "**/*.java"
  force_reindex: false        # Booleans without quotes
  
# Group related settings
limits:
  max_file_size: 20000
  timeout_ms: 30000
```

### PowerShell Examples

**Always use one command per line** (never chain with `&&`):

```powershell
# Good
.\gradlew clean build
```

```powershell
pwsh tools\install-windows.ps1
```

```powershell
talos --version
```

```powershell
# Bad - don't chain commands
.\gradlew clean build && pwsh tools\install-windows.ps1 && talos --version
```

---

## Issue Labels & Triage

### Label Categories

**Type:**
- `enhancement` - New feature requests
- `bug` - Confirmed bugs
- `documentation` - Documentation improvements  
- `question` - Support questions
- `security` - Security-related issues

**Priority:**
- `critical` - Security issues, data loss, crashes
- `high` - Major functionality broken
- `medium` - Important but not blocking
- `low` - Nice to have improvements

**Component:**
- `cli` - Command-line interface
- `rag` - RAG pipeline and search
- `config` - Configuration system
- `docs` - Documentation
- `build` - Build system and CI

### Issue Templates

**Bug Report:**
```markdown
## Description
Brief description of the issue.

## Steps to Reproduce
1. Run command: `talos rag-index`
2. Observe error: [error message]

## Expected Behavior
What should happen instead.

## Environment
- OS: Windows 10/11
- Java version: `java -version`
- Ollama version: `ollama --version`
- Talos version: `talos --version`

## Additional Context
Logs, screenshots, or other relevant information.
```

**Feature Request:**
```markdown
## Feature Description
Clear description of the proposed feature.

## Use Case
Why is this feature needed? What problem does it solve?

## Proposed Implementation
High-level approach (if you have ideas).

## Alternative Solutions
Other ways this could be addressed.
```

---

## Release Process

### Release Preparation

**Pre-release checklist** (maintainers only):
- [ ] All tests pass on `v0.9.0-beta-dev`
- [ ] Documentation updated and reviewed
- [ ] Security audit completed
- [ ] Performance benchmarks run
- [ ] Breaking changes documented
- [ ] Migration guide prepared (if needed)

**Version bumping:**
```powershell
# Update version in build.gradle.kts
# Update README.md version references
# Update technical analysis version
# Tag release commit
git tag -a v0.9.0-beta -m "Talos v0.9.0-beta release"
```

---

## Code of Conduct

### Our Standards

**Positive behavior:**
- Using welcoming and inclusive language
- Being respectful of differing viewpoints
- Gracefully accepting constructive criticism
- Focusing on what is best for the community
- Showing empathy towards other community members

**Unacceptable behavior:**
- Trolling, insulting/derogatory comments, personal attacks
- Public or private harassment
- Publishing others' private information without permission
- Other conduct which could reasonably be considered inappropriate

### Enforcement

Project maintainers are responsible for clarifying standards and taking corrective action in response to unacceptable behavior.

**Contact:** Report issues to project maintainers via GitLab private messages.

---

## Getting Help

### Resources

- **Technical questions:** Create issue with `question` label
- **Feature requests:** Create issue with `enhancement` label  
- **Bug reports:** Create issue with `bug` label
- **Security issues:** Contact maintainers privately

### Development Support

**Common development questions:**
- **"How do I add a new CLI command?"** - See `dev.talos.cli.cmds` package
- **"How do I add a new REPL mode?"** - Implement `dev.talos.cli.modes.Mode` interface
- **"How do I modify the RAG pipeline?"** - Start with `dev.talos.core.rag.RagService`
- **"How do I add configuration options?"** - Update `default-config.yaml` and related classes

**Debugging tips:**
```powershell
# Enable debug logging
talos run
```

```
/debug on
```

```powershell
# Run with JVM debug flags
$env:JAVA_OPTS="-Dloqj.debug=true"
```

```powershell
talos status --verbose
```

```powershell
# Check configuration loading
talos status --verbose
```

---

**Thank you for contributing to Talos!**

Talos thrives on community contributions. Whether you're fixing bugs, adding features, improving documentation, or helping other users, your contributions make the project better for everyone.

---

**Contributing Guide** - Version `v0.9.0-beta` • Commit `ec2f6e9`
