# LLM CLI Tools Reference

Battle-tested syntax and gotchas for gemini, codex, grok CLIs.

## Quick Reference

| Tool   | Model Flag | Reasoning | Interactive | Batch Mode | Session |
|--------|-----------|-----------|-------------|------------|---------|
| Gemini | `-m` | N/A | default | `-y -p` | manual save/resume |
| Codex  | `-m` | `-c model_reasoning_effort="high"` | default | `-p` | `--continue`, `--resume` |
| Grok*  | `-m` | N/A | N/A | `-p` or pipe | N/A |

*Grok is a curl wrapper script at `scripts/grok`, not an official CLI

---

## Gemini CLI

### Basic Usage
```bash
# Interactive mode
gemini

# Batch mode (auto-confirm)
gemini -y -p "your question here"

# Pipe input
echo "question" | gemini -y

# From file
gemini -y < prompt.txt
```

### MCP Servers
```bash
# With MCP servers (context-prompt, content-prompt available)
gemini --allowed-mcp-server-names context-prompt content-prompt -y -p "question"

# Disable MCP for batch queries (prevents timeouts)
gemini --allowed-mcp-server-names "" -y -p "question"
```

### Session Management
```bash
# Interactive session with manual save
gemini
> /chat save my-research-session
> ... ask questions ...
> /chat list
> /chat resume my-research-session

# Track session in file
gemini --session-summary session.txt
```

### Gotchas
- **Timeout issue**: Use `--allowed-mcp-server-names ""` for batch queries to avoid MCP timeouts
- **Clean output**: No reasoning preamble, response starts immediately
- **Duplicate risk**: Can produce identical outputs for same prompt - add variation!

---

## Codex CLI

### Basic Usage
```bash
# Interactive mode
codex "your question"

# Non-interactive batch mode (use codex exec)
echo "your question" | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -

# From file
codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - < prompt.txt
```

### Key Syntax Points
- **Use `codex exec` for batch mode** (not just `codex`)
- **Use `-` as prompt argument** to read from stdin (NOT `-p -`)
- **Use `--full-auto`** for automatic execution without prompts
- **`-c model_reasoning_effort="high"`** for best quality

### Session Management
```bash
# Interactive: resume last session
codex --resume

# Non-interactive: resume by id
codex exec resume <session-id>
codex exec resume --last

# Sessions stored at: ~/.codex/sessions/*.jsonl
```

### Models
- `gpt-5-codex` - Best for code analysis with reasoning
- `gpt-4-turbo` - Fallback option
- `o3` - Latest model (use `-m o3`)

### Gotchas
- **Use `codex exec` for pipes**: Regular `codex` command requires TTY (interactive terminal)
- **`-p` is NOT for prompt**: It's `--profile` for config profiles
- **Output includes reasoning**: First ~1430 lines = prompt echo + thinking
  - **Solution**: Use `tail -n +1430 output.md` or Read tool with `offset: 1430`
- **Token usage**: Reported at end of response
- **`--full-auto` safety**: Uses workspace-write sandbox (safer than --dangerously-bypass)

---

## Grok (curl wrapper)

**Note**: This is a custom curl wrapper at `scripts/grok`, not the official CLI.

### Basic Usage
```bash
# Batch mode
scripts/grok -p "question"

# With model selection
scripts/grok -m grok-4-latest -p "question"

# Pipe input
echo "question" | scripts/grok

# From file
scripts/grok < prompt.txt > output.md
```

### Models
- `grok-4` - Default stable version
- `grok-4-latest` - Latest general model
- `grok-code-fast-1` - Fast code-focused model

### Environment
```bash
# Required
export XAI_API_KEY="your-xai-api-key"

# Optional
export GROK_MODEL="grok-4-latest"  # Override default
```

### Gotchas
- **API key required**: Must set `XAI_API_KEY` environment variable
- **No streaming**: Uses `"stream": false` for complete responses
- **Output format**: Clean JSON response, no reasoning preamble
- **Requires jq**: For JSON parsing (should be installed)

---

## Parallel Query Patterns

### Problem: Duplicate Responses
Running the same prompt multiple times can produce identical outputs, especially with Gemini.

### Solution: Add Variation

**Option 1: UUID prefix**
```bash
for i in 1 2 3; do
  (echo "Query-ID: $(uuidgen)" && cat prompt.txt) | \
    gemini -y > results/gemini-$i.md &
done
wait
```

**Option 2: Perspective variation**
```bash
perspectives=("structural analysis" "performance patterns" "comparative design")
for i in {0..2}; do
  (echo "Analyze from perspective: ${perspectives[$i]}" && cat prompt.txt) | \
    gemini -y > results/gemini-$((i+1)).md &
done
wait
```

**Option 3: Different questions**
```bash
# BEST: Ask genuinely different questions
gemini -y -p "How is the trie data structure implemented?" &
gemini -y -p "What are the performance optimizations?" &
gemini -y -p "How does conflict resolution work?" &
wait
```

### Duplicate Detection
```bash
# Check for identical responses
md5sum results/*.md | awk '{print $1}' | sort | uniq -d

# Show duplicates
md5sum results/*.md | sort | uniq -w32 -D
```

---

## Multi-Model Battle Testing

