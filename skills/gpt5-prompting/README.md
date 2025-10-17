# GPT-5 Prompting Skill - Quick Reference

One-page cheat sheet for GPT-5 prompting best practices.

## Quick Decisions

| Task Type | Reasoning Effort | Verbosity | Integration |
|-----------|-----------------|-----------|-------------|
| Architecture/Design | `high` | `medium` | API (PydanticAI) |
| Code Review | `high` | `medium` | API (PydanticAI) |
| Standard Coding | `medium` | `low` (status)<br>`high` (code) | API |
| Data Extraction | `minimal` | `low` | API |
| Interactive Exploration | `high` | `medium` | CLI (codex) |

## API Integration (Preferred)

### PydanticAI (Best)
```python
from pydantic_ai import Agent

agent = Agent(
    "openai:gpt-5-codex",
    output_type=YourSchema,
    system_prompt="..."
)
result = await agent.run(query, model_settings={"reasoning_effort": "high"})
```

### OpenAI SDK
```python
from openai import OpenAI

client = OpenAI()
response = client.responses.create(
    model="gpt-5-codex",
    input=[...],
    reasoning={"effort": "high"},
    text={"verbosity": "medium"}
)
```

## CLI (Fallback Only)

```bash
# Interactive
codex -m gpt-5-codex -c model_reasoning_effort="high" "question"

# Batch
echo "prompt" | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -
```

## Core Rules

1. **API-first** - Use CLI only for interactive exploration
2. **High reasoning for taste** - Architecture, review, style
3. **No contradictions** - GPT-5 wastes tokens reconciling conflicts
4. **Clear hierarchy** - MUST vs SHOULD
5. **Verbosity control** - Low for status, high for code

## Common Pitfalls

❌ Using `minimal` for architecture
❌ Contradictory instructions
❌ CLI in production
❌ Missing MUST vs SHOULD hierarchy
❌ Default verbosity everywhere

✅ Use `high` for taste/architecture
✅ Remove contradictions
✅ API for automation
✅ Separate requirements from preferences
✅ Context-specific verbosity

## Files

- `SKILL.md` - Full documentation
- `examples/api-integration.py` - API patterns
- `examples/cli-fallback.sh` - CLI patterns (when needed)
- `data/reasoning-effort-guide.edn` - Decision trees
- `data/common-pitfalls.edn` - Error catalog

## See Also

- `skills/architect/` - Uses GPT-5 for proposals
- `skills/research/` - Model selection across providers
- `CLAUDE.md#llm-provider-clis` - CLI reference
