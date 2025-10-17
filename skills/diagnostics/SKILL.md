# Dev Diagnostics Skill

<!-- L1: Metadata (always loaded, ~100 tokens) -->
**Name:** Dev Environment Diagnostics
**Description:** Environment validation, health checks, cache management, error diagnosis for Clojure/ClojureScript development.
**Triggers:** health check, validate environment, diagnose error, preflight, cache
**Network Required:** No (except for API key validation)
**Resources:** health.clj, error-catalog.edn, preflight scripts

---

<!-- L2: Instructions (loaded when skill triggered, <5k tokens) -->

## Overview

This skill consolidates development environment diagnostics - health checks, cache management, error diagnosis, and pre-flight validation for smooth ClojureScript development.

## When to Use

Use this skill when:
- Starting a new development session
- Troubleshooting build issues
- Environment problems (missing dependencies, stale cache)
- Diagnosing common errors
- Pre-commit validation
- CI/CD health checks

## Available Commands

### `health` - Quick Health Check

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

**Output:**
```
=== Environment Health Check ===
✓ Java 21.0.1
✓ Clojure 1.11.1
✓ Node.js v20.10.0
✓ npm 10.2.3
✓ Shadow-CLJS 2.26.0
✓ Git repository (clean)
✓ API keys present (GEMINI, OPENAI, GROK)

All checks passed!
```

### `preflight` - Pre-Flight Checks

```bash
./run.sh preflight
```

More thorough checks before starting work:
- Environment health (all above)
- Cache status
- Dependency freshness
- REPL connectivity (if server running)
- Workspace cleanliness

**Use before:**
- Starting new feature
- After pulling changes
- When switching branches

### `cache` - Cache Management

```bash
# Show cache status
./run.sh cache status

# Clear all caches
./run.sh cache clear

# Clear specific cache
./run.sh cache clear shadow
./run.sh cache clear clj-kondo
./run.sh cache clear npm
```

Manages:
- Shadow-CLJS cache (`.shadow-cljs/`)
- Clojure cache (`.cpcache/`, `.clj-kondo/.cache/`)
- npm cache
- Skills cache (`.cache/`)

### `diagnose` - Error Diagnosis

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
./run.sh diagnose "Cannot resolve symbol"

