## Table of Contents

**Quick Start:**
- [The Start](#the-start) - Session initialization, overview.md, LLM CLI usage
- [Environment Validation](#environment-validation) - API keys, preflight checks
- [Dev Tooling](#dev-tooling) - REPL, health checks, fixtures, config
- [NPM Commands](#npm-commands) - lint, test, fix:cache, agent:health

**Research & Analysis:**
- [Research Workflow](#research-workflow) - best-of repos exploration, repomix queries
- [LLM Provider CLIs](#llm-provider-clis) - gemini, codex, grok syntax
- [Architectural Proposals](#architectural-proposals-workflow) - proposal generation
- [Best-of Repos Research](#best-of-repos-research) - parallel research queries

**Development:**
- [Quick Reference Index](#quick-reference-index) - Core files, testing, dev tools, MCP
- [Dev Quality Gates](#dev-quality-gates) - pre-commit hooks, linting, module deps
- [Investigation Tactics](#investigation-tactics) - bat, rg, error-catalog

**Resources:**
- [Available Projects](#available-projects) - ~/Projects/best/* repos
- [Common Failure Modes](#common-failure-modes) - CLI troubleshooting

---

## The Start

**Start here**: Read both auto-generated overviews (created on src/ merge):
1. `AUTO-SOURCE-OVERVIEW.md` - The code/logic (kernel implementation)
2. `AUTO-PROJECT-OVERVIEW.md` - The project structure (tooling, docs, research)

For historical overviews, see `docs/overviews/`.

You can use the gemini, codex, grok, and opencode CLI tools with repomix to get
inspiration from the best-of projects.

USE gemini for high token-count queries (lots of text at once) 
USE codex at max settings for questions around style, refactorings, and architecture (it's elegant and powerful)


For dev tooling and infrastructure: keep it simple - I'm a solo developer using AI agents as helpers. Focus on the 80/20, not performance or production use.

### Environment Validation

**CRITICAL**: Always verify environment before running research scripts.

**Quick Check:**
```bash
# Verify API keys
test -f .env && echo "✓ .env found" || echo "✗ .env missing"
env | grep -E "(GROK|GEMINI|OPENAI)_API_KEY" | cut -d= -f1

# Full validation
scripts/validate-proposal-setup
```

**API Keys:**
- Store in `.env` (gitignored, auto-sourced by scripts)
- Required: `GROK_API_KEY`, `GEMINI_API_KEY`, `OPENAI_API_KEY`

### Output Validation Protocol

**ALWAYS verify output after running research scripts:**

```bash
# After any research/proposal script
LATEST=$(ls -td research/{proposals,results}/* 2>/dev/null | head -1)

# Check for errors (files < 1KB are usually error messages)
find "$LATEST" -name "*.md" -size -1k

# Verify content
head -20 "$LATEST"/**/*.md | grep -c "^#"  # Should have headers

# Check for error messages
grep -i "error\|failed\|not found" "$LATEST"/**/*.md
```

**Before claiming "complete":**
1. Check file sizes (should be > 5KB)
2. Read first 20 lines - look for actual content, not errors
3. Verify expected structure (markdown headers, sections)

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

**3. Use prompt template** (see `templates/research-prompt.md`):
```
How does {PROJECT} implement {FEATURE}?
Focus on: 1) {MECH_1} 2) {MECH_2} 3) {MECH_3} 4) {MECH_4}
Show code patterns.
```

**4. Parallel queries** (for multiple repos):
```bash
# Edit research/questions.edn, then:
scripts/parallel-research.sh research/questions.edn
# Results in: research/results/
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

**Parallel Query Protocol**:
- **No duplicate prompts**: Same prompt goes to each model once only
- **Vary questions**: Parallel queries should ask different questions or use different perspectives
- **Add variation**: Use UUIDs, timestamps, or perspective tags to ensure unique responses
- **Example perspectives**: "structural analysis", "performance patterns", "comparative design"
- **See**: `research/CLI_REFERENCE.md` for tested syntax and duplicate prevention patterns

**Architectural Proposals Workflow**:
```bash
# Generate 15 architectural proposals + 2 rankings (fully automated)
scripts/architectural-proposals

# Uses: project overview (not source) to avoid implementation bias
# Covers: kernel, indexes, pipeline, extensibility, DX
# Output: research/proposals/YYYY-MM-DD-HH-MM/{proposals,rankings}/
# See: research/ARCHITECTURAL_PROPOSALS.md for details
```

**Best-of Repos Research** (15 parallel queries):
```bash
# Query 5 best/ repos with 3 models each (15 parallel)
scripts/best-repos-research ["custom question"]

# Default: Analyze core data structures and operations
# Repos: datascript, reitit, re-frame, meander, specter
# Models: gemini, codex, grok (NO --full-auto for research)
# Output: research/results/YYYY-MM-DD-HH-MM/{repo}-{model}.md
# Cache: .repomix-cache/ (repomix results cached for speed)
```

**Analyze Battle-Test Results** (Codex synthesis):
```bash
# Analyze all results with Codex (pipes ALL context, no summarization)
scripts/analyze-battle-test [results-dir]

# Uses Codex reasoning-effort=high for pattern analysis
# Finds: consensus patterns, repo-specific insights, outliers
# Shows live progress (new output as it arrives)
# Output: {results-dir}/analysis.md
```

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
- `dev/bin/` - Dev commands (health-check.sh, preflight.sh)
- `dev/health.clj` - Environment diagnostics
- `dev/error-catalog.edn` - Error taxonomy with auto-fixes
- `scripts/generate-overview.sh` - Generate AI architectural docs

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
- `mcp/servers/dev_diagnostics.clj` - Minimal MCP server (Java SDK)
- `mcp/shared/` - Shared MCP utilities (future)
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

**Research & Analysis:**

- `research/CLI_REFERENCE.md` - Battle-tested CLI syntax (gemini, codex, grok)
- `research/ARCHITECTURAL_PROPOSALS.md` - Proposal generation workflow docs
- `research/architectural-questions.edn` - Question templates
- `research/proposal-ranker-prompt.md` - Ranking criteria
- `.agentlog/session-*.md` - Past session learnings and failure modes

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
