# Latest AI Models - October 2025

**Last Updated:** 2025-10-24 23:45 UTC

**Status:** ✅ VERIFIED WITH ACTUAL API CALLS

---

## CRITICAL: Use These Verified Models

All models below have been **TESTED AND VERIFIED** via actual API calls on 2025-10-24.

## Verified Working Models

### Anthropic (Claude 4.5)

| Model | API Name | Status | Notes |
|-------|----------|--------|-------|
| Claude Sonnet 4.5 (dated) | `claude-sonnet-4-5-20250929` | ✅ | Pinned version (Sep 2025) |
| Claude Sonnet 4.5 (alias) | `claude-sonnet-4-5` | ✅ | **DEFAULT** - Auto-updates |
| Claude Haiku 4.5 | `claude-haiku-4-5` | ✅ | Faster, cheaper variant |

**llmx Usage:**
```bash
llmx --provider anthropic "prompt"  # Uses claude-sonnet-4-5-20250929
llmx --provider anthropic --model claude-haiku-4-5 "prompt"
```

---

### xAI (Grok 4)

| Model | API Name | Status | Notes |
|-------|----------|--------|-------|
| Grok 4 (dated) | `grok-4-0709` | ✅ | Pinned version (Jul 2025) |
| Grok 4 (alias) | `grok-4-latest` | ✅ | **DEFAULT** - Auto-updates |
| Grok 4 Fast Reasoning | `grok-4-fast-reasoning` | ✅ | Reasoning mode enabled |

**llmx Usage:**
```bash
llmx --provider xai "prompt"  # Uses grok-4-latest
llmx --provider xai --model grok-4-fast-reasoning "prompt"
```

---

### OpenAI (GPT-5)

| Model | API Name | Status | Notes |
|-------|----------|--------|-------|
| GPT-5 Pro | `gpt-5-pro` | ✅⚠️ | **DEFAULT** - Requires temp=1 |
| GPT-5 | `gpt-5` | ✅⚠️ | Requires temp=1 |
| GPT-5 Mini | `gpt-5-mini` | ✅⚠️ | Requires temp=1 |

⚠️ **IMPORTANT:** GPT-5 models require `--temperature 1` (no other temperature supported)

**llmx Usage:**
```bash
llmx --provider openai --temperature 1 "prompt"  # Uses gpt-5-pro
llmx --provider openai --model gpt-5-mini --temperature 1 "prompt"
```

---

### Google (Gemini 2.5)

| Model | API Name | Status | Notes |
|-------|----------|--------|-------|
| Gemini 2.5 Pro | `gemini/gemini-2.5-pro` | ✅ | **DEFAULT** - Most powerful |
| Gemini 2.5 Flash | `gemini/gemini-2.5-flash` | ✅ | Faster variant |

**llmx Usage:**
```bash
llmx --provider google "prompt"  # Uses gemini-2.5-pro (no need to specify model)
llmx "prompt"  # Default provider is google, uses gemini-2.5-pro
llmx --provider google --model gemini/gemini-2.5-flash "prompt"
```

**Alternative (vendor CLI):**
```bash
gemini "prompt"  # Google's official CLI
```

---

## Quick Reference

### Default Models (as of 2025-10-24)

When you use `llmx --provider PROVIDER "prompt"` without specifying a model:

- **Anthropic:** `claude-sonnet-4-5-20250929` (Claude 4.5)
- **xAI:** `grok-4-latest` (Grok 4)
- **OpenAI:** `gpt-5-pro` (GPT-5 Pro, requires temp=1)
- **Google:** `gemini/gemini-2.5-pro` (Gemini 2.5 Pro)

### Default Provider

When you use `llmx "prompt"` without specifying a provider:
- **Default:** Google (Gemini 2.5 Pro)

---

## Previous Docs Were WRONG

The previous version of this document incorrectly claimed these models **don't exist**.

**They DO exist and are working:**

- ✅ `claude-sonnet-4-5`, `claude-sonnet-4-5-20250929` - **WORKS**
- ✅ `claude-haiku-4-5` - **WORKS**
- ✅ `grok-4-0709`, `grok-4-latest`, `grok-4-fast-reasoning` - **WORKS**
- ✅ `gpt-5`, `gpt-5-pro`, `gpt-5-mini` - **WORKS** (requires temp=1)
- ✅ `gemini/gemini-2.5-pro`, `gemini/gemini-2.5-flash` - **WORKS**

