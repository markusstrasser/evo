---
name: Dev Environment Diagnostics
description: Environment validation, health checks, cache management, error diagnosis for Clojure/ClojureScript development. Triggers on health, preflight, cache, diagnose, validate environment. Includes API key checks, dependency verification, cache clearing. No network required except for API validation.
---

# Dev Environment Diagnostics

## Prerequisites

**Required Tools:**
- `java` - JVM for Clojure
- `clojure` - Clojure CLI
- `node` - Node.js runtime
- `npm` - Package manager
- `shadow-cljs` - ClojureScript compiler

**Optional Tools:**
- `git` - Version control
- `rlwrap` - REPL line editing

**API Keys (optional):**
```bash
# In .env file
GEMINI_API_KEY=your-key
OPENAI_API_KEY=your-key
XAI_API_KEY=your-key
```

## Quick Start

```bash
# Quick health check
./run.sh health

# Pre-flight before starting work
./run.sh preflight

# Clear all caches
./run.sh cache clear

# Diagnose specific error
./run.sh diagnose "Cannot resolve symbol"

# Check API keys
./run.sh api-keys check
```

## When to Use

Use this skill when:
- Starting a new development session
- Troubleshooting build issues
- Environment problems (missing dependencies, stale cache)
- Diagnosing common errors
- Pre-commit validation
- CI/CD health checks

## Available Commands

### health - Quick Health Check

```bash
./run.sh health
```

Checks:
- ✅ Java version
- ✅ Clojure CLI
- ✅ Node.js & npm
- ✅ Shadow-CLJS
- ✅ Git status
- ✅ API keys (if .env exists)

### preflight - Pre-Flight Checks

```bash
./run.sh preflight
```

More thorough checks before starting work:
- Environment health (all above)
- Cache status
- Dependency freshness
- REPL connectivity (if server running)
- Workspace cleanliness

Use before:
- Starting new feature
- After pulling changes
- When switching branches

### cache - Cache Management

```bash
# Show cache status
./run.sh cache status

# Clear all caches
./run.sh cache clear

# Clear specific cache
./run.sh cache clear shadow      # .shadow-cljs/
./run.sh cache clear clj-kondo   # .clj-kondo/.cache/
./run.sh cache clear clojure     # .cpcache/
./run.sh cache clear npm         # node_modules/.cache/
```

**Cache locations:**
| Cache | Path | When to Clear |
|-------|------|---------------|
| Shadow-CLJS | `.shadow-cljs/` | Weird compilation errors, stale code |
| Clj-kondo | `.clj-kondo/.cache/` | Linter not finding symbols, false warnings |
| Clojure | `.cpcache/` | Dependency resolution issues |
| npm | `node_modules/.cache/` | After package.json changes |
| Skills | `.cache/` | Research or visual cache issues |

### diagnose - Error Diagnosis

```bash
# Diagnose specific error
./run.sh diagnose "error message"

# Interactive diagnosis
./run.sh diagnose --interactive
```

Uses `dev/error-catalog.edn` to:
- Match error patterns
- Suggest fixes
- Provide auto-fix commands

**Example:**
```bash
$ ./run.sh diagnose "Cannot resolve symbol"

Found match: :unresolved-symbol
Category: compilation
Likely causes:
  - Missing require
  - Typo in name
  - Wrong alias
Fixes:
  - Add (require '[namespace :as alias])
  - Check spelling
  - Verify namespace exists
```

### api-keys - API Key Validation

```bash
# Check which keys are set
./run.sh api-keys check

# Validate keys work (makes test API calls)
./run.sh api-keys validate

# Show required keys
./run.sh api-keys required
```

**Required keys:** GEMINI_API_KEY, OPENAI_API_KEY, XAI_API_KEY
**Optional keys:** ANTHROPIC_API_KEY, GROQ_API_KEY

## NPM Commands Integration

The skill wraps existing npm commands:

| NPM Command | Skill Command |
|-------------|---------------|
| `npm run agent:health` | `./run.sh health` |
| `npm run agent:preflight` | `./run.sh preflight` |
| `npm run fix:cache` | `./run.sh cache clear` |
| `npm run repl:health` | Part of `./run.sh preflight` |

## Error Catalog

Maintains `dev/error-catalog.edn` with common errors and fixes:

### Common Errors