Found match: :unresolved-symbol
Likely cause: Missing require or alias
Fix: Add (require '[missing.namespace :as alias])
Auto-fix: Check imports in namespace
```

### `api-keys` - API Key Validation

```bash
# Check which keys are set
./run.sh api-keys check

# Validate keys work
./run.sh api-keys validate

# Show required keys
./run.sh api-keys required
```

Checks:
- `.env` file exists
- Required keys present (GEMINI, OPENAI, GROK)
- Optional keys (ANTHROPIC, GROQ, etc.)
- Can optionally test keys (make API calls)

### `deps` - Dependency Checks

```bash
# Check for outdated deps
./run.sh deps outdated

# Verify all deps downloadable
./run.sh deps verify

# Show dependency tree
./run.sh deps tree
```

## NPM Commands Integration

The skill wraps existing npm commands:

- `npm run agent:health` → `./run.sh health`
- `npm run agent:preflight` → `./run.sh preflight`
- `npm run fix:cache` → `./run.sh cache clear`
- `npm run repl:health` → Part of `./run.sh preflight`

## Error Catalog

Maintains `dev/error-catalog.edn` with common errors:

```clojure
{:unresolved-symbol
 {:pattern #"Cannot resolve symbol"
  :category :compilation
  :likely-causes ["Missing require" "Typo in name" "Wrong alias"]
  :fixes ["Add (require '[ns :as alias])"
          "Check spelling"
          "Verify namespace exists"]
  :auto-fix "Check imports"}

 :stale-cache
 {:pattern #"Unexpected error|Strange behavior"
  :category :cache
  :likely-causes ["Stale cache" "Old compilation artifacts"]
  :fixes ["Clear shadow-cljs cache: rm -rf .shadow-cljs/"
          "Clear clj-kondo cache: rm -rf .clj-kondo/.cache/"]
  :auto-fix "./run.sh cache clear"}}
```

## Pre-Flight Checklist

**Before starting work:**

```bash
./run.sh preflight
```

Validates:
1. Environment tools installed
2. Dependencies up-to-date
3. Caches clean (no stale data)
4. API keys present
5. REPL server reachable
6. Git workspace clean

## Cache Management Patterns

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

### Safe Clear Order

```bash
# 1. Try selective clear first
./run.sh cache clear shadow

# 2. If still broken, clear all
./run.sh cache clear

# 3. Rebuild
npm run dev  # or shadow-cljs watch app
```

## Common Diagnostics Scenarios

### Scenario 1: "Build is broken"

```bash
# 1. Check environment
./run.sh health

# 2. Clear caches
./run.sh cache clear

# 3. Check dependencies
./run.sh deps verify

# 4. Rebuild
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

# 3. Check .env sourced
source .env && env | grep API_KEY
```

### Scenario 4: "Strange runtime behavior"

```bash
# Diagnose automatically
./run.sh diagnose "describe the behavior"

# Often: stale cache
./run.sh cache clear
```

## Integration with CI/CD

### Pre-commit Hook

```bash
#!/bin/bash
# .git/hooks/pre-commit

# Quick health check
./skills/diagnostics/run.sh health || exit 1

# Linting (handled by .pre-commit-check.sh)
npm run lint || exit 1
```

### CI/CD Pipeline

```yaml
# .github/workflows/test.yml
- name: Environment Check
  run: ./skills/diagnostics/run.sh health

- name: Cache Management
  run: ./skills/diagnostics/run.sh cache status
```

## Configuration

Edit `config.edn` to customize:

```clojure
{:health-checks
 {:required ["java" "clojure" "node" "npm" "shadow-cljs"]
  :optional ["git" "rlwrap"]
  :api-keys ["GEMINI_API_KEY" "OPENAI_API_KEY"]}

 :cache-paths
 {:shadow-cljs ".shadow-cljs/"
  :clj-kondo ".clj-kondo/.cache/"
  :clojure ".cpcache/"
  :npm "node_modules/.cache/"
  :skills ".cache/"}

 :preflight
 {:check-deps true
  :check-repl true
  :check-git true
  :warn-dirty-git true}

 :error-catalog
 {:path "../../dev/error-catalog.edn"
  :auto-update true}}
```

## Error Catalog Format

Errors in `dev/error-catalog.edn`:

```clojure
{:error-id
 {:pattern #"regex pattern"
  :category :compilation | :runtime | :cache | :env
  :severity :error | :warning | :info
  :likely-causes ["cause 1" "cause 2"]
  :fixes ["fix 1" "fix 2"]
  :auto-fix "command to run"
  :docs-ref "URL or local path"}}
```

## Quick Reference

```bash
# Health check
./run.sh health

# Pre-flight
./run.sh preflight

# Clear cache
./run.sh cache clear

# Diagnose error
./run.sh diagnose "error message"

# Check API keys
./run.sh api-keys check

# Check dependencies
./run.sh deps outdated
```

## Tips & Best Practices

1. **Run health check at session start**
   - Catch problems early
   - Verify environment ready

2. **Clear cache when in doubt**
   - Shadow-CLJS cache is safe to delete
   - Rebuild is usually fast

3. **Keep error catalog updated**
   - Add new patterns as encountered
   - Document fixes for team

4. **Use preflight before commits**
   - Catch issues before pushing
   - Verify tests pass

5. **Monitor cache sizes**
   - Large caches may indicate issues
   - Regular clearing prevents bloat

## Common Pitfalls

- **Forgetting to source .env**: API keys won't be available
- **Partial cache clear**: Sometimes need to clear all
- **Skipping preflight**: Issues found late in development
- **Stale node_modules**: Occasional `rm -rf node_modules && npm install` needed

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
- Add to error catalog
- Use interactive mode

## See Also

- Project docs: `../../CLAUDE.md#dev-tooling`
- Health checks: `../../dev/health.clj`
- Error catalog: `../../dev/error-catalog.edn`
- Scripts: `../../dev/bin/`
- NPM commands: `../../package.json`
