# Dev Tooling & Skills Analysis

**Date:** 2025-11-02  
**Context:** Post-refactoring analysis of development infrastructure, tools, and skills

---

## Executive Summary

The project has **excellent dev tooling** infrastructure with comprehensive REPL helpers, well-organized Babashka tasks, and thoroughly documented skills. One stale file (debug.cljs) was found and fixed.

**Status:** ✅ Clean - All tooling functional and well-documented

---

## Dev Tooling (dev/ directory)

### ✅ dev/repl/init.cljc - REPL Helpers (Excellent)

**Quick Start:**
```clojure
(require '[repl :as repl])
(repl/go!)  ; One-command startup: connect → init → health check
```

**Core Functions:**
- `connect!` - Connect to shadow-cljs REPL for :frontend build
- `init!` - Load kernel namespaces + install clojure-plus enhancements
- `go!` - Full startup (connect + init + health check)

**Test Runners:**
- `rt!` - Run all tests or specific namespaces
- `rq! 'namespace-test` - Reload & run specific test

**Component Testing (Browser REPL):**
- `sample-db!` - Get/reset/fixture DB: `(sample-db! :fixture)`
- `inspect-db!` - Inspect DB state: `(inspect-db! [:nodes])`
- `send-intent!` - Test intent dispatch: `(send-intent! {:type :select :ids "a"})`
- `test-component!` - Test component rendering with props

**Clojure-Plus Enhancements (auto-installed):**
- `#p` debug macro - Inline debugging (works in -> and ->> pipelines)
- Better printing - atoms, refs, files show clearly
- Improved errors - Clojure-aware stack traces, reversed (most relevant first), colored
- Better test output - Structured context, clearer expected/actual comparison

**Journaling:**
- Enabled by default in dev (operations logged to `.architect/ops.ednlog`)

**Why Excellent:**
- Minimal friction (go! = instant productivity)
- Component testing helpers (sample-db!, inspect-db!, send-intent!)
- Clojure-plus integration improves debugging experience significantly
- Well-commented with rich docstrings and usage examples

### ✅ dev/debug.cljs - Browser Debug Helpers (Fixed)

**Status:** Fixed in commit 4e800af (was referencing stale lab.anki.* namespaces)

**Usage (Browser Console):**
```javascript
DEBUG.summary()         // State overview
DEBUG.tree()            // Print outline structure
DEBUG.checkIntegrity()  // Validate DB
DEBUG.nodes             // All nodes
DEBUG.selection()       // Current selection
DEBUG.history()         // History state
DEBUG.dispatch({...})   // Test intent dispatch
DEBUG.reload()          // Hard reload
```

**State Inspection:**
- `nodes()`, `node(id)`, `children(id)`, `parent(id)`
- `selection()`, `editing()`, `folded()`

**History Inspection:**
- `history()`, `undo-count()`, `redo-count()`
- `can-undo?()`, `can-redo?()`

**Summary Output:**
```clojure
{:nodes/total 10
 :nodes/blocks 8
 :nodes/pages 2
 :selection/count 2
 :selection/ids ["a" "b"]
 :editing/block-id "c"
 :folded/count 1
 :history/undo-count 5
 :history/redo-count 2}
```

**Why Excellent:**
- Exported to window.DEBUG for easy console access
- Tree visualization for outline structure
- Integrity checking via db/validate
- Intent testing with dispatch!

### ✅ dev/verify.clj - Integration Tests (Good)

**Purpose:** Example integration tests for end-to-end workflows

**Example Pattern:**
```clojure
;; UI ephemeral: enter/exit edit does not affect undo
(def db1 (:db (api/dispatch db {:type :enter-edit :block-id "a"})))
(assert= (q/editing-block-id db1) "a" "enter-edit failed")
(assert= (H/undo-count db1) 0 "UI-only op should not record history")

;; Structural change records history
(def db2 (:db (api/dispatch db1 {:type :indent :id "b"})))
(assert= (H/undo-count db2) 1 "structural op must record history")
```

**Coverage:**
- Ephemeral vs persistent operations
- History tracking behavior
- Selection persistence across undo

**Why Good:**
- Clear examples for learning patterns
- Tests invariants (UI state persists across undo)
- Simple assert= helper for readable checks

---

## Build Tools (bb.edn)

### ✅ Babashka Tasks - Well-Organized

**Quality Gates:**
```bash
bb lint                # clj-kondo linter
bb check               # lint + compile check (full quality gate)
bb test                # compile test build + run node tests
bb check-deps-sync     # verify deps.edn ↔ shadow-cljs.edn versions match
```

**Cache & Index:**
```bash
bb clean               # clear all caches (.shadow-cljs, .cpcache, .ck index)
bb index               # rebuild ck semantic search embeddings
```

**Development:**
```bash
bb dev                 # shadow-cljs watch :frontend
bb repl-health         # quick REPL diagnostics
bb docs-overview       # generate AI architectural overview
```

**Setup:**
```bash
bb install-hooks       # install git pre-commit hooks
bb help                # show available tasks
```

**Why Excellent:**
- Covers full development lifecycle
- Clear categorization (quality gates, cache, dev, setup)
- Integrated with semantic search (ck)
- Help command for discoverability

### ✅ Pre-commit Hook (.pre-commit-check.sh) - Thorough

