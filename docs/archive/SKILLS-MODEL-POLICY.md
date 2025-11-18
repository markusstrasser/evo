# Skills Model Policy

**CRITICAL: Skills MUST use high-quality models**

## The Rule

**NEVER use cheap/fast models for Skills work:**
- ❌ `gpt-4o`
- ❌ `gpt-4-turbo`
- ❌ `gemini-flash`
- ❌ `gemini-1.5-*`

**ALWAYS use top-tier models:**
- ✅ `gpt-5-codex` with `model_reasoning_effort="high"`
- ✅ `gemini-2.5-pro`
- ✅ `grok-4-latest`

## Rationale

1. **Skills are for important work**
   - Architectural decisions
   - Research and analysis
   - Code review and refactoring
   - Debugging complex issues

2. **Token savings >> model costs**
   - Progressive disclosure saves ~480 tokens per session
   - Skills L1/L2/L3 loading means context efficiency
   - Cheap models defeat the purpose of high-quality workflows

3. **Quality over speed**
   - Solo developer needs accurate analysis
   - Bad architectural decisions are expensive
   - Fast/cheap models often miss nuance

## Cost Justification

```
Scenario: Architectural decision session

WITHOUT Skills (current):
- Load full CLAUDE.md: ~15k tokens
- Use gpt-4-turbo: Cheaper per token
- Total cost: Medium

WITH Skills + cheap models:
- Load L1 only: ~100 tokens
- Use gemini-flash: Very cheap
- Result: Fast but often wrong
- Cost of bad decision: HIGH

WITH Skills + quality models:
- Load L1: ~100 tokens
- Load L2 when triggered: ~3k tokens
- Use gpt-5-codex (high reasoning): Expensive per token
- Result: Correct, thorough analysis
- Total tokens: 3.1k vs 15k (80% reduction)
- Actual cost: LOWER than old approach despite better model
```

## Configuration Standard

Every skill `config.edn` MUST include:

```clojure
:model-policy
{:rationale "Skills require maximum reasoning capability"
 :never-use ["gpt-4o" "gpt-4-turbo" "gemini-flash" "gemini-1.5-*"]
 :always-use ["gpt-5-codex" "gemini-2.5-pro" "grok-4-latest"]
 :reasoning-effort "high"  ; Non-negotiable
 :cost-justification "Token savings from progressive disclosure >> model costs"}
```

## Skill-Specific Guidance

### Research Skill
- **Primary**: `gemini-2.5-pro` (handles large context well)
- **Code review**: `gpt-5-codex` with high reasoning
- **Quick queries**: `grok-4-latest`

### Architect Skill
- **Proposals**: All three providers at once (gpt-5, gemini-2.5-pro, grok-4)
- **Ranking**: Via tournament-mcp (uses same models)
- **Never**: Use 4o or flash for architectural decisions

### Visual Validation Skill
- **Analysis**: Can use local Python scripts (no LLM needed)
- **Comparison**: If using LLM, use gpt-5-codex

### REPL Debug Skill
- **Type**: Guidance-only (no LLM calls)
- **Model**: N/A

### Diagnostics Skill
- **Type**: Shell scripts only (no LLM calls)
- **Model**: N/A

## Common Mistakes to Avoid

❌ **"But flash is faster for quick queries"**
- Skills aren't for quick queries - use CLI directly
- Skills are for important, complex work

❌ **"4o is good enough for most things"**
- Not for architecture, research, or refactoring
- These are the exact use cases where quality matters most

❌ **"The cost difference is too high"**
- Token savings from progressive disclosure are massive
- Total cost is LOWER with Skills + quality models
- One bad architectural decision costs more than better models

## Enforcement

1. **Config validation**: Pre-commit hook checks `config.edn` for proper models
2. **Runtime checks**: Skills should validate model at invocation
3. **Documentation**: SKILL.md should specify required models
4. **Code review**: Check that CLIs are called with correct model flags

## Example Invocations

### Research Skill
```bash
# ❌ WRONG
gemini --model gemini-2.5-flash -y -p "..."

# ✅ CORRECT
gemini --model gemini-2.5-pro -y -p "..."

# ✅ CORRECT
codex -m gpt-5-codex -c model_reasoning_effort="high" "..."
```

### Architect Skill
```bash
# ❌ WRONG
echo "$prompt" | codex exec -m gpt-4-turbo --full-auto -

# ✅ CORRECT
echo "$prompt" | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -
```

## Summary

Skills are progressive-disclosure workflows that save massive tokens. Use that token budget to afford the best models. **Quality always over speed for Skills work.**
