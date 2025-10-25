## The Start

**Start here**: Read both auto-generated overviews (created on src/ merge):
1. `dev/overviews/AUTO-SOURCE-OVERVIEW.md` - The code/logic (kernel implementation)
2. `dev/overviews/AUTO-PROJECT-OVERVIEW.md` - The project structure (tooling, docs, research)

**Development Philosophy:**
- Solo developer using AI agents as helpers
- Focus on 80/20, not performance or production use
- Keep it simple - prioritize debuggability over cleverness

---

## Unified Toolchain

### Babashka Tasks (bb)

```bash
# Quality gates
bb lint check test check-deps-sync fix-cache

# Skills
bb health preflight env-debug research

# Development
bb dev repl-health docs-overview install-hooks help
```

### Semantic Search & Indexing

The `ck` tool provides semantic, lexical, and hybrid code search with embeddings:

```bash
# Rebuild embeddings index (do this regularly)
bb rebuild-index

# Clean orphaned index files
bb clean-index

# Semantic search in code
ck --sem "event sourcing patterns" src/

# Search session history
bb sessions search --hybrid "kernel IR architecture" --limit 5
```

**Index management:**
- **`.ck/` directories** - Gitignored embeddings cache (auto-generated)
- **Rebuild regularly** - Run `bb rebuild-index` after major refactors or weekly
- **First search is slow** - Builds index automatically, subsequent searches are instant
- **Session search** - Uses ck to find conceptually similar conversations

### llmx CLI (Agent Scripts)

All agent scripts use `llmx` - unified CLI for 100+ LLM providers via LiteLLM.

```bash
llmx "prompt"                                                    # Default (Google)
llmx --provider openai --model gpt-5-codex --reasoning-effort high "prompt"
llmx --provider xai "prompt"                                    # Grok
llmx --compare "tabs or spaces?"                                # Compare providers
cat code.txt | llmx --provider google "analyze"                # Pipe input
```

**Human interactive use:** `gemini` (high token queries), `codex` (taste/style/architecture with `--reasoning-effort high`)

---

## Skills

Filesystem-based workflows with progressive disclosure (L1: metadata, L2: instructions, L3: resources).

| Skill | Path | Quick Start | Full Docs |
|-------|------|-------------|-----------|
| **Code Research** | `skills/research/` | `bb research list` | [SKILL.md](skills/research/SKILL.md) |
| **Diagnostics** | `skills/diagnostics/` | `bb health` | [SKILL.md](skills/diagnostics/SKILL.md) |
| **REPL Debug** | `skills/repl-debug/` | `skills/repl-debug/run.sh guide` | [SKILL.md](skills/repl-debug/SKILL.md) |
| **Visual Validation** | `skills/visual/` | `skills/visual/run.sh analyze ref.png` | [SKILL.md](skills/visual/SKILL.md) |
| **Architect** | `skills/architect/` | `skills/architect/run.sh propose` | [SKILL.md](skills/architect/SKILL.md) |
| **Computer Use** | `skills/computer-use/` | `skills/computer-use/run.sh start` | [SKILL.md](skills/computer-use/SKILL.md) |

---

## Dev Tooling

### REPL Quick Start

```clojure
;; One-command startup (recommended):
(require '[repl :as repl])
(repl/go!)  ; Connect, load namespaces, health check

;; Manual:
(repl/connect!)  (repl/init!)  (repl/quick-health-check!)

;; Tests:
(repl/rt!)                        ; Run all tests
(repl/rq! 'core-transaction-test) ; Reload and run specific test

;; Component testing (browser):
(repl/sample-db! :fixture)                    ; Load test data in browser
(repl/inspect-db! [:nodes])                   ; Inspect DB path
(repl/send-intent! {:type :select :ids "a"})  ; Test intent dispatch
(repl/test-component! 'components.block/Block ; Test component rendering
                      {:db (repl/sample-db!)
                       :block-id "a"
                       :depth 0
                       :on-intent identity})
```

**Location:** `dev/repl/init.cljc`

### Clojure+ Enhancements

