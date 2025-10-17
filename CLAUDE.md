## Table of Contents

**⚡ Skills (Progressive-Disclosure Workflows):**
- [Agent Skills Overview](#agent-skills-overview) - L1/L2/L3 loading, token savings
- [Research Skill](#research-workflow) - Query best-of repos (40+ projects)
- [Visual Validation Skill](#visual-validation) - Canvas/WebGL analysis
- [REPL Debug Skill](#debugging-workflow) - REPL-first debugging
- [Diagnostics Skill](#environment-validation--dev-diagnostics) - Health checks, cache management

**Quick Start:**
- [The Start](#the-start) - Session initialization, overview.md, LLM CLI usage
- [Dev Tooling](#dev-tooling) - REPL, health checks, fixtures, config
- [NPM Commands](#npm-commands) - lint, test, fix:cache, agent:health

**Research & Analysis:**
- [LLM Provider CLIs](#llm-provider-clis) - gemini, codex, grok syntax
- [MCPs](#mcps) - tournament, architect, researcher subagent
- [Available Projects](#available-projects) - ~/Projects/best/* repos

**Development:**
- [Quick Reference Index](#quick-reference-index) - Core files, testing, dev tools, MCP
- [Dev Quality Gates](#dev-quality-gates) - pre-commit hooks, linting, module deps
- [Investigation Tactics](#investigation-tactics) - bat, rg, error-catalog

**Resources:**
- [Toolbox Index](#toolbox-index) - All tools (MCPs, Skills, CLIs, Scripts)
- [Common Failure Modes](#common-failure-modes) - CLI troubleshooting

**Gotcha Docs:**
- `docs/REPLICANT.md` - Event handler syntax, :event/target.value, interpolation
- `docs/CHROME_DEVTOOLS.md` - Stale snapshots, screenshot saving
---

## The Start

**Start here**: Read both auto-generated overviews (created on src/ merge):
1. `dev/overviews/AUTO-SOURCE-OVERVIEW.md` - The code/logic (kernel implementation)
2. `dev/overviews/AUTO-PROJECT-OVERVIEW.md` - The project structure (tooling, docs, research)

Prompts: `dev/overviews/AUTO-SOURCE-OVERVIEW-PROMPT.md`, `dev/overviews/AUTO-PROJECT-OVERVIEW-PROMPT.md`

You can use the gemini, codex, grok, and opencode CLI tools with repomix to get
inspiration from the best-of projects.

USE gemini for high token-count queries (lots of text at once) 
USE codex at max settings for questions around taste, style, refactorings, and architecture (it's elegant and powerful)


For dev tooling and infrastructure: keep it simple - I'm a solo developer using AI agents as helpers. Focus on the 80/20, not performance or production use.

## Agent Skills Overview

**What are Skills?** Filesystem-based workflows with progressive disclosure:
- **L1 (Metadata)**: ~100 tokens, always loaded for discovery
- **L2 (Instructions)**: 3-5k tokens, loaded when triggered
- **L3+ (Resources)**: Unlimited, loaded as needed

**Token Savings:** ~14.5k tokens per session (97% reduction vs loading all docs)

### Available Skills

**📚 Research** (`skills/research/`)
- Query 40+ best-of Clojure/ClojureScript repos
- Model selection (gemini/codex/grok)
- Small/large repo strategies
- **Triggers**: research, best-of, patterns, inspiration

**🎨 Visual Validation** (`skills/visual/`)
- Canvas/WebGL analysis (waves, lighting, geometry)
- Compare reference vs implementation
- Get actionable fixes
- **Triggers**: visual, canvas, validate, compare

**🐛 REPL Debugging** (`skills/repl-debug/`)
- REPL-first debugging workflow
- Browser console helpers
- Common pitfalls catalog
- **Triggers**: debug, repl, troubleshoot

**🏥 Dev Diagnostics** (`skills/diagnostics/`)
- Environment health checks
- Cache management
- Error diagnosis
- **Triggers**: health, preflight, cache, diagnose

**Architecture:** All skills follow official Claude Skills best practices:
- YAML frontmatter only (name + description, max 1024 chars)
- All configuration as markdown tables/sections in SKILL.md
- Progressive disclosure: L1 (metadata) → L2 (instructions) → L3 (resources)
- No EDN config files (migrated 2025-10-17)

**See also:** `dev/tooling-index.edn` for complete tool registry (MCPs, Skills, CLIs, Scripts)

### Environment Validation & Dev Diagnostics

**Use the Dev Diagnostics Skill for all environment checks:**

```bash
# Quick health check
skills/diagnostics/run.sh health

# Pre-flight before starting work
skills/diagnostics/run.sh preflight

# Check API keys
skills/diagnostics/run.sh api-keys check
```

**See:** `skills/diagnostics/SKILL.md` for full documentation.

### Research Workflow

**Use the Research Skill to query best-of repositories:**

```bash
# List available projects
skills/research/run.sh list

# Explore a project (uses llmx unified CLI)
skills/research/run.sh explore malli "schema composition patterns"

# Compare across projects
skills/research/run.sh compare "re-frame,electric" "reactive state"
```

**See:** `skills/research/SKILL.md` for full documentation including:
- Model selection (google/openai/xai providers via llmx)
- Small vs large repo strategies
- Multi-turn research sessions
- Common queries and patterns

### LLM Provider CLIs

**Gemini** (context-prompt, content-prompt MCP servers available):
```bash
gemini -y -p "question"
gemini --allowed-mcp-server-names -p "question"

# Session management (manual save/resume)
gemini
> /chat save architecture-research
> ... ask questions ...
> /chat list
> /chat resume architecture-research

# Or track session in file
gemini --session-summary session.txt
```


```bash
# Interactive (for questions/answers)
codex -m gpt-5-codex -c model_reasoning_effort="high" "your question"

# Non-interactive batch (for agents/automation)
echo "prompt" | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -

# IMPORTANT:
# - Use regular codex for Q&A (safer, interactive)
# - Use codex exec --full-auto ONLY for agents/automation
# - ALWAYS use model_reasoning_effort="high" for code review/taste

# Session continuity
codex --resume    # Resume last session
# Sessions stored in: ~/.codex/sessions/*.jsonl
```

**llmx** - Unified API wrapper (100+ providers via LiteLLM):
```bash
# Default provider (Google)
llmx "your prompt"

# Specific providers
llmx --provider xai "prompt"      # Grok
llmx --provider google "prompt"   # Gemini
llmx --provider openai "prompt"   # OpenAI
llmx --provider anthropic "prompt" # Claude

# With specific model
llmx --provider xai -m grok-4-latest "prompt"

# From stdin
echo "prompt" | llmx --provider xai

# Compare providers
llmx --compare "Which is better: tabs or spaces?"
llmx --compare --providers google,openai,xai "prompt"

# Non-streaming for scripts
llmx --provider xai --no-stream "prompt" > output.txt

# Debug logging
llmx --debug "prompt" 2>&1 | grep "🔍"

# Requires: API keys in .env (GEMINI_API_KEY, OPENAI_API_KEY, XAI_API_KEY, etc.)
```

**Multi-turn Research Pattern**:
```bash
# Initial query
codex --model gpt-5 --reasoning-effort high -p "...prompt"
```

**Note**: Research and refactor agents use even split across providers for
diverse perspectives.

### MCPs

**researcher subagent** - Deep research using Context7, Exa, best-of repos. Writes to `.architect/reports/`.

**architect-mcp** (`~/Projects/architect-mcp/`) - Proposal → tournament → ADR workflow. Writes to `.architect/review-runs/`.

**tournament-mcp** (`~/Projects/tournament-mcp/`) - Bradley-Terry ranking via Swiss-Lite tournaments. Used by architect-mcp.

See `~/.claude/agents/researcher.md` for subagent spec. MCPs advertise their own tools via `/mcp`.

### Available Projects

The path is `~/Projects/best/{projectname}`. Here are the projects:

<projects-for-inspiration>
adapton.rust, aero, athens, bevy, claude-code, clerk, cljfmt, clojure, clojure-mcp, clojurescript, 
Common-Metadata-Repository, component-docs, compojure, conduit, core.async, core.logic, core.typed, 
datalevin, datascript, editscript, electric, environ, exa-mcp-server, expresso, fastmcp, garden, honeysql, 
integrant, javelin, kakoune, logseq, malli, meander, medley, Miscellaneous, missionary, morphdom, neanderthal, 
neovim, onyx, opencode, overtone, pathom3, portal, prosemirror, quil, re-frame, re-frame-10x, Reactive-Programming, 
reitit, replicant, rewrite-clj, ring, S, salsa, sci, slate, specter, thin_repos.py, tree-sitter, unison, vlojure, xi-editor, zed
</projects-for-inspiration>


### Dev Tooling

**Quick start**: `(require '[repl :as repl]) (repl/init!)`

**Key utilities:**
- `dev/repl/` - REPL helpers (init.clj, session.clj)
- `dev/debug.cljs` - Browser/REPL debugging helpers (NEW)
- `dev/bin/` - Dev commands (health-check.sh, preflight.sh)
- `dev/health.clj` - Environment diagnostics
- `dev/error-catalog.edn` - Error taxonomy with auto-fixes
- `scripts/generate-overview.sh` - Generate overviews (--source, --project, --auto, -t path, -m model)
- `scripts/quick-test.sh` - Run specific test namespace quickly

### Debugging Workflow

**Use the REPL-First Debugging Skill for interactive debugging:**

**Core philosophy:** Test hypotheses in REPL BEFORE editing code (30 seconds vs 5+ minutes)

**Quick reference:**
```bash
# Show debugging guide
skills/repl-debug/run.sh guide

# Common patterns
skills/repl-debug/run.sh patterns

# Browser console helpers
skills/repl-debug/run.sh browser-helpers
```

**Browser DEBUG helpers (always available in dev mode):**
```javascript
DEBUG.summary()       // State overview
DEBUG.events()        // All events
DEBUG.inspectEvents() // Recent with ✅/❌ status
DEBUG.reload()        // Hard reload
```

**See:** `skills/repl-debug/SKILL.md` for full documentation including:
- REPL-first workflow (reproduce → test → apply → verify)
- Common debugging patterns
- Browser console helpers
- Common pitfalls and solutions

### NPM Commands

- `npm run lint` - run clj-kondo linter
- `npm run check` - lint + compile (full check)
- `npm test` - run test suite
- `npm run fix:cache` - clear all caches
- `npm run agent:health` - environment diagnostics
- `npm run agent:preflight` - pre-flight checks (needs dev server)
- `npm run repl:health` - quick REPL diagnostics
- `npm run docs:overview` - generate AI architectural overview (default: all
  sections, src/)

### Investigation Tactics

- `bat`, `rg`, targeted file reads for token efficiency
- Check `dev/error-catalog.edn` for self-diagnosis patterns
- Use `docs/research/sources/repos.edn` for best-of repos metadata

### Dev Quality Gates

- **Pre-commit**: `.pre-commit-check.sh` - linting, tests, CLJS imports, namespace/path validation
- **Linting**: `.clj-kondo/config.edn` - catches errors, enforces style (pure functions, explicit data flow)
- **Module isolation**: Core kernel modules separate from shell concerns

### Standing Instructions

- Maintain kernel transaction architecture, invariants, instrumentation focus
- Prefer synchronous/pure patterns unless async explicitly justified
- Skip tests for docs-only changes; note "Tests: not run"

### Toolbox Index

**Complete tool registry:** `dev/tooling-index.edn`

This EDN file maps all development tools (MCPs, Skills, CLIs, Scripts) with:
- Natural language triggers
- Tool descriptions and purposes
- Paths and configurations
- Migration history (285 lines of config.edn removed, 2025-10-17)

**Skills Architecture (Post-Migration):**
- All skills use YAML frontmatter only (official Claude Skills standard)
- Configuration stored as markdown tables in SKILL.md
- No separate config.edn files
- Research skill uses llmx unified CLI (replaced vendor-specific CLIs where appropriate)

**Usage:**
```clojure
;; Load index
(def idx (-> "dev/tooling-index.edn" slurp edn/read-string))

;; Find research tools
(keys (:skills idx))
;=> (:research :visual-validate :repl-debug :dev-diagnostics :architect)

;; Check migration status
(get-in idx [:migration :totals])
;=> {:config-lines-removed 285, :files-deleted 5, :skills-migrated 6}
```

### Quick Reference Index

**Core Architecture:**

- `src/core/db.cljc` - canonical DB shape, validation, derived indexes
- `src/core/ops.cljc` - three core operations (create, place, update)
- `src/core/interpret.cljc` - transaction pipeline (normalize → validate →
  apply)
- `src/core/schema.cljc` - Malli schemas for operations and data

**Testing:**

- `test/core_interpret_test.cljc` - comprehensive operation and pipeline tests
- `dev/fixtures.cljc` - test data generators and utilities

**Development:**

- `dev/repl/` - REPL helpers: init.clj (`connect!`, `init!`), session.clj (`quick-health-check!`)
- `dev/bin/` - Scripts: health-check.sh, preflight.sh
- `dev/health.clj` - Environment diagnostics (`preflight-check!`, `cache-stats`)
- `dev/error-catalog.edn` - Error taxonomy with auto-fixes
- `.pre-commit-check.sh` - Quality gates: linting, tests, namespace checks

**MCP Integration:**

- `docs/MCP.md` - Complete MCP reference (config, failure modes, patterns)
- `mcp/servers/dev_diagnostics.clj` - Clojure dev diagnostics MCP (Java SDK)
- `~/Projects/tournament-mcp/` - Tournament-based LLM judge comparison (FastMCP)
- `~/Projects/architect-mcp/` - Architectural decision workflow (FastMCP)
- `.mcp.json` - Project MCP server config
- `~/.claude.json` - Global/project-scoped MCP config
- `~/Projects/best/modelcontextprotocol/docs/` - Full MCP spec (121 MDX files)
  - `docs/learn/architecture.mdx` - Protocol concepts
  - `docs/develop/build-server.mdx` - Server guide
  - `specification/` - Versioned specs (2024-11-05, 2025-03-26, 2025-06-18)

**Research Sources:**

- `docs/research/sources/repos.edn` - Best-of repos with LOC, trees, README snippets
- `docs/research/sources/update-repos.sh` - Regenerate repo stats (tokei + tree)
- `~/Projects/best/` - Cloned reference repos for inspiration

**Visual Validation:**

**Use the Visual Validation Skill for canvas/WebGL analysis:**

```bash
# Analyze reference
skills/visual/run.sh analyze reference.png

# Compare and get fixes
skills/visual/run.sh compare reference.png implementation.png

# Focus on specific aspect
skills/visual/run.sh compare ref.png impl.png --aspect lighting
```

**See:** `skills/visual/SKILL.md` for full documentation including:
- Wave pattern extraction (rings, spacing, frequency)
- Lighting analysis (brightness, contrast)
- Geometry measurements
- Actionable fix generation

**Common Failure Modes:**

- **Codex batch mode**: Use `codex exec ... -` not `codex -p -`
- **API keys**: Scripts auto-source `.env`, verify with `env | grep API_KEY`
- **Empty output**: Check file sizes after runs (< 1KB = error)
- **Gemini timeouts**: Use `--allowed-mcp-server-names ""` for batch

**Config & Setup:**

- `shadow-cljs.edn` - ClojureScript build config
- `deps.edn` - Clojure dependencies and aliases
- `package.json` - npm scripts for dev workflow
- `.clj-kondo/config.edn` - linting rules and module boundaries
### Git Hooks

Run `scripts/install-hooks.sh` after cloning to install pre-commit hook.

**Note**: AGENTS.md is a symlink to CLAUDE.md - edit CLAUDE.md only.
- ALWAYS TEST and SPOT CHECK YOUR EDITS before the end of the session!
- For testing scripts that use gemini, you can test faster using the dumber model:  "gemini --model gemini-2.5-flash"