**Unresolved Symbol**
- Pattern: `Cannot resolve symbol`
- Category: compilation
- Fix: Add `(require '[namespace :as alias])`

**Stale Cache**
- Pattern: `Unexpected error` / Strange behavior
- Category: cache
- Fix: `./run.sh cache clear`

**Port In Use**
- Pattern: `port.*in use` / `address.*in use`
- Category: runtime
- Fix: `pkill -f shadow-cljs`

**API Key Missing**
- Pattern: `api.*key` / `authentication`
- Category: configuration
- Fix: Check `.env` file and `source .env`

## Common Diagnostics Scenarios

### Scenario 1: "Build is broken"

```bash
# 1. Check environment
./run.sh health

# 2. Clear caches
./run.sh cache clear

# 3. Rebuild
npm run dev
```

### Scenario 2: "Linter is confused"

```bash
# 1. Clear linter cache
./run.sh cache clear clj-kondo

# 2. Re-run linter
npm run lint
```

### Scenario 3: "API calls failing"

```bash
# 1. Check keys present
./run.sh api-keys check

# 2. Validate keys work
./run.sh api-keys validate

# 3. Source .env if needed
source .env
```

### Scenario 4: "Strange runtime behavior"

```bash
# Diagnose automatically
./run.sh diagnose "describe the behavior"

# Often: stale cache
./run.sh cache clear
```

## Cache Management Patterns

### Safe Clear Order

```bash
# 1. Try selective clear first
./run.sh cache clear shadow

# 2. If still broken, clear all
./run.sh cache clear

# 3. Rebuild
npm run dev
```

### When to Clear Cache

**Clear Shadow-CLJS cache:**
- Weird compilation errors
- Stale code loading
- After dependency changes

**Clear Clj-kondo cache:**
- Linter not finding new symbols
- False positive warnings

**Clear NPM cache:**
- Dependency resolution issues
- After package.json changes

**Clear all caches:**
- "Turn it off and on again" moment
- Before important demo
- After major refactor

## Integration with CI/CD

### Pre-commit Hook

```bash
#!/bin/bash
# .git/hooks/pre-commit

./skills/diagnostics/run.sh health || exit 1
npm run lint || exit 1
```

### CI/CD Pipeline

```yaml
# .github/workflows/test.yml
- name: Environment Check
  run: ./skills/diagnostics/run.sh health

- name: Cache Status
  run: ./skills/diagnostics/run.sh cache status
```

## Configuration Options

**Health check thresholds:**
- Required tools must be present
- Optional tools warn if missing
- API keys checked if .env exists

**Cache management:**
- Auto-clear threshold: 500 MB
- Warn size: 200 MB
- Clear on error: disabled by default

**Error diagnosis:**
- Uses `dev/error-catalog.edn`
- Verbose output: disabled
- Suggest fixes: enabled

## Tips & Best Practices

1. **Run health check at session start** - Catch problems early
2. **Clear cache when in doubt** - Shadow-CLJS cache is safe to delete
3. **Keep error catalog updated** - Add new patterns as encountered
4. **Use preflight before commits** - Catch issues before pushing
5. **Monitor cache sizes** - Large caches may indicate issues

## Common Pitfalls

- **Forgetting to source .env** - API keys won't be available
- **Partial cache clear** - Sometimes need to clear all
- **Skipping preflight** - Issues found late in development
- **Stale node_modules** - Occasional `rm -rf node_modules && npm install` needed

## Troubleshooting

**"Health check fails"**
- Install missing tools
- Check PATH includes tool directories
- Verify versions compatible

**"Cache clear doesn't help"**
- Try clearing all caches
- Check for permission issues
- Verify disk space available

**"API keys not found"**
- Verify .env exists in project root
- Check keys not commented out
- Source .env: `source .env`

**"Diagnose finds no match"**
- Error might be new
- Add to error catalog manually
- Use interactive mode

## Resources (Level 3)

- `run.sh` - Main CLI wrapper
- `dev/health.clj` - Clojure health check functions
- `dev/error-catalog.edn` - Error pattern database
- `dev/bin/health-check.sh` - Standalone health check
- `dev/bin/preflight.sh` - Preflight validation script

## See Also

- Project docs: `../../CLAUDE.md#dev-tooling`
- NPM commands: `../../package.json`
- Pre-commit hook: `../../.pre-commit-check.sh`
