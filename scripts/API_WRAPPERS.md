# API Wrappers

## Active Wrappers

### Global Tools (installed via npm/bun)
- **gemini** - Google Gemini API client (npm: @google/genai)
- **codex** - OpenAI GPT/Codex client (npm: openai-codex or similar)

### Local Scripts
- **scripts/grok** - X.AI Grok API wrapper (bash/curl)

## Usage

### From Command Line
```bash
# Gemini (global)
gemini -y -p "your prompt"
gemini --model gemini-2.0-flash-exp -p "prompt"

# Codex (global)
codex -m gpt-5-codex -c model_reasoning_effort="high" "prompt"
echo "prompt" | codex exec --full-auto -

# Grok (local)
scripts/grok -p "your prompt"
scripts/grok -m grok-4-latest -p "prompt"
echo "prompt" | scripts/grok
```

### From Python (MCP servers)
See `mcp/review/providers.py` for async subprocess wrappers.

### From Clojure (research scripts)
See `dev/scripts/research/*.clj` for shell-out patterns.

## Archived

- `scripts/.archived-gemini-api` - Duplicate bash wrapper (unused)
- `scripts/.archived-openai` - Duplicate bash wrapper (unused)

These were created before adopting the global npm tools.
