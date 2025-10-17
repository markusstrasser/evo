# Research Workflow Skill

Query high-quality Clojure/ClojureScript reference projects to find patterns, idioms, and architectural examples.

## Quick Start

```bash
# List available projects
./run.sh list

# Get project info
./run.sh info malli

# Quick query
./run.sh explore malli "How does schema composition work?"

# Focused query on large repo
./run.sh explore clojurescript "macro expansion" \\
    --focused "cljs/compiler.clj,cljs/analyzer.cljc"

# Compare projects
./run.sh compare "re-frame,electric" "reactive state"
```

## Files

- **SKILL.md** - Full documentation (L1/L2)
- **run.sh** - Main orchestration script
- **config.edn** - Configuration and query templates
- **data/repos.edn** - Project metadata
- **examples/** - Usage examples

## Requirements

- `repomix` - Repository bundling
- `gemini` / `codex` / `grok` - LLM provider CLIs
- API keys in `.env` file

## Usage Patterns

### Quick Query (Small Repos)
For projects <10MB like `malli`, `aero`, `environ`:
```bash
./run.sh explore malli "your question"
```

### Focused Query (Large Repos)
For projects >50MB like `clojurescript`, `athens`:
```bash
./run.sh explore clojurescript "your question" \\
    --focused "path/to/specific/files.clj"
```

### Comparison
Compare patterns across multiple projects:
```bash
./run.sh compare "project1,project2,project3" "aspect to compare"
```

## Model Selection

- **Gemini** - High token count, fast, cheap
- **Codex** - Deep analysis, architecture, taste
- **Grok** - Quick queries, fallback

Default is `gemini`. Override with `--model codex`.

## Examples

See `examples/` directory:
- `example-quick-query.sh`
- `example-comparison.sh`

## See Also

- Full docs: `SKILL.md`
- Project docs: `../../CLAUDE.md#research-workflow`
