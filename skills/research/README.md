# Research Workflow Skill

Query 40+ Clojure/ClojureScript reference projects to find patterns, idioms, and architectural examples.

## Full Documentation

See **[SKILL.md](SKILL.md)** for complete documentation including:
- Prerequisites and setup
- Model selection guide
- Query patterns for small/large repos
- Common query templates
- Tips, pitfalls, and troubleshooting

## Quick Start

```bash
# Query a small repo
repomix ~/Projects/best/malli --copy --output /dev/null | \
  llmx --provider google --model gemini-2.5-pro "How does schema composition work?"

# Query large repo (focused)
repomix ~/Projects/best/clojurescript/src/main/clojure/cljs \
  --include "compiler.clj,analyzer.cljc" --copy --output /dev/null | \
  llmx --provider openai --model gpt-5-codex "Explain macro expansion"
```

## Requirements

- `llmx` - Unified LLM CLI
- `repomix` - Repository bundling
- API keys: `GEMINI_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY`

## CLI Helper (Optional)

The `run.sh` script provides convenience commands:

```bash
./run.sh list              # List available projects
./run.sh info malli        # Show project details
./run.sh explore malli "question"  # Query with defaults
```

See [SKILL.md](SKILL.md) for detailed usage.
