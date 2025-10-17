# Dev Diagnostics Skill

Environment validation, health checks, cache management for Clojure/ClojureScript development.

## Quick Start

```bash
# Health check
./run.sh health

# Pre-flight before work
./run.sh preflight

# Clear cache
./run.sh cache clear

# Diagnose error
./run.sh diagnose "error message"

# Check API keys
./run.sh api-keys check
```

## Commands

- **health** - Quick environment check (Java, Clojure, Node, Git, API keys)
- **preflight** - Thorough pre-flight validation
- **cache** - Manage caches (status | clear [type])
- **diagnose** - Error diagnosis with suggestions
- **api-keys** - API key validation (check | required | validate)
- **deps** - Dependency checks (outdated | verify | tree)

## Example Output

```
=== Environment Health Check ===

✓ Java 21.0.1
✓ Clojure CLI 1.11.1
✓ Node.js v20.10.0
✓ npm 10.2.3
✓ Shadow-CLJS 2.26.0
✓ Git repository (clean)
✓ API keys present (all 3)

All required checks passed!
```

## When to Use

- Start of development session
- Troubleshooting build issues
- Before committing
- After dependency changes
- When things feel "weird"

## See Also

- Full docs: `SKILL.md`
- Health checks: `../../dev/health.clj`
- Error catalog: `../../dev/error-catalog.edn`
