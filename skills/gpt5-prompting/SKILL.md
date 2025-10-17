---
name: GPT-5 Prompting
description: Best practices for prompting GPT-5 effectively via API - reasoning effort tuning, verbosity control, instruction hierarchy, avoiding contradictions, and agentic eagerness control. Use when integrating GPT-5 into agents, skills, or workflows requiring high-quality code review, architecture decisions, or taste judgments.
---

# GPT-5 Prompting Best Practices

How to get the most out of GPT-5 (gpt-5-codex, gpt-5, gpt-5-mini) via API or CLI.

## Core Principles

1. **API-first** - Use direct API calls when possible
2. **CLI only when needed** - Codex wrapper for interactive exploration or legacy scripts
3. **High reasoning for taste** - Architecture, code review, style require `reasoning_effort="high"`
4. **Avoid contradictions** - GPT-5 wastes reasoning tokens reconciling conflicting instructions
5. **Clear hierarchy** - Hard requirements vs soft preferences

## API Integration Patterns

### Using PydanticAI (Recommended)

```python
from pydantic_ai import Agent
from pydantic import BaseModel

class CodeReview(BaseModel):
    clarity: float  # 0-10
    maintainability: float
    verdict: str

agent = Agent(
    "openai:gpt-5-codex",
    output_type=CodeReview,
    system_prompt="You are a code reviewer focused on simplicity and debuggability."
)

result = await agent.run(
    "Review this implementation...",
    model_settings={
        "reasoning_effort": "high",  # Critical for taste/architecture
        "verbosity": "medium"
    }
)
```

### Using OpenAI SDK Directly

```python
from openai import OpenAI

client = OpenAI()

response = client.responses.create(
    model="gpt-5-codex",
    input=[
        {"role": "developer", "content": system_prompt},
        {"role": "user", "content": user_query}
    ],
    reasoning={
        "effort": "high"  # minimal | medium | high
    },
    text={
        "verbosity": "medium"  # low | medium | high
    }
)
```

## Reasoning Effort Guide

| Effort | Use When | Example Tasks | Cost/Latency |
|--------|----------|---------------|--------------|
| `minimal` | Speed-critical, deterministic | Extract JSON, format code, simple classification | Fastest, cheapest |
| `medium` | Balanced (default) | Standard coding, refactoring, bug fixes | Moderate |
| `high` | Complex, taste-driven | Architecture decisions, code review, style judgment, multi-step planning | Slowest, most expensive |

**Key Decision:**
- **Architecture/taste/code review** → Always use `high`
- **Data extraction/formatting** → Use `minimal`
- **Everything else** → Start with `medium`

## Verbosity Control

### API Parameter (Global)

```python
text={"verbosity": "low"}     # Terse, minimal prose (good for status updates)
text={"verbosity": "medium"}  # Balanced (default)
text={"verbosity": "high"}    # Verbose, detailed (good for code generation)
```

### Prompt Override (Context-Specific)

**Pattern from Cursor:** Set low globally, override for specific contexts

```python
# API: low verbosity
text={"verbosity": "low"}

# Prompt: high verbosity for code only
system_prompt = """
Use high verbosity for writing code and code tools.
Write code for clarity first. Prefer readable, maintainable solutions.
"""
```

**Result:** Terse status updates, verbose code diffs

## Instruction Design

### 1. Avoid Contradictions

GPT-5 wastes reasoning tokens trying to reconcile contradictions.

❌ **Bad:**
```
Prefer standard library, but use external packages if simpler
Single-pass streaming, but reread or cache if clearer
Exact results, but approximate methods are fine
```

✅ **Good:**
```
# Hard requirements
- Use only Python stdlib
- Single-pass streaming only
- Exact Top-K (no approximations)
```

### 2. Clear Instruction Hierarchy

```markdown
# MUST follow (hard requirements)
- Use only stdlib
- No file I/O
- Exact semantics only

# SHOULD follow (soft preferences)
- Prefer readable names over single letters
- Minimize comments unless critical
```

### 3. Explicit Stop Conditions

