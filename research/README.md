# Research Directory

Tools and workflows for extracting architectural patterns from external codebases.

## Quick Start

```bash
# 1. Copy and edit questions file
cp questions.edn.example questions.edn
# Edit questions.edn with your repos and questions

# 2. Run parallel research
../scripts/parallel-research.sh questions.edn

# 3. Check results
ls -lh results/
cat results/datascript.md
```

## Directory Structure

```
research/
├── README.md                 # This file
├── questions.edn.example     # Template questions file
├── questions.edn            # Your actual questions (gitignored)
└── results/                 # Query results (gitignored)
    ├── datascript.md
    ├── adapton-rust.md
    └── ...
```

## Workflow

### 1. Explore Repository Structure

For large repos, explore first to find relevant paths:

```bash
# Check size and structure
tree -L 3 -d ~/Projects/best/clojurescript
tokei ~/Projects/best/clojurescript

# Output example:
# ────────────────────────────────────────────────────
# Language            Files        Lines         Code
# ────────────────────────────────────────────────────
# Clojure               450       125000        95000
# ClojureScript         320        85000        65000
# ...

# Zoom into specific directories
tree -L 2 ~/Projects/best/clojurescript/src/main/clojure/cljs
```

### 2. Create Questions File

Use EDN format (better for Clojure tooling):

```edn
{:project-name
 {:path "~/Projects/best/project/src/relevant/subdir"
  :question "How does X implement Y?
Focus on:
1) Mechanism A
2) Mechanism B
3) Mechanism C
4) Mechanism D
Show code patterns."}}
```

**Guidelines**:
- Use `~` for home directory (script expands it)
- Zoom into subdirectories for large repos (saves context)
- Follow prompt template in `../templates/research-prompt.md`
- 4 focused questions per query (sweet spot)
- Request concrete deliverables ("show code", "show architecture")

### 3. Run Parallel Research

```bash
# Default: uses questions.edn
../scripts/parallel-research.sh

# Or specify file
../scripts/parallel-research.sh my-questions.edn

# Monitor progress
tail -f ../scripts/parallel-research.log  # If logging enabled
```

**Features**:
- Runs up to 4 queries in parallel
- Caches results in `~/.cache/evo-research/`
- Skips cached queries (saves API costs)
- Outputs to `results/{repo-name}.md`

### 4. Review Results

```bash
# List all results
ls -lh results/

# Read specific result
bat results/datascript.md  # or cat, less, etc

# Compare results
diff results/datascript.md results/datalevin.md
```

### 5. Synthesize Patterns

Manually review and extract patterns, or use synthesis tools:

```bash
# Manual: Read and take notes
# Automated: TBD - synthesis agent

# Create comparative analysis
cat results/*.md > combined.md
# Then extract common patterns
```

## Tips

### For Small Repos (<10MB)

Include full `src/**`:

```edn
{:integrant
 {:path "~/Projects/best/integrant"
  :question "..."}}
```

### For Medium Repos (10-50MB)

Include core directories:

```edn
{:datascript
 {:path "~/Projects/best/datascript/src"
  :question "..."}}
```

### For Large Repos (>50MB)

Target specific subdirectories or files:

```edn
{:clojurescript-compiler
 {:path "~/Projects/best/clojurescript/src/main/clojure/cljs"
  :question "Focus on compiler.clj and analyzer.cljc..."}}
```

### Multiple Queries Per Repo

Break complex analyses into focused queries:

```edn
{:clojurescript-parser
 {:path "~/Projects/best/clojurescript/src/main/clojure/cljs"
  :question "How does parsing work? ..."}

 :clojurescript-validation
 {:path "~/Projects/best/clojurescript/src/main/clojure/cljs"
  :question "How does validation work? ..."}

 :clojurescript-emit
 {:path "~/Projects/best/clojurescript/src/main/clojure/cljs"
  :question "How does code emission work? ..."}}
```

Each query gets 1/N of context budget, goes deeper.

## Prompt Engineering

Use the template in `../templates/research-prompt.md`:

**Structure**:
```
How does {PROJECT} implement {FEATURE}?
Focus on: 1) {MECH} 2) {MECH} 3) {MECH} 4) {MECH}
{DELIVERABLE}
```

**Good Example**:
```
How does Adapton handle incremental computation?
Focus on:
1) Dependency tracking mechanism
2) Invalidation strategy
3) Memoization patterns
4) Cycle handling
Be specific about architecture and show code patterns.
```

**Bad Example**:
```
How does Adapton work?
```

## Advanced: LLM Provider Usage

### Multi-Turn Conversations

For deeper analysis, use continuation:

```bash
# Initial query
codex --model gpt-5 --reasoning-effort high -p \
  "$(repomix ~/Projects/best/datascript/src --copy && pbpaste)"

# Follow-up
codex --continue -p "Now explain the transaction model"

# Another follow-up
codex --continue -p "What are the performance tradeoffs?"
```

### Provider Diversity

Query same repo with different providers:

```bash
# Gemini (free, fast)
repomix ~/Projects/best/X --copy && \
  pbpaste | gemini -y -p "question"

# Codex (high reasoning)
repomix ~/Projects/best/X --copy && \
  pbpaste | codex --model gpt-5 --reasoning-effort high -p "question"

# OpenCode (alternative perspective)
repomix ~/Projects/best/X --copy && \
  pbpaste | opencode -p "question"
```

Compare responses for diverse insights.

## Cache Management

Cache location: `~/.cache/evo-research/`

```bash
# View cache
ls -lh ~/.cache/evo-research/

# Clear cache (force re-query)
rm -rf ~/.cache/evo-research/

# Clear specific query
rm ~/.cache/evo-research/<hash>.md
```

Cache keys are MD5 hashes of `repo:path:question`.

## Troubleshooting

### "Repository not found"

Check path with tilde expansion:
```bash
ls ~/Projects/best/projectname
```

### "Token limit exceeded"

Zoom into smaller directory:
```bash
# Before: ~/Projects/best/clojurescript
# After:  ~/Projects/best/clojurescript/src/main/clojure/cljs
```

### "Repomix failed"

Check if repomix is installed:
```bash
which repomix
npm list -g repomix
```

### "Gemini/Codex failed"

Check CLI availability:
```bash
which gemini codex opencode
```

## See Also

- `../templates/research-prompt.md` - Prompt engineering guide
- `../scripts/parallel-research.sh` - Parallel query script
- `../CLAUDE.md` - Research workflow documentation
