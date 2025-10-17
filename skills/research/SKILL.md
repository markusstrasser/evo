---
name: Research Best-Of Repositories
description: Query 40+ Clojure/ClojureScript reference projects from ~/Projects/best/* using repomix + llmx. Find patterns, idioms, architectural examples, and code comparisons. Triggers on research, best-of, patterns, inspiration, code examples. Requires llmx CLI, repomix, and API keys (GEMINI_API_KEY, OPENAI_API_KEY, XAI_API_KEY).
---

# Research Workflow

## Prerequisites

**Tools:**
- `repomix` - Repository bundling (must be in PATH)
- `llmx` - Unified LLM CLI (100+ providers)

**Environment:**
```bash
# Required API keys in .env
GEMINI_API_KEY=your-key
OPENAI_API_KEY=your-key
XAI_API_KEY=your-key
```

**Data:**
- 40+ curated projects in `~/Projects/best/`
- See `data/repos.edn` for complete list with metadata

## Quick Start

```bash
# Query a small repo
repomix ~/Projects/best/malli --copy --output /dev/null | \\
  llmx --provider google --model gemini-2.5-pro "How does schema composition work?"

# Query large repo (focused)
repomix ~/Projects/best/clojurescript/src/main/clojure/cljs \\
  --include "compiler.clj,analyzer.cljc" --copy --output /dev/null | \\
  llmx --provider openai --model gpt-5-codex "Explain macro expansion"

# Compare projects
repomix ~/Projects/best/re-frame/src --include "**/subs.cljs" --copy && \\
repomix ~/Projects/best/electric/src --include "**/reactive.clj*" --copy | \\
  llmx --provider openai --model gpt-5-codex "Compare reactive patterns"
```

## When to Use

Use this skill when you need to:
- Find idiomatic Clojure patterns
- Research architectural approaches
- Understand library usage patterns
- Compare implementations across projects
- Validate design decisions against production code

## Available Projects

**Core Clojure:**
clojure, clojurescript, core.async, core.logic, core.typed

**Data & State:**
datascript, datalevin, re-frame, specter, meander

**UI & Reactive:**
replicant, electric, javelin, reagent

**Web & API:**
ring, compojure, reitit, pathom3

**Build & Dev:**
shadow-cljs, clerk, portal, component

See `data/repos.edn` for complete list (LOC, languages, descriptions).

## Model Selection

| Provider | Model | Use For | Cost | Speed |
|----------|-------|---------|------|-------|
| google | gemini-2.5-pro | High-token queries, large repos, sessions | Medium | Medium |
| openai | gpt-5-codex | Code review, architecture, taste | High | Slow |
| xai | grok-4-latest | Quick queries, fallback | Medium | Medium |

**Critical Policy:** Never use gemini-flash, gpt-4o, or gpt-4-turbo for Skills work. Token savings from progressive disclosure outweigh model costs.

## Query Patterns

### Pattern 1: Small Repo (<10MB)

For projects like `aero`, `environ`, `malli`:

```bash
# 1. Extract full src + README
repomix ~/Projects/best/malli --copy --output /dev/null \\
  --include "src/**,README.md"

# 2. Query with gemini
pbpaste | llmx --provider google --model gemini-2.5-pro \\
  "YOUR_QUESTION"
```

### Pattern 2: Large Repo (>50MB) Focused Query

For projects like `clojurescript`, `athens`, `logseq`:

```bash
# 1. Explore structure first
tree -L 3 -d ~/Projects/best/clojurescript
tokei ~/Projects/best/clojurescript

# 2. Zoom into specific subdirectories
repomix ~/Projects/best/clojurescript/src/main/clojure/cljs \\
  --include "compiler.clj,analyzer.cljc" --copy --output /dev/null

# 3. Query with codex (high reasoning)
pbpaste | llmx --provider openai --model gpt-5-codex \\
  "YOUR_QUESTION"
```

### Pattern 3: Multi-Project Comparison

```bash
# Extract from multiple projects
repomix ~/Projects/best/re-frame/src --include "**/subs.cljs" \\
  --copy --output /tmp/re-frame.txt

repomix ~/Projects/best/electric/src --include "**/reactive.clj*" \\
  --copy --output /tmp/electric.txt

# Compare patterns
cat /tmp/re-frame.txt /tmp/electric.txt | \\
  llmx --provider openai --model gpt-5-codex \\
  "Compare reactive state patterns"
```

## Common Query Templates

### Event Sourcing
**Question:** "How is event sourcing implemented? Show event log structure, transaction handling, and replay mechanisms."
**Relevant projects:** datascript, datalevin, electric

### Reactive State
**Question:** "How is reactive state managed? Show subscription patterns, state propagation, and performance optimizations."
**Relevant projects:** re-frame, electric, javelin, reagent

### Macro Patterns
**Question:** "What are common macro patterns? Show code generation, compile-time optimization, and DSL implementation."
**Relevant projects:** clojurescript, core.async, specter

### API Design
**Question:** "How are APIs designed for composability? Show function composition, middleware patterns, and extension points."
**Relevant projects:** ring, compojure, reitit, pathom3

### Testing Patterns
**Question:** "What testing patterns are used? Show test organization, fixture management, and property-based testing."
**Relevant projects:** clojure, datascript, malli

### Build Workflow
**Question:** "How is the build workflow organized? Show compilation, optimization, and hot-reload setup."
**Relevant projects:** shadow-cljs, clerk, clojurescript

## Repository Size Thresholds

- **< 10 MB:** Extract full src/ directory
- **50-200 MB:** Use focused paths (specific subdirs/files)
- **> 200 MB:** Multiple focused queries recommended

Check size with: `du -sh ~/Projects/best/{project}`

## Environment Variables

**Optional configuration:**
```bash
# Override default model
export RESEARCH_DEFAULT_MODEL="gemini-2.5-pro"

# Set timeouts (seconds)
export REPOMIX_TIMEOUT=30
export LLM_TIMEOUT=120
```

## Tips & Best Practices

1. **Start with structure exploration** - Use `tree` and `tokei` before extracting
2. **Be specific with includes** - `--include "pattern"` reduces tokens
3. **Choose the right model** - gemini for breadth, codex for depth
4. **Validate outputs** - Check file sizes (`wc -l`, `du -h`)
5. **Handle errors gracefully** - Check stderr for repomix failures

## Common Pitfalls

- **Empty output:** repomix failed silently, check stderr
- **Token limits:** Query too large, use focused includes
- **API timeouts:** Use faster model or retry
- **Stale data:** Regenerate repos.edn with `docs/research/sources/update-repos.sh`

## Troubleshooting

**"No such file or directory"**
- Verify: `ls ~/Projects/best/{project}`
- Check `data/repos.edn` for correct path

**"Command timed out"**
- Use faster model: `--model gemini-2.5-flash`
- Break query into smaller parts

**"Empty response"**
- Check API keys: `env | grep -E "(GEMINI|OPENAI|XAI)_API_KEY"`
- Source .env: `source .env`

## Resources (Level 3)

- `data/repos.edn` - Project metadata (names, LOC, languages, trees)
- `run.sh` - CLI wrapper for common workflows (optional)
- `examples/` - Usage examples:
  - `example-quick-query.sh` - Fast single-project lookup
  - `example-comparison.sh` - Cross-project pattern analysis

## See Also

- Project docs: `../../CLAUDE.md#research-workflow`
- LLM CLI guide: `../../CLAUDE.md#llm-provider-clis`
- Repomix docs: `~/Projects/best/repomix/README.md`
