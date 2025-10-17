## Table of Contents

**⚡ Quick Start:**
- [The Start](#the-start) - Session initialization, overviews, development philosophy
- [Unified Toolchain](#unified-toolchain) - bb tasks, llmx CLI, skill commands

**⚡ Skills (Progressive-Disclosure Workflows):**
- [Skills Overview](#skills-overview) - L1/L2/L3 loading, 97% token savings
- All skill documentation: See [Skills Index](#skills-index)

**Development:**
- [Dev Tooling](#dev-tooling) - REPL (go!), health checks, debugging
- [Quick Reference](#quick-reference) - Core files, testing, MCPs
- [Quality Gates](#quality-gates) - pre-commit, linting, standards

**Resources:**
- [Available Projects](#available-projects) - ~/Projects/best/* repos
- [Gotcha Docs](#gotcha-docs) - REPLICANT.md, CHROME_DEVTOOLS.md

---

## The Start

**Start here**: Read both auto-generated overviews (created on src/ merge):
1. `dev/overviews/AUTO-SOURCE-OVERVIEW.md` - The code/logic (kernel implementation)
2. `dev/overviews/AUTO-PROJECT-OVERVIEW.md` - The project structure (tooling, docs, research)

**Development Philosophy:**
- Solo developer using AI agents as helpers
- Focus on 80/20, not performance or production use
- Keep it simple - prioritize debuggability over cleverness

**LLM CLI Usage (for human interactive use):**
- `gemini` - High token-count queries (interactive, MCP-aware)
- `codex` - Taste, style, refactorings, architecture (use `--reasoning-effort high`)
- Agent scripts use unified `llmx` CLI (see [Unified Toolchain](#unified-toolchain))

---

## Unified Toolchain

**All agent scripts now use `llmx`** - unified CLI for LLM providers (100+ via LiteLLM).

### llmx CLI (Agent Scripts)

```bash
# Default provider (Google)
llmx "your prompt"

# Specific provider with reasoning-effort
llmx --provider openai --model gpt-5-codex --reasoning-effort high "prompt"

# Other providers
llmx --provider xai "prompt"      # Grok
llmx --provider google "prompt"   # Gemini
llmx --provider anthropic "prompt" # Claude

# Compare providers
llmx --compare "Which is better: tabs or spaces?"

# From stdin (common pattern in skills)
cat code.txt | llmx --provider google "analyze this"
```

**Reasoning-effort support:** `--reasoning-effort [low|medium|high]` for OpenAI o1/GPT-5 models.

### Babashka Tasks (bb)

Unified task runner replacing npm scripts:

```bash
# Quality gates
bb lint          # Run clj-kondo
bb check         # Lint + compile
bb test          # Run test suite
bb fix-cache     # Clear all caches

# Skills (agent workflows)
bb health        # Environment diagnostics
bb preflight     # Pre-flight checks
bb env-debug VAR # Debug .env variable
bb research ...  # Code research tool

# Development
bb dev           # Start dev server
bb repl-health   # REPL diagnostics
bb docs-overview # Generate overview

# Setup
bb install-hooks # Install git hooks
bb help          # Show all tasks
```

**Note:** npm scripts still work, but bb tasks are preferred for consistency.

**Babashka for Complex Scripts:** The `env-debug` command uses a Babashka script (`skills/diagnostics/lib/env_debug.bb`) instead of bash. This demonstrates the principle: use Babashka **only when strictly better** (data structures, parsing, testing) - bash remains fine for simple scripts.

---

## Skills Overview

**What are Skills?** Filesystem-based workflows with progressive disclosure:
- **L1 (Metadata)**: ~100 tokens, always loaded for discovery
- **L2 (Instructions)**: 3-5k tokens, loaded when triggered
- **L3+ (Resources)**: Unlimited, loaded as needed

**Token Savings:** ~14.5k tokens per session (97% reduction vs loading all docs)

**Architecture:** All skills follow official Claude Skills best practices (YAML frontmatter, markdown docs, progressive disclosure).

### Skills Index

| Skill | Path | Quick Start | Full Docs |
|-------|------|-------------|-----------|
| **Code Research** | `skills/research/` | `bb research list` | [SKILL.md](skills/research/SKILL.md) |
| **Diagnostics** | `skills/diagnostics/` | `bb health` | [SKILL.md](skills/diagnostics/SKILL.md) |
| **REPL Debug** | `skills/repl-debug/` | `skills/repl-debug/run.sh guide` | [SKILL.md](skills/repl-debug/SKILL.md) |
| **Visual Validation** | `skills/visual/` | `skills/visual/run.sh analyze ref.png` | [SKILL.md](skills/visual/SKILL.md) |
| **Architect** | `skills/architect/` | `skills/architect/run.sh propose` | [SKILL.md](skills/architect/SKILL.md) |

**For detailed usage, triggers, and examples:** See each skill's SKILL.md file.

---

## Dev Tooling

### REPL Quick Start

```clojure
;; One-command startup (recommended):
(require '[repl :as repl])
(repl/go!)  ; Connect, load namespaces, health check

;; Or manual setup:
(repl/connect!)  ; Connect to shadow-cljs
(repl/init!)     ; Load core namespaces
(repl/quick-health-check!)  ; Verify environment

;; Run tests:
(repl/rt!)  ; Run all tests
(repl/rq! 'core.interpret-test)  ; Reload and run specific test
```

**Location:** `dev/repl/init.clj` (consolidated from init.clj + session.clj)

### REPL-First Debugging

**Core philosophy:** Test hypotheses in REPL BEFORE editing code (30 seconds vs 5+ minutes)

**Full guide:** See [skills/repl-debug/SKILL.md](skills/repl-debug/SKILL.md)

**Browser DEBUG helpers (always available in dev mode):**
```javascript
DEBUG.summary()       // State overview
DEBUG.events()        // All events
DEBUG.inspectEvents() // Recent with ✅/❌ status
DEBUG.reload()        // Hard reload
```

### Dev Commands

**Key utilities:**
- `dev/repl/init.clj` - REPL helpers (go!, connect!, init!, rt!, rq!)
- `dev/debug.cljs` - Browser/REPL debugging helpers
- `dev/health.clj` - Environment diagnostics
- `dev/error-catalog.edn` - Error taxonomy with auto-fixes
- `scripts/generate-overview.sh` - Generate AI overviews
- `scripts/quick-test.sh` - Run specific test namespace

---

## Quick Reference

### Core Architecture

**Database & Operations:**
- `src/core/db.cljc` - Canonical DB shape, validation, derived indexes
- `src/core/ops.cljc` - Three core operations (create, place, update)
- `src/core/interpret.cljc` - Transaction pipeline (normalize → validate → apply)
- `src/core/schema.cljc` - Malli schemas for operations and data

**Testing:**
- `test/core_interpret_test.cljc` - Comprehensive operation tests
- `dev/fixtures.cljc` - Test data generators
- Run: `bb test` or `(repl/rt!)`

### MCP Integration

**Active MCPs:**
- `tournament-mcp` (`~/Projects/tournament-mcp/`) - Bradley-Terry ranking via Swiss-Lite tournaments
- `architect-mcp` (`~/Projects/architect-mcp/`) - Proposal → tournament → ADR workflow
- Researcher subagent (available via `/mcp`) - Deep research using Context7, Exa, best-of repos

**Configuration:**
- `.mcp.json` - Project MCP server config
- `~/.claude.json` - Global/project-scoped MCP config

**Full MCP reference:** See `docs/MCP.md` (config, failure modes, patterns)

**Upstream spec:** `~/Projects/best/modelcontextprotocol/docs/` (121 MDX files)

---

## Quality Gates

### Pre-commit Hooks

Run `scripts/install-hooks.sh` after cloning to install pre-commit hook.

**.pre-commit-check.sh** validates:
- Linting (clj-kondo)
- Tests pass
- CLJS imports valid
- Namespace/path alignment
- Module boundaries

### Linting

**Config:** `.clj-kondo/config.edn`

**Enforces:**
- Pure functions, explicit data flow
- No dynamic Vars, minimal refs/atoms
- Module isolation (kernel vs shell)

**Run:** `bb lint` or `npm run lint`

### Standing Instructions

- Maintain kernel transaction architecture and invariants
- Prefer synchronous/pure patterns unless async explicitly justified
- Skip tests for docs-only changes; note "Tests: not run"
- **IMPORTANT:** ALWAYS TEST and SPOT CHECK edits before end of session

---

## Available Projects

The path is `~/Projects/best/{projectname}`. Here are the projects:

```
adapton.rust, aero, athens, bevy, claude-code, clerk, cljfmt, clojure, clojure-mcp, clojurescript,
Common-Metadata-Repository, component-docs, compojure, conduit, core.async, core.logic, core.typed,
datalevin, datascript, editscript, electric, environ, exa-mcp-server, expresso, fastmcp, garden, honeysql,
integrant, javelin, kakoune, logseq, malli, meander, medley, Miscellaneous, missionary, morphdom, neanderthal,
neovim, onyx, opencode, overtone, pathom3, portal, prosemirror, quil, re-frame, re-frame-10x, Reactive-Programming,
reitit, replicant, rewrite-clj, ring, S, salsa, sci, slate, specter, thin_repos.py, tree-sitter, unison, vlojure, xi-editor, zed
```

**Query with:** `bb research explore <project> "query"` (see [skills/research/SKILL.md](skills/research/SKILL.md))

**Metadata:** `docs/research/sources/repos.edn` (LOC, trees, README snippets)

---

## Gotcha Docs

**REPLICANT.md** (`docs/REPLICANT.md`):
- Event handler syntax (`:event/target.value` interpolation)
- Common pitfalls with reactive attributes

**CHROME_DEVTOOLS.md** (`docs/CHROME_DEVTOOLS.md`):
- Stale snapshot issues
- Screenshot saving patterns

---

## Git Hooks

Run `scripts/install-hooks.sh` after cloning to install pre-commit hook.

**Note**: AGENTS.md is a symlink to CLAUDE.md - edit CLAUDE.md only.

---

## Investigation Tactics

- Use `bat`, `rg`, targeted file reads for token efficiency
- Check `dev/error-catalog.edn` for self-diagnosis patterns
- Use `docs/research/sources/repos.edn` for best-of repos metadata

---

## Testing Scripts with Faster Models

For testing scripts that use gemini, use the faster flash model:

```bash
gemini --model gemini-2.5-flash "test prompt"
```

**IMPORTANT:** Always TEST and SPOT CHECK your edits before the end of the session!