**What was actually wrong:** Documentation, not the models.

---

## Environment Setup

### Automatic .env Loading (NEW!)

As of 2025-10-24, `llmx` **automatically loads** `.env` files from:

1. Current directory (`./`)
2. Parent directory (`../`)
3. Grandparent directory (`../../`)

**No need to manually source .env anymore!** Just run `llmx` and it works.

### .env File Format

`llmx` supports both standard and shell-style .env formats:

```bash
# Standard format (python-dotenv)
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-...

# Shell format (with export)
export GEMINI_API_KEY=AIza...
export XAI_API_KEY=xai-...
```

---

## Model Release Timeline

- **Sep 2025:** Claude Sonnet 4.5 (`claude-sonnet-4-5-20250929`)
- **Oct 2025:** Claude Haiku 4.5 (`claude-haiku-4-5`)
- **Jul 2025:** Grok 4 (`grok-4-0709`)
- **Sep 2025:** Grok 4 Fast (`grok-4-fast-reasoning`)
- **Oct 2025:** GPT-5 family (`gpt-5-pro`, `gpt-5`, `gpt-5-mini`)
- **Mar 2025:** Gemini 2.5 Pro (`gemini-2.5-pro`)

---

## Verification Method

All models verified using:

```bash
# Test each model
llmx --provider PROVIDER --model MODEL_NAME "say hi"

# Success: Returns response
# Failure: Returns error with model name not found or authentication error
```

**Verification Date:** 2025-10-24 23:45 UTC
**Verification Method:** Direct API testing via llmx CLI
**All tests:** Passing ✅

---

## Common Errors & Fixes

### Error: "API key not found"
**Cause:** .env file not loading or API key not set
**Fix:** Reinstall llmx with `cd llmx && uv tool install --reinstall --editable .`

### Error: "temperature not supported" (GPT-5 only)
**Cause:** GPT-5 models only support temperature=1
**Fix:** Add `--temperature 1` flag

### Error: "LLM Provider NOT provided" (Gemini only)
**Cause:** Missing `gemini/` prefix
**Fix:** Use full model name `gemini/gemini-2.5-pro` or omit model (uses default)

---

## Architecture Review Stack

For architecture reviews, use the most powerful models from each provider:

```bash
# Generate proposals from latest models
cat context.md | llmx --provider anthropic --model claude-sonnet-4-5 "" > proposal-claude-4.5.md
cat context.md | llmx --provider xai --model grok-4-latest "" > proposal-grok-4.md
cat context.md | llmx --provider google --model gemini/gemini-2.5-pro "" > proposal-gemini-2.5-pro.md
cat context.md | llmx --provider openai --model gpt-5-pro --temperature 1 "" > proposal-gpt-5-pro.md
```

---

## Updates Since Last Doc

1. ✅ **All model names corrected** - Claude 4.5, Grok 4, GPT-5, Gemini 2.5 Pro verified
2. ✅ **Auto .env loading** - No more manual `source scripts/load-env.sh`
3. ✅ **Shell-style .env support** - Handles `export VAR=value` format
4. ✅ **Latest models as defaults** - Most powerful variants for all providers
5. ✅ **GPT-5 temperature requirement** - Documented temp=1 requirement

---

---

## Tournament MCP Judge Names

The `tournament` MCP uses simplified judge names that map to actual API models:

| Judge Name | Maps To | Provider |
|------------|---------|----------|
| `gpt5-pro` | `openai:gpt-5-pro` | OpenAI GPT-5 Pro |
| `gemini25-pro` | `google-gla:gemini-2.5-pro` | Google Gemini 2.5 Pro |
| `grok-4` | `xai:grok-4-latest` | xAI Grok 4 |
| `claude-4.5` | `anthropic:claude-sonnet-4-5-20250929` | Anthropic Claude Sonnet 4.5 |

**Note:** The judge name `gemini25-pro` (no dots) is correct for tournament MCP. It uses its own naming scheme but maps to the correct API model `gemini-2.5-pro`.

**FIXED (2025-10-24):**
- ✅ `grok-4` now uses actual xAI Grok-4 (was incorrectly using Groq/Llama!)
- ✅ `gpt5-codex` renamed to `gpt5-pro` (actual model name)

---

**Last Verification:** 2025-10-24 23:45 UTC
**Next Review:** When new models release or API changes
