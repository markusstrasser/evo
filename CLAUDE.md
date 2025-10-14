## Table of Contents

**Quick Start:**
- [The Start](#the-start) - Session initialization, overview.md, LLM CLI usage
- [Environment Validation](#environment-validation) - API keys, preflight checks
- [Dev Tooling](#dev-tooling) - REPL, health checks, fixtures, config
- [Debugging Workflow](#debugging-workflow) - REPL-first debugging, browser console, fast iteration
- [NPM Commands](#npm-commands) - lint, test, fix:cache, agent:health

**Research & Analysis:**
- [Research Workflow](#research-workflow) - best-of repos exploration, repomix queries
- [LLM Provider CLIs](#llm-provider-clis) - gemini, codex, grok syntax
- [MCPs](#mcps) - tournament, architect, researcher subagent

**Development:**
- [Quick Reference Index](#quick-reference-index) - Core files, testing, dev tools, MCP
- [Dev Quality Gates](#dev-quality-gates) - pre-commit hooks, linting, module deps
- [Investigation Tactics](#investigation-tactics) - bat, rg, error-catalog
- [Visual Validation](#visual-validation) - Reference analysis, actionable comparison

**Resources:**
- [Available Projects](#available-projects) - ~/Projects/best/* repos
- [Common Failure Modes](#common-failure-modes) - CLI troubleshooting

**Gotcha Docs:**
- `docs/REPLICANT.md` - Event handler syntax, :event/target.value, interpolation
- `docs/CHROME_DEVTOOLS.md` - Stale snapshots, screenshot saving
- `.agentlog/gemini-media-fixes-2025-10-02.md` - Binary uploads, HTTP client bugs

---

## The Start

**Start here**: Read both auto-generated overviews (created on src/ merge):
1. `AUTO-SOURCE-OVERVIEW.md` - The code/logic (kernel implementation)
2. `AUTO-PROJECT-OVERVIEW.md` - The project structure (tooling, docs, research)

Prompts: `AUTO-SOURCE-OVERVIEW-PROMPT.md`, `AUTO-PROJECT-OVERVIEW-PROMPT.md`

You can use the gemini, codex, grok, and opencode CLI tools with repomix to get
inspiration from the best-of projects.

USE gemini for high token-count queries (lots of text at once) 
USE codex at max settings for questions around taste, style, refactorings, and architecture (it's elegant and powerful)


For dev tooling and infrastructure: keep it simple - I'm a solo developer using AI agents as helpers. Focus on the 80/20, not performance or production use.

### Environment Validation

**CRITICAL**: Always verify environment before running research scripts.

**Quick Check:**
```bash
# Verify API keys
test -f .env && echo "✓ .env found" || echo "✗ .env missing"
env | grep -E "(GROK|GEMINI|OPENAI)_API_KEY" | cut -d= -f1
```

**API Keys:**
- Store in `.env` (gitignored, auto-sourced by scripts)
- Required: `GROK_API_KEY`, `GEMINI_API_KEY`, `OPENAI_API_KEY`


### Research Workflow

**Path**: `~/Projects/best/{projectname}`

**1. Explore structure first (for large repos):**
```bash
# Check size and structure
tree -L 3 -d ~/Projects/best/{projectname}
tokei ~/Projects/best/{projectname}  # Token/LOC counts

# For large repos (>50MB), zoom into subdirectories
tree -L 2 ~/Projects/best/clojurescript/src/main/clojure
```

**2. Query with focused paths:**
```bash
# Small repos (<10MB): include full src
repomix ~/Projects/best/{projectname} --copy --output /dev/null \
  --include "src/**,README.md" > /dev/null 2>&1 && \
  pbpaste | gemini --allowed-mcp-server-names context-prompt content-prompt -y -p "YOUR_QUESTION"

# Large repos: zoom into specific subdirs (saves context, gets deeper insights)
repomix ~/Projects/best/clojurescript/src/main/clojure/cljs \
  --include "compiler.clj,analyzer.cljc" \
  --copy --output /dev/null > /dev/null 2>&1 && \
  pbpaste | gemini -y -p "YOUR_QUESTION"
```

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

**Grok** (curl wrapper at `scripts/grok`):
```bash
scripts/grok -p "question"
scripts/grok -m grok-4-latest -p "question"
echo "question" | scripts/grok

# Requires: export XAI_API_KEY="your-key"
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

**REPL-First Debugging** - Use REPL for rapid hypothesis testing before code changes:

```clojure
;; In browser console (debug helpers auto-loaded in dev mode):
DEBUG.summary()           // State overview: cards, events, stacks
DEBUG.events()            // All events
DEBUG.inspectEvents()     // Recent events with ✅/❌ status
DEBUG.activeEvents()      // Only active events
DEBUG.undoneEvents()      // Only undone events
DEBUG.cards()             // All cards
DEBUG.dueCards()          // Cards due now
DEBUG.undoStack()         // Current undo stack
DEBUG.redoStack()         // Current redo stack
DEBUG.reload()            // Hard reload page

// Test theories quickly:
DEBUG.events().length                    // How many events?
DEBUG.activeEvents().length              // How many active?
core.build_event_status_map(DEBUG.events())  // Check status map
```

**Testing Workflow:**
```bash
# Quick test specific namespace
scripts/quick-test.sh lab.anki.core-test

# All tests
scripts/quick-test.sh
```

**Process: Fast Debugging Loop**

❌ **Slow way (what NOT to do):**
1. Make educated guess
2. Edit code
3. Wait for compile
4. Reload browser
5. Check console
6. Repeat...

✅ **Fast way (REPL-first):**
1. **Reproduce in REPL/console first** - Verify the problem
2. **Test hypothesis in REPL** - Try fixes interactively
3. **Only then update code** - Apply the working fix
4. **Verify with browser** - Final integration test

**Example: Debugging Array.from Bug**

```clojure
;; ❌ Slow: Edit code → compile → reload (5+ iterations)

;; ✅ Fast: Test in REPL (30 seconds):
(def handle (js/showDirectoryPicker))  ; user selects
(def values (.values handle))
(js/Array.from values)  ; => [] - AHA! Empty!
(.next values)          ; => Promise! It's async!
;; Now fix code once, done
```

**Browser State Inspection:**
```javascript
// Check if new code loaded
() => lab.anki.fs.load_all_md_files.toString().includes("Processing entry")

// Inspect current state
() => ({
  cards: cljs.core.count(cljs.core.get(state, cljs.core.keyword("cards"))),
  events: DEBUG.events().length,
  undoStack: DEBUG.undoStack().length
})
```

**Common Pitfalls:**
- Browser cache: Use DEBUG.reload() for hard refresh
- Async iteration: `Array.from(asyncIterator)` returns empty - use `for await` or promises
- Event sourcing: Use DEBUG.inspectEvents() to see active/undone status
- Stale code: Check console for "🔧 Loading debug helpers..." on page load

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

- `scripts/visual-analyze-reference` - Extract wave patterns, lighting, geometry from reference
- `scripts/visual-compare-actionable` - Get specific fixes (e.g., "Scene 140% too bright → reduce lighting")
- `scripts/README-VISUAL-VALIDATION.md` - Complete toolkit docs (basic + ML stages)
- `docs/VISUAL_VALIDATION.md` - Architecture and design

**Workflow:**
```bash
# 1. Analyze reference (what to implement)
scripts/visual-analyze-reference reference.png
# → Shows: 15 rings, spacing 17.1px, frequency ~6, brightness 71.2

# 2. Compare and get fixes
scripts/visual-compare-actionable reference.png impl.png
# → Shows: "Scene 140% too bright → dir-light1: intensity *= -0.40"
```

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