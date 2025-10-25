# llmx-safe: Model Version Validation

Wrapper for `llmx` that enforces use of latest AI models (as of Oct 2025).

## Problem

Using outdated models wastes money and reduces quality. Token savings from progressive disclosure far exceed model costs, so **always use the latest models**.

## Solution

`llmx-safe` validates model names before calling `llmx`:
- ✅ Allows latest models (GPT-5, Claude Sonnet 4.5, Gemini 2.5 Pro, Grok 4)
- ❌ Errors on deprecated models (GPT-4, Claude 3.x, Gemini 1.5, Grok 2)
- ⚠️  Warns on unknown models (might be new or typo)

## Usage

```bash
# Instead of:
llmx --provider openai --model gpt-4o "prompt"  # ❌ ERRORS

# Use:
llmx-safe --provider openai --model gpt-5-pro "prompt"  # ✅ OK
```

## Installation

```bash
# Add to PATH or create alias
alias llmx='skills/llmx-validation/llmx-safe'

# Or add to .bashrc/.zshrc
export PATH="$PWD/skills/llmx-validation:$PATH"
```

## Latest Models (Oct 2025)

See `docs/LATEST-MODELS-2025.md` for full reference.

**Quick reference:**
- OpenAI: `gpt-5`, `gpt-5-pro`
- Anthropic: `claude-sonnet-4-5`, `claude-haiku-4-5`
- Google: `gemini-2.5-pro`
- xAI: `grok-4`, `grok-4-fast-reasoning`

## Maintenance

Update `LATEST_MODELS` and `DEPRECATED_MODELS` arrays in `llmx-safe` when new models are released.

Check quarterly:
- https://platform.openai.com/docs/models
- https://docs.anthropic.com/en/docs/about-claude/models/overview
- https://ai.google.dev/gemini-api/docs/models
- https://x.ai/api

## See Also

- `docs/LATEST-MODELS-2025.md` - Full model reference
- `CLAUDE.md` - Project instructions (llmx usage guidelines)
