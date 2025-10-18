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
bb lint check test fix-cache

# Skills
bb health preflight env-debug research

# Development
bb dev repl-health docs-overview install-hooks help
```

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
(repl/rt!)                      ; Run all tests
(repl/rq! 'core.interpret-test) ; Reload and run specific test
```

**Location:** `dev/repl/init.clj`

### Browser DEBUG Helpers

```javascript
DEBUG.summary()       // State overview
DEBUG.events()        // All events
DEBUG.inspectEvents() // Recent with ✅/❌ status
DEBUG.reload()        // Hard reload
```

### Key Files

- `dev/repl/init.clj` - REPL helpers (go!, connect!, init!, rt!, rq!)
- `dev/debug.cljs` - Browser/REPL debugging helpers
- `dev/health.clj` - Environment diagnostics
- `dev/error-catalog.edn` - Error taxonomy with auto-fixes

---

## Core Architecture

**Database & Operations:**
- `src/core/db.cljc` - Canonical DB shape, validation, derived indexes
- `src/core/ops.cljc` - Three core operations (create, place, update)
- `src/core/interpret.cljc` - Transaction pipeline (normalize → validate → apply)
- `src/core/schema.cljc` - Malli schemas

**Testing:**
- `test/core_interpret_test.cljc` - Comprehensive operation tests
- `dev/fixtures.cljc` - Test data generators
- Run: `bb test` or `(repl/rt!)`

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
- Check `dev/error-catalog.edn` for self-diagnosis patterns