```markdown
# Agentic persistence
- Keep going until user's query is COMPLETELY resolved
- Only terminate when problem is solved
- Never stop at uncertainty - deduce and continue

# Stop conditions
- All sub-requests completed
- All tests passing
- User explicitly says "done"
```

## Agentic Eagerness Control

### More Autonomous (Higher Exploration)

```python
system_prompt = """
<persistence>
- You are an agent - keep going until completely resolved
- Only terminate when problem is solved
- Never stop at uncertainty - research or deduce and continue
- Don't ask for confirmation - act and adjust later
- Document assumptions for user reference after finishing
</persistence>

<reasoning_settings>
reasoning_effort: high  # More thorough exploration
</reasoning_settings>
"""
```

### Less Autonomous (Faster Execution)

```python
system_prompt = """
<context_gathering>
- Search depth: very low
- Bias towards correct answer quickly, even if not fully complete
- Absolute maximum of 2 tool calls
- If need more time, update user and await confirmation
</context_gathering>

<reasoning_settings>
reasoning_effort: medium  # Faster decision-making
</reasoning_settings>
"""
```

## Tool Preambles (Agentic Flows)

For better user experience in multi-step workflows:

```markdown
<tool_preambles>
- Always begin by rephrasing user's goal clearly
- Outline structured plan before calling tools
- Narrate each step succinctly as you execute
- Mark progress clearly
- Finish with summary distinct from initial plan
</tool_preambles>
```

**Example output:**
```
I'm going to check a live weather service to get current conditions
in San Francisco, providing temperature in both F and C.

[calls weather tool]

The current temperature in San Francisco is 58°F (14°C) with partly
cloudy skies.
```

## Structured Outputs with PydanticAI

GPT-5 excels at structured outputs when using schema validation:

```python
from pydantic import BaseModel, Field
from pydantic_ai import Agent

class ArchitecturalProposal(BaseModel):
    approach: str = Field(description="Core approach in 2-3 sentences")
    components: list[str] = Field(description="Key components and responsibilities")
    pros: list[str]
    cons: list[str]
    red_flags: list[str] = Field(description="Watch out for these issues")

agent = Agent(
    "openai:gpt-5-codex",
    output_type=ArchitecturalProposal,
    system_prompt="Focus on simplicity and debuggability for solo developers."
)

# PydanticAI handles:
# - API calls with proper formatting
# - Automatic retry on validation errors
# - Structured output extraction and validation

result = await agent.run(
    "How should we implement event sourcing?",
    model_settings={"reasoning_effort": "high"}
)

print(result.output.approach)
print(result.output.red_flags)
```

## Common Pitfalls

### 1. Low Reasoning on Hard Tasks

❌ Using `minimal` for architecture/taste questions
✅ Always use `high` for nuanced decisions

### 2. Contradictory Instructions

❌ "Never schedule without consent" + "Auto-assign without contacting"
✅ "Auto-assign after informing patient"

### 3. Missing Hierarchy

❌ All instructions sound equally important
✅ Separate MUST vs SHOULD

### 4. Verbose Noise

❌ Default verbosity for all contexts
✅ Low for status, high for code

## Integration Examples

### Architect Skill (Proposal Generation)

```python
# skills/architect/lib/providers.py
import subprocess

def call_codex_via_cli(description: str) -> str:
    """Fallback: Use CLI when API integration not available."""
    prompt = f"""Generate proposal for: {description}

    Provide:
    1. Core approach (2-3 sentences)
    2. Key components
    3. Pros/cons
    4. Red flags
    """

    result = subprocess.run(
        f'echo {repr(prompt)} | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -',
        shell=True, capture_output=True
    )
    return result.stdout.decode()
```

**Better: Use API directly**

```python
from openai import OpenAI

def call_codex_via_api(description: str) -> str:
    """Preferred: Direct API call."""
    client = OpenAI()

    response = client.responses.create(
        model="gpt-5-codex",
        input=[{
            "role": "developer",
            "content": "Generate architectural proposals focused on simplicity."
        }, {
            "role": "user",
            "content": f"How should we implement: {description}"
        }],
        reasoning={"effort": "high"},
        text={"verbosity": "medium"}
    )

    return response.output_text
```

