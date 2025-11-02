# Dev Tooling Analysis

**Date:** 2025-11-02
**Status:** ✅ Clean - All tooling functional and well-documented

---

## Summary

Excellent dev tooling infrastructure aligned with project philosophy (REPL-first, debuggability, simplicity, quick iteration).

**One fix:** dev/debug.cljs referenced stale `lab.anki.*` namespaces → updated to current project (commit 4e800af)

---

## REPL Helpers (dev/repl/init.cljc)

**Quick Start:**
```clojure
(require '[repl :as repl])
(repl/go!)  ; connect → init → health check
```

**Key Functions:**
- `(rt!)` - Run all tests
- `(rq! 'namespace-test)` - Reload & run specific test
- `(sample-db! :fixture)` - Load test fixture in browser
- `(inspect-db! [:nodes])` - Inspect DB state
- `(send-intent! {...})` - Test intent dispatch
- `(test-component! ...)` - Test component rendering

**Auto-installed:** Clojure+ (#p debug macro, better printing, improved errors)

---

## Browser DEBUG Helpers (dev/debug.cljs)

**Console Usage:**
```javascript
DEBUG.summary()         // State overview
DEBUG.tree()            // Print outline structure
DEBUG.checkIntegrity()  // Validate DB
DEBUG.dispatch({...})   // Test intent dispatch
```

**Output Example:**
```clojure
{:nodes/total 10
 :selection/ids ["a" "b"]
 :editing/block-id "c"
 :history/undo-count 5}
```

---

## Babashka Tasks (bb.edn)

```bash
bb lint check test      # Quality gates
bb clean index          # Cache & semantic search
bb dev repl-health      # Development
bb install-hooks help   # Setup
```

---

## Pre-commit Hook (.pre-commit-check.sh)

**Validates:**
1. Lint, compile, tests
2. CLJS imports (gstr/format → requires goog.string.format)
3. Namespace/path consistency (dev/repl/*.cljc uses `repl.*`)
4. Shadow-CLJS server status

---

## Skills Infrastructure

**diagnostics** - Health checks, cache management, error catalog
**architect** - Tournament-based proposal generation & ranking (via llmx)
**code-research** - Semantic search via ck
**session-memory** - Search past conversations
**computer-use** - MCP-based helpers

All have comprehensive SKILL.md documentation with progressive disclosure (L1: metadata, L2: instructions, L3: resources).

---

## Conclusion

No further cleanup needed. All tooling functional, well-documented, and aligned with project philosophy.
