### REPL Integration
- **Dev utilities**: `src/dev.clj` - unified ClojureScript REPL bridge
- **Auto-connection**: `(init!)` connects to shadow-cljs nREPL
- **Smart evaluation**: `(cljs! "code")` for browser, `(clj! "code")` for Clojure
- **Environment health**: `(preflight-check!)` validates full stack

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