### Template: 3 models × 3 questions = 9 responses
```bash
#!/bin/bash
# research/battle-test.sh

PROMPT_BASE="prompt.txt"
RESULTS="research/results"
mkdir -p "$RESULTS"

# Define perspectives
PERSPECTIVES=(
  "Focus on data structures and algorithms"
  "Focus on performance optimizations"
  "Focus on architectural patterns and composition"
)

# Query each model with each perspective
for model in gemini codex grok; do
  for i in {0..2}; do
    perspective="${PERSPECTIVES[$i]}"
    output="$RESULTS/${model}-$((i+1)).md"

    case $model in
      gemini)
        (echo "$perspective" && cat "$PROMPT_BASE") | \
          gemini --allowed-mcp-server-names "" -y > "$output" &
        ;;
      codex)
        (echo "$perspective" && cat "$PROMPT_BASE") | \
          codex -m gpt-5-codex -c model_reasoning_effort="high" -p - > "$output" &
        ;;
      grok)
        (echo "$perspective" && cat "$PROMPT_BASE") | \
          scripts/grok -m grok-4-latest -p - > "$output" &
        ;;
    esac
  done
done

wait
echo "✓ All queries complete. Results in $RESULTS/"

# Check for duplicates
echo "Checking for duplicates..."
md5sum "$RESULTS"/*.md | sort | uniq -w32 -D
```

---

## Output Parsing

### Gemini
```bash
# Clean output, starts immediately
cat results/gemini-1.md  # Full response from line 1
```

### Codex
```bash
# Skip prompt echo and reasoning (lines 1-1430)
tail -n +1430 results/codex-1.md > results/codex-1-clean.md

# Or use offset in Read tool
# Read(file_path="results/codex-1.md", offset=1430)
```

### Grok
```bash
# TBD - format not yet tested
cat results/grok-1.md
```

---

## Token Counting

### Quick Estimation
```bash
# Words × 1.3 ≈ tokens
wc -w prompt.txt | awk '{print int($1 * 1.3) " tokens (estimated)"}'
```

### Accurate Counting (if tiktoken installed)
```bash
python3 -c "import tiktoken; enc = tiktoken.encoding_for_model('gpt-4'); \
  print(len(enc.encode(open('prompt.txt').read())), 'tokens')"
```

### Token Limits
- GPT-4: 128k context
- GPT-5: 200k context
- Gemini: 1M context (but aim for <100k for quality)
- Grok-4: 131k context

---

## Best Practices

1. **Always use reasoning effort high** for Codex: `-c model_reasoning_effort="high"`
2. **Disable MCP for batch Gemini queries**: `--allowed-mcp-server-names ""`
3. **Add variation to parallel queries**: UUID, perspective, or different questions
4. **Use offset reads for Codex**: Skip first 1430 lines of reasoning
5. **Check for duplicates**: `md5sum results/*.md | sort | uniq -w32 -D`
6. **Target token budgets**: <80k for GPT-5, <100k for Gemini
7. **One prompt per model**: Don't run same prompt multiple times on same model

---

## Troubleshooting

### Gemini times out
```bash
# Use: --allowed-mcp-server-names ""
gemini --allowed-mcp-server-names "" -y -p "question"
```

### Codex output is huge
```bash
# First ~1430 lines are reasoning, skip them
tail -n +1430 codex-output.md
```

### Getting duplicate responses
```bash
# Add variation to prompts (see Parallel Query Patterns above)
echo "Analysis-ID: $(uuidgen)" | cat - prompt.txt | gemini -y
```

### Out of tokens
```bash
# Use repomix with --include filter for large repos
repomix ~/Projects/best/large-repo --include "src/core/**" --copy --output /dev/null
```

---

## Example: Full Research Pipeline

```bash
#!/bin/bash
# Complete research workflow with 3 models × 3 perspectives

# 1. Prepare repo snippet
repomix ~/Projects/best/reitit \
  --include "src/reitit/core.cljc,src/reitit/trie.cljc" \
  --copy --output /dev/null > /dev/null 2>&1

# 2. Build prompt with XML structure
cat > /tmp/research-prompt.txt <<'EOF'
<instructions>
How does Reitit implement its routing engine?
Focus on: 1) trie data structure 2) matching algorithm 3) middleware 4) conflicts
</instructions>

<context_overview>
$(cat docs/latest-overview.md)
</context_overview>

<target_repository>
$(pbpaste)
</target_repository>
EOF

# 3. Run parallel queries with perspectives
mkdir -p research/results
perspectives=(
  "Focus on data structures and compilation"
  "Focus on performance and optimization"
  "Focus on architectural patterns"
)

for i in {0..2}; do
  p="${perspectives[$i]}"

  # Gemini
  (echo "Perspective: $p" && cat /tmp/research-prompt.txt) | \
    gemini --allowed-mcp-server-names "" -y > research/results/gemini-$((i+1)).md &

  # Codex (skip reasoning in output)
  (echo "Perspective: $p" && cat /tmp/research-prompt.txt) | \
    codex -m gpt-5-codex -c model_reasoning_effort="high" -p - > \
    research/results/codex-$((i+1)).md &

  # Grok
  (echo "Perspective: $p" && cat /tmp/research-prompt.txt) | \
    scripts/grok -m grok-4-latest -p - > research/results/grok-$((i+1)).md &
done

wait
echo "✓ All 9 queries complete"

# 4. Check for duplicates
echo "Checking for duplicates..."
md5sum research/results/*.md | sort | uniq -w32 -D

# 5. Analyze and rank
# (Manual step: read all responses and create ranking.md)
```

---

## Version History

- **2025-09-30**: Initial version (gemini, codex tested; grok added)
- Tested with: gemini CLI 1.x, codex CLI 2.x, grok CLI 1.x
