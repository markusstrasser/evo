# Research Workflow

<!-- L1: Metadata (always loaded, ~100 tokens) -->
**Name:** Research Best-Of Repositories
**Description:** Query reference Clojure/ClojureScript projects from ~/Projects/best/* to find patterns, idioms, and architectural examples.
**Triggers:** research, best-of, reference projects, code patterns, inspiration
**Network Required:** Yes (calls LLM provider CLIs)
**Resources:** repos.edn, repomix, LLM orchestration

---

<!-- L2: Instructions (loaded when skill triggered, <5k tokens) -->

## Overview

This skill packages the research workflow for exploring high-quality Clojure/ClojureScript reference projects. It orchestrates:
1. Repository exploration and size analysis
2. Focused code extraction via `repomix`
3. LLM provider selection based on query type
4. Multi-turn research sessions with context management

## When to Use

Use this skill when you need to:
- Find idiomatic Clojure patterns (e.g., "How do ClojureScript projects handle reactive state?")
- Research architectural approaches (e.g., "Event sourcing patterns in Clojure")
- Understand library usage (e.g., "re-frame subscription patterns")
- Compare implementations across projects
- Validate design decisions against production codebases

## Available Projects

The skill has access to 40+ curated projects in `~/Projects/best/`:

**Core Clojure:**
- clojure, clojurescript, core.async, core.logic, core.typed

**Data & State:**
- datascript, datalevin, re-frame, specter, meander

**UI & Reactive:**
- replicant, electric, javelin, reagent

**Web & API:**
- ring, compojure, reitit, pathom3

**Build & Dev:**
- shadow-cljs, clerk, portal, component

**See `data/repos.edn` for complete list with metadata (LOC, languages, descriptions)**

## LLM Provider Selection

Choose based on query characteristics:

- **Gemini** (`gemini`):
  - High token-count queries (>50k tokens)
  - Large repo exploration (full src/ trees)
  - Multi-file analysis
  - Session continuity with `/chat save`

- **Codex** (`codex`):
  - Code review and taste questions
  - Architecture and refactoring advice
  - Style and idiom analysis
  - Use with `model_reasoning_effort="high"`

- **Grok** (`grok`):
  - Quick queries
  - Fallback option
  - Single-file analysis

## Query Patterns

### Pattern 1: Small Repo Query (<10MB)

For projects like `aero`, `environ`, `malli`:

```bash
# 1. Include full src + README
repomix ~/Projects/best/{project} --copy --output /dev/null \\
  --include "src/**,README.md" > /dev/null 2>&1

# 2. Query with context
pbpaste | gemini -y -p "YOUR_QUESTION"
```

### Pattern 2: Large Repo Focused Query (>50MB)

For projects like `clojurescript`, `athens`, `logseq`:

```bash
# 1. Explore structure first
tree -L 3 -d ~/Projects/best/{project}
tokei ~/Projects/best/{project}

# 2. Zoom into specific subdirectories
repomix ~/Projects/best/clojurescript/src/main/clojure/cljs \\
  --include "compiler.clj,analyzer.cljc" \\
  --copy --output /dev/null > /dev/null 2>&1

# 3. Query focused context
pbpaste | codex -m gpt-5-codex -c model_reasoning_effort="high" "YOUR_QUESTION"
```

### Pattern 3: Multi-Project Comparison

Compare implementations across projects:

```bash
# 1. Extract relevant files from multiple projects
repomix ~/Projects/best/re-frame/src --include "**/subs.cljs" --copy --output /dev/null
# Save to file1.txt

repomix ~/Projects/best/electric/src --include "**/reactive.clj*" --copy --output /dev/null
# Save to file2.txt

# 2. Compare patterns
cat file1.txt file2.txt | codex -c model_reasoning_effort="high" -p "Compare reactive patterns"
```

### Pattern 4: Multi-Turn Research Session

For deep investigation:

```bash
# 1. Start gemini session
gemini
> /chat save architecture-research

# 2. Query incrementally
> Load clojurescript compiler architecture
[paste repomix output]

> Now show me how macroexpansion works
[paste relevant files]

# 3. Resume later
> /chat list
> /chat resume architecture-research
```

## Workflow Scripts

The skill provides wrapper scripts for common workflows:

### `run.sh` - Interactive research helper

```bash
# Quick search
./run.sh search "event sourcing patterns"

# Deep dive into project
./run.sh explore re-frame --query "subscription lifecycle"

# Compare projects
./run.sh compare "re-frame,electric" --aspect "reactive-state"
```

### `query-repo.sh` - Single project query

```bash
# Full repo query (small projects)
./query-repo.sh datascript "transaction semantics"

# Focused query (large projects)
./query-repo.sh clojurescript "cljs/compiler.clj,cljs/analyzer.cljc" "macro expansion"
```

## Environment Setup

**Required:**
```bash
# Verify .env exists with API keys
test -f .env && echo "✓" || echo "✗ Create .env"
env | grep -E "(GEMINI|OPENAI|GROK)_API_KEY"
```

**Optional:**
```bash
# Set default model
export RESEARCH_DEFAULT_MODEL="gemini-2.5-flash"  # Fast queries
export RESEARCH_DEFAULT_MODEL="gpt-5-codex"       # Deep analysis
```

## Tips & Best Practices

1. **Start with structure exploration**
   - Use `tree` and `tokei` to understand repo size
   - Identify key directories before extracting code

2. **Be specific with includes**
   - Use `--include "pattern"` to focus extraction
   - Reduces tokens, increases relevance

3. **Choose the right model**
   - Gemini for breadth, Codex for depth
   - Use `model_reasoning_effort="high"` for complex queries

4. **Use sessions for multi-turn**
   - Save/resume gemini sessions for context continuity
   - Codex `--resume` for last session

5. **Validate outputs**
   - Check file sizes (`wc -l`, `du -h`)
   - Ensure repomix didn't error (check stderr)

## Common Pitfalls

- **Empty output:** repomix failed silently - check error output
- **Token limits:** Query too large - use focused includes or zoom into subdirs
- **API timeouts:** Use faster model (gemini-flash) or retry
- **Stale data:** repos.edn might be outdated - regenerate with `docs/research/sources/update-repos.sh`

## Troubleshooting

**"No such file or directory"**
- Verify repo exists: `ls ~/Projects/best/{project}`
- Check repos.edn for correct path

**"Command timed out"**
- Use `--model gemini-2.5-flash` for faster response
- Break query into smaller parts

**"Empty response"**
- Check API keys: `env | grep API_KEY`
- Verify .env sourcing: `source .env`

## Examples

See `examples/` directory for:
- `example-quick-query.sh` - Fast single-project lookup
- `example-deep-dive.sh` - Multi-turn research session
- `example-comparison.sh` - Cross-project pattern analysis
- `example-architecture.sh` - Large repo architecture exploration

## Data Resources

- `data/repos.edn` - Curated project list with metadata
- `data/common-queries.edn` - Pre-built query templates
- `data/model-selection.edn` - Decision tree for model choice

## See Also

- Project docs: `CLAUDE.md#research-workflow`
- LLM CLIs: `CLAUDE.md#llm-provider-clis`
- Repomix docs: `~/Projects/best/repomix/README.md`