### Tournament Judging (Structured Output)

```python
# From tournament-mcp patterns
from pydantic_ai import Agent
from pydantic import BaseModel, Field

class PairwiseJudgment(BaseModel):
    criteria: dict[str, dict[str, float]] = Field(
        description="For each criterion, {'left': score, 'right': score} (0-10)"
    )
    verdict: str = Field(description="Must be 'left' or 'right'")
    confidence: float = Field(ge=0.0, le=1.0)

agent = Agent(
    "openai:gpt-5-codex",
    output_type=PairwiseJudgment,
    system_prompt="""You are an expert code evaluator.

    IMPORTANT:
    - Score BOTH items on EACH criterion: 0.0 (terrible) to 10.0 (excellent)
    - Use FULL range - don't cluster around 5.0
    - Verdict MUST be consistent with criteria scores
    """
)

result = await agent.run(comparison_prompt)
```

## When to Use Codex CLI (Rarely)

**Only use codex CLI when:**
1. Quick interactive exploration (REPL-like sessions)
2. Legacy scripts not yet migrated to API
3. Session continuity needed (`codex --resume`)

**Prefer API when:**
- Building agents or automation
- Need structured outputs (PydanticAI)
- Production workflows
- Type safety and validation matter

### Codex CLI Patterns (If You Must)

```bash
# Interactive Q&A
codex -m gpt-5-codex -c model_reasoning_effort="high" "your question"

# Batch/automation (ONLY if API not available)
echo "prompt" | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -

# Session management
codex --resume  # Resume last session
```

**⚠️ Common CLI pitfalls:**
- Using `codex -p -` instead of `codex exec --full-auto -` (hangs)
- Not setting `model_reasoning_effort="high"` for taste questions
- Using CLI in production when API would work

## Model Selection

### GPT-5 Variants

- **gpt-5-codex** - Best for coding, architecture, taste (use `reasoning_effort="high"`)
- **gpt-5** - General purpose, balanced
- **gpt-5-mini** - Faster, cheaper, still very capable
- **gpt-5-nano** - Fastest, good for simple tasks

### Across Providers (from Research Skill)

- **GPT-5 (codex):** Code review, taste, architecture, refactoring
- **Gemini:** High token capacity (>100k), large repos, multi-file analysis
- **Claude:** Long-context understanding, nuanced writing
- **Grok:** Quick queries, fallback option

## Quick Checklist

- [ ] Using API instead of CLI? (preferred)
- [ ] Architecture/taste question? Set `reasoning_effort="high"`
- [ ] Speed-critical task? Use `reasoning_effort="minimal"`
- [ ] Prompt has contradictions? Remove them
- [ ] Instructions have clear hierarchy (MUST vs SHOULD)?
- [ ] Agentic flow? Add tool preambles and persistence instructions
- [ ] Need structured output? Use PydanticAI with schema
- [ ] Verbosity appropriate? (low for status, high for code)

## Troubleshooting

**Poor quality on architecture questions:**
- Check: `reasoning_effort` not set to `high`
- Fix: Add `reasoning={"effort": "high"}` to API call

**Verbose status spam:**
- Check: Default verbosity
- Fix: Set `text={"verbosity": "low"}` or add prompt override

**Contradictory behavior:**
- Check: Prompt for conflicting instructions
- Fix: Remove contradictions, establish clear hierarchy

**Agent stops prematurely:**
- Check: Missing persistence instructions
- Fix: Add explicit stop conditions and persistence prompt

## See Also

- **Architect Skill:** `skills/architect/SKILL.md` - Uses GPT-5 for proposals
- **Research Skill:** `skills/research/SKILL.md` - Model selection guidance
- **Project Docs:** `CLAUDE.md#llm-provider-clis` - CLI reference
- **External Docs:** `/Users/alien/Projects/tooling/gpt5-prompting guide.txt`
