### REPL Integration
- **Dev utilities**: `dev/` - generic ClojureScript development tools
  - `dev/repl.clj` - shadow-cljs REPL bridge (connect!, init!, cljs!, clj!)
  - `dev/health.clj` - build health checks (preflight-check!, cache-stats)
  - `dev/fixtures.cljc` - test data builders (make-db, gen-linear-tree, etc.)
- **Quick start**: `(require '[repl :as repl]) (repl/init!)`
- **Documentation**: See `dev/README.md` for workflows

### Investigation Tactics That Worked
- `bat`, `rg`, and targeted file reads kept exploration token-efficient.
- Inspecting inspiration repos before drafting ensured each proposal cited concrete sources.
- Updating `docs/kernel_simplification_proposals.md` alongside new idea files preserves a single authoritative backlog.

### Standing Instructions Recap
- Maintain kernel transaction architecture, invariants, and instrumentation focus when proposing changes.
- Prefer synchronous/pure patterns unless async is explicitly justified.
- Skip running tests for docs-only changes; note "Tests: not run" when applicable.


--
IF you need better semantic search check @docs/SemanticSearch.md