**Checks:**
1. Linting (clj-kondo)
2. Compilation (shadow-cljs compile test)
3. Tests (npm test)
4. CLJS import validation (e.g., gstr/format requires goog.string.format)
5. Namespace/path consistency
   - dev/repl/*.cljc should use `repl.*` (not `dev.repl.*`) since 'dev' is on classpath
   - mcp/servers/*.cljc should use `servers.*` (not `mcp.servers.*`)
6. Shadow-CLJS server check (warns if not running)

**Why Thorough:**
- Catches common ClojureScript gotchas (missing goog requires)
- Validates namespace/path alignment automatically
- Fast feedback loop (fails early)

---

## Skills (skills/ directory)

### ✅ skills/diagnostics/ - Environment Diagnostics

**Purpose:** Health checks, cache management, error diagnosis

**Quick Start:**
```bash
./run.sh health           # quick health check
./run.sh preflight        # thorough pre-flight checks
./run.sh cache clear      # clear all caches
./run.sh diagnose "error" # diagnose specific error
./run.sh api-keys check   # check API keys
```

**Health Checks:**
- Java version
- Clojure CLI
- Node.js & npm
- Shadow-CLJS
- Git status
- API keys (if .env exists)

**Cache Management:**
| Cache | Path | When to Clear |
|-------|------|---------------|
| Shadow-CLJS | `.shadow-cljs/` | Weird compilation errors, stale code |
| Clj-kondo | `.clj-kondo/.cache/` | Linter not finding symbols, false warnings |
| Clojure | `.cpcache/` | Dependency resolution issues |
| npm | `node_modules/.cache/` | After package.json changes |
| Skills | `.cache/` | Research or visual cache issues |

**Error Diagnosis:**
- Uses `skills/diagnostics/data/error-catalog.edn`
- Pattern matching for common errors
- Auto-fix suggestions

**Why Excellent:**
- Comprehensive environment validation
- Cache management with clear guidance
- Error catalog for self-diagnosis
- Well-documented SKILL.md

### ✅ skills/architect/ - Architectural Decision Workflow

**Purpose:** Tournament-based architectural proposal generation and ranking

**Workflow:**
```bash
# Full cycle (generate → rank → optionally decide)
cat VISION.md AUTO-SOURCE-OVERVIEW.md src/core/*.cljc | \
  skills/architect/run.sh propose "Review this architecture"

skills/architect/run.sh rank <run-id>
skills/architect/run.sh decide <run-id> approve <proposal-id> "rationale"
```

**LLM Providers (Parallel Generation):**
- gemini (via `gemini` CLI) - gemini-2.5-pro
- codex (via `codex` CLI) - gpt-5-codex with high reasoning effort
- grok (via `llmx` CLI) - grok-4-latest

**Critical Policy:**
- Never use: gpt-4o, gpt-4-turbo, gemini-flash
- Always use: gpt-5-codex, gemini-2.5-pro, grok-4-latest
- Reasoning effort: HIGH (non-negotiable for architecture)

**Evaluation Criteria (Priority Order):**
1. Simplicity (HIGHEST) - Solo dev can understand/debug easily
2. Debuggability - Observable state, clear errors, REPL-friendly
3. Flexibility - Can skip stages, run tools independently
4. Provenance - Trace proposal → spec → implementation
5. Quality gates - Catch bad specs before implementation

**Tournament Integration:**
- Uses tournament MCP for ranking when available
- Falls back to heuristic comparison if MCP unavailable
- Two use cases:
  1. **Validation:** Same prompt → multiple providers → check consensus (INVALID = identical = good!)
  2. **Comparison:** Different architectures → rank by quality

**Output:**
- `.architect/review-runs/{run-id}/` - Proposals, rankings, specs, ADRs
- `.architect/review-ledger.jsonl` - Append-only provenance log

**Why Excellent:**
- Well-documented context requirements (CRITICAL: provide full context!)
- Parallel LLM generation for diversity
- Tournament-based ranking (Bradley-Terry model)
- Clear evaluation criteria aligned with project philosophy
- ADR generation for decisions

### ✅ skills/code-research/, session-memory/, computer-use/

**Status:** All have comprehensive SKILL.md documentation

**Pattern:** Progressive disclosure (L1: metadata, L2: instructions, L3: resources)

**Integration:**
- code-research: Uses `ck` CLI for semantic search
- session-memory: Searches past conversations via `ck`
- computer-use: MCP-based helpers for screenshots, mouse, keyboard

---

## Scripts (scripts/ directory)

### ✅ Supporting Scripts

**Found:**
- `install-hooks.sh` - Install pre-commit hooks
- `generate-overview.sh` - Generate AI architectural overview
- `check-version-sync.sh` - Verify deps.edn ↔ shadow-cljs.edn versions
- `load-env.sh` - Load environment variables from .env

**Status:** All functional, no issues found

---

## Recommendations

### ✅ Completed

1. **Fixed dev/debug.cljs** - Updated to reference current project namespaces (commit 4e800af)

### No Action Needed

Everything else is well-designed and functional:
- REPL helpers provide excellent development experience
- Babashka tasks cover all workflows
- Pre-commit hooks catch common mistakes
- Skills are thoroughly documented
- No other stale code found

---

## Conclusion

The dev tooling infrastructure is **excellent** and aligns perfectly with the project philosophy:
- **REPL-first:** Comprehensive helpers, clojure-plus integration, component testing
- **Debuggability:** Browser DEBUG helpers, tree visualization, integrity checking
- **Simplicity:** bb tasks, clear documentation, progressive disclosure in skills
- **Quick iteration:** Hot reload, cache management, health checks

**Next Session:** All tooling is clean and functional. Focus can shift to feature development or architecture exploration.
