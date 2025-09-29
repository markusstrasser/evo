### Dev Tooling
- **REPL**: `dev/repl.clj` - shadow-cljs bridge (`connect!`, `init!`, `cljs!`, `clj!`)
- **Health**: `dev/health.clj` - diagnostics (`preflight-check!`, `cache-stats`, `check-repl-state`)
- **Fixtures**: `dev/fixtures.cljc` - test data builders (`make-db`, `gen-linear-tree`)
- **Config**: `dev/config.edn` - central config (paths, ports, timeouts)
- **Errors**: `dev/error-catalog.edn` - error taxonomy with remedies
- **Preflight**: `dev/preflight.edn` + `dev/scripts/preflight.sh` - startup checks
- **Quick start**: `(require '[repl :as repl]) (repl/init!)`
- **Docs**: `dev/README.md`

### NPM Commands
- `npm run check` - lint + compile
- `npm run fix:cache` - clear all caches
- `npm run agent:health` - environment diagnostics
- `npm run agent:preflight` - pre-flight checks (needs dev server)

### Investigation Tactics
- `bat`, `rg`, targeted file reads for token efficiency
- Check error-catalog.edn for self-diagnosis patterns
- Use semantic search: `@docs/SemanticSearch.md`

### Standing Instructions
- Maintain kernel transaction architecture, invariants, instrumentation focus
- Prefer synchronous/pure patterns unless async explicitly justified
- Skip tests for docs-only changes; note "Tests: not run"
