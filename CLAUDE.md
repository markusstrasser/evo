## The Start

To begin the session read the ENTIRE most recent overview.md inside the docs/
folder.

You can use the gemini, codex, and opencode CLI tools with repomix to get
inspiration from the best-of projects.

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
codex --model gpt-5 --reasoning-effort high -p "question"  # Max reasoning

# Session continuity
codex --continue  # Resume last session
codex --resume    # Choose which session to resume
# Sessions stored in: ~/.codex/sessions/*.jsonl
```


**Multi-turn Research Pattern**:
```bash
# Initial query
codex --model gpt-5 --reasoning-effort high -p "...prompt"
```

**Note**: Research and refactor agents use even split across providers for
diverse perspectives.

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
- **REPL**: `dev/repl.clj` - shadow-cljs bridge (`connect!`, `init!`, `cljs!`, `clj!`)
- **Health**: `dev/health.clj` - diagnostics (`preflight-check!`, `cache-stats`, `check-repl-state`)
- **Session**: `dev/session.clj` - REPL state persistence (`save-session!`, `restore-session!`, `quick-health-check!`)
- **Fixtures**: `dev/fixtures.cljc` - test data builders (`make-db`, `gen-linear-tree`)
- **Config**: `dev/config.edn` - central config (paths, ports, timeouts)
- **Errors**: `dev/error-catalog.edn` - error taxonomy with remedies and auto-fixes
- **Preflight**: `dev/preflight.edn` + `dev/scripts/preflight.sh` - startup checks
- **Health Check**: `dev/scripts/health-check.sh` - dev environment diagnostics
- **AI Overview**: `scripts/generate-overview.sh` - AI repo info script (uses repomix/bat + gemini)
  - Generate architectural docs from codebase
  - Target: `-t src/` (dir/files), sections by name/number (1-9)
  - Focus: `-p "text"` appends custom prompt for specific analysis
  - Examples: `performance`, `1-3`, `-t src/core/db.cljc ops`, `-p "Focus on cycles" 1`
  - Auto-runs on git merge if `src/` changes (`.git/hooks/post-merge`)
- **Quick start**: `(require '[repl :as repl]) (repl/init!)`
- **Docs**: `dev/README.md`

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
- Check error-catalog.edn for self-diagnosis patterns
- Use semantic search: `@docs/SemanticSearch.md`

### Dev Quality Gates

- **Pre-commit**: `.pre-commit-check.sh` - enhanced with CLJS import validation
- **Linting**: `.clj-kondo/config.edn` - comprehensive rules based on STYLE.md
    - Catches: invalid arity, shadowed vars, redundant code, unused bindings
    - Enforces: consistent aliases (m, set, str), no refer-all
    - Style: prefers pure functions, explicit data flow
- **Module Deps**: Core kernel modules isolated from shell concerns

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

- `dev/session.clj` - REPL management (`quick-health-check!`)
- `dev/error-catalog.edn` - common issues with auto-remediation
- `dev/scripts/health-check.sh` - environment validation
- `.pre-commit-check.sh` - quality gates with CLJS validation

**Config & Setup:**

- `shadow-cljs.edn` - ClojureScript build config
- `deps.edn` - Clojure dependencies and aliases
- `package.json` - npm scripts for dev workflow
- `.clj-kondo/config.edn` - linting rules and module boundaries
### Git Hooks
Run `scripts/install-hooks.sh` after cloning to install pre-commit hook that syncs CLAUDE.md → AGENTS.md.