The project uses [clojure-plus](https://github.com/tonsky/clojure-plus) for improved REPL development experience. These enhancements are automatically installed when you run `(repl/go!)` or `(repl/init!)`.

**Available features:**

1. **#p Debug Macro** (clojure+.hashp)
   - Inline debugging better than println
   - Returns value, so works in -> and ->> pipelines
   - Prints location and form automatically
   ```clojure
   (let [x 5
         y #p (+ x 2)]  ; prints: #p (+ x 2) [repl:1] 7
     (* x y))
   ```

2. **Better Printing** (clojure+.print)
   - Atoms, refs, volatiles: `#atom 123` instead of `#object[...]`
   - Arrays: `#ints [1 2 3]` instead of `#object["[I" ...]`
   - Files, paths: `#file "/"` instead of `#object[java.io.File ...]`
   - See all supported types in [clojure-plus README](https://github.com/tonsky/clojure-plus#clojureprint)

3. **Improved Errors** (clojure+.error)
   - Clojure-aware stack traces: `clojure.core/eval` instead of `core$eval invokeStatic`
   - Reversed traces (most relevant first)
   - Colored output
   - Cleaner ExceptionInfo formatting

4. **Better Test Output** (clojure+.test)
   - Structured test context display
   - Clearer expected/actual comparison
   - Captured output for failed tests only
   - Time reporting per namespace

**Manual control:**
```clojure
;; Uninstall if needed
(clojure+.error/uninstall!)
(clojure+.print/uninstall!)
(clojure+.test/uninstall!)
```

### Browser DEBUG Helpers

```javascript
DEBUG.summary()       // State overview
DEBUG.events()        // All events
DEBUG.inspectEvents() // Recent with ✅/❌ status
DEBUG.reload()        // Hard reload
```

### Hot Reload

**Status:** ✅ Working by default (shadow-cljs auto-reload enabled)

- Shadow-cljs automatically reloads code changes for `:browser` targets
- File changes trigger incremental compilation (0.64s average)
- Browser automatically reloads changed namespaces
- **Note:** `:preloads [evolver.dev-preload]` in shadow-cljs.edn is archived (was for custom behavior)
- Use `DEBUG.reload()` for hard reload if needed

### Key Files

- `dev/repl/init.cljc` - REPL helpers (go!, connect!, init!, rt!, rq!, component testing)
- `dev/debug.cljs` - Browser/REPL debugging helpers
- `dev/viz.clj` - Tree visualization utility
- `dev/fixtures.cljc` - Test data generators
- `skills/diagnostics/data/error-catalog.edn` - Error taxonomy with auto-fixes

---

## Core Architecture

**Database & Operations:**
- `src/core/db.cljc` - Canonical DB shape, validation, derived indexes, tree utils
- `src/core/ops.cljc` - Three core operations (create, place, update)
- `src/core/transaction.cljc` - Transaction pipeline (normalize → validate → apply)
- `src/core/schema.cljc` - Malli schemas

**Testing:**
- `test/core_transaction_test.cljc` - Comprehensive operation tests
- `dev/fixtures.cljc` - Test data generators
- Run: `bb test` or `(repl/rt!)`

**Test Execution Strategy:**
- **ClojureScript tests**: Compiled via `shadow-cljs compile :test` → run with `node out/tests.js`
- **`bb test`**: Runs full test suite (compile + execute)
- **REPL testing**: `(repl/rt!)` for all tests, `(repl/rq! 'namespace-test)` for specific
- **157 tests** with 651 assertions, runs in ~1.8 seconds
- **Property-based tests**: Uses `test.check` for algebra layer (100-200 iterations per property)
- **Test organization**: Mirrors `/src` structure in `/test`
- **Fixtures**: Generic fixtures in `/dev/fixtures.cljc`, domain-specific in test files

---

## MCP Integration

**Active MCPs (Main Agent):**
- `tournament` - Bradley-Terry ranking via Swiss-Lite tournaments
- `clojure-shadow-cljs` - Full REPL + file editing via shadow-cljs nREPL
- `chrome-devtools` - Browser automation and debugging
- `computer-use` - Screenshots, mouse, keyboard control
- `context7` - Library documentation (inherited by researcher subagent)
- `exa` - Code examples and web search (inherited by researcher subagent)

**Researcher Subagent:**
- Inherits all MCPs from main agent (context7, exa, etc.)
- See: `.claude/agents/researcher.md` for configuration

**Skills:**
- Code search: `ck` CLI (semantic, regex, hybrid search)
- Computer control: `skills/computer-use/` (MCP-based helpers)
- Architect: `skills/architect/` (proposal generation, evaluation)

**Config:**
- `.mcp.json` - Project MCP server config
- `~/.claude.json` - Global/project-scoped MCP config
- `.claude/agents/researcher.md` - Researcher subagent tools
- Full reference: `docs/MCP.md`
- Upstream spec: `~/Projects/best/modelcontextprotocol/docs/`

---

## Quality Gates

### Pre-commit Hooks

Run `bb install-hooks` to install. Validates:
- Linting (clj-kondo)
- Tests pass
- CLJS imports valid
- Namespace/path alignment
- Module boundaries

### Linting

Config: `.clj-kondo/config.edn`

Enforces:
- Pure functions, explicit data flow
- No dynamic Vars, minimal refs/atoms
- Module isolation (kernel vs shell)

Run: `bb lint`

### Standing Instructions

- Maintain kernel transaction architecture and invariants
- Prefer synchronous/pure patterns unless async explicitly justified
- Skip tests for docs-only changes; note "Tests: not run"
- **IMPORTANT:** ALWAYS TEST and SPOT CHECK edits before end of session

---

## Available Projects

`~/Projects/best/{projectname}`:

```
adapton.rust, aero, athens, bevy, claude-code, clerk, cljfmt, clojure, clojure-mcp, clojurescript,
Common-Metadata-Repository, component-docs, compojure, conduit, core.async, core.logic, core.typed,
datalevin, datascript, editscript, electric, environ, exa-mcp-server, expresso, fastmcp, garden, honeysql,
integrant, javelin, kakoune, logseq, malli, meander, medley, Miscellaneous, missionary, morphdom, neanderthal,
neovim, onyx, opencode, overtone, pathom3, portal, prosemirror, quil, re-frame, re-frame-10x, Reactive-Programming,
reitit, replicant, rewrite-clj, ring, S, salsa, sci, slate, specter, thin_repos.py, tree-sitter, unison, vlojure, xi-editor, zed
```

Query: `bb research explore <project> "query"` (see [skills/research/SKILL.md](skills/research/SKILL.md))

Metadata: `docs/research/sources/repos.edn`

---

## Gotcha Docs

- **REPLICANT.md** (`docs/REPLICANT.md`) - Event handler syntax, reactive attributes
- **CHROME_DEVTOOLS.md** (`docs/CHROME_DEVTOOLS.md`) - Stale snapshot issues, screenshot patterns

---

## Notes

- AGENTS.md is a symlink to CLAUDE.md - edit CLAUDE.md only
- Use `bat`, `rg`, targeted file reads for token efficiency
- Check `skills/diagnostics/data/error-catalog.edn` for self-diagnosis patterns
We track work in Beads instead of Markdown. Run `bd quickstart` to see how.
