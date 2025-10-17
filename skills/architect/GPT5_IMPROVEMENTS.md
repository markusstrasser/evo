# GPT-5 Prompting Improvements for Architect Skill

Based on `skills/gpt5-prompting/SKILL.md` best practices.

## Summary

The architect skill currently works well but could benefit from GPT-5 prompting best practices:

1. **API-first integration** (instead of CLI)
2. **Clearer instruction hierarchy** (MUST vs SHOULD)
3. **Better evaluation prompts** (explicit scoring, no contradictions)

## Changes Recommended

### 1. Migrate to API-first (providers.py)

**Current:** Uses `codex exec` CLI for all calls
**Issue:** CLI is slower, less reliable, harder to test
**Fix:** Use OpenAI SDK with CLI fallback

**See:** `skills/architect/lib/providers_api.py` for implementation

**Benefits:**
- Type-safe structured outputs (can use PydanticAI)
- Better error handling
- Faster (no subprocess overhead)
- Easier to test

**Migration path:**
```python
# Option 1: Drop-in replacement (try API, fall back to CLI)
import providers_api as providers

# Option 2: Gradual migration
from providers_api import call_codex  # Uses API
from providers import call_gemini, call_grok  # Keep CLI for now
```

### 2. Improve Proposal Generation Prompts

**Current issues:**
- All instructions sound equally important
- No clear MUST vs SHOULD hierarchy
- Missing explicit requirements

**Fixed in `providers_api.py`:**

```python
system_prompt = """You are an architectural advisor for solo developers.

MUST (hard requirements):
- Focus on simplicity over cleverness
- Debuggability is critical (observable state, clear errors)
- Solutions must be REPL-friendly (easy to test interactively)
- Avoid hidden automation or complex orchestration

SHOULD (preferences):
- Prefer explicit over implicit
- Minimize dependencies
- Document tradeoffs clearly
"""

user_prompt = f"""Generate an implementation proposal for: {description}

REQUIRED sections:
1. Core approach (2-3 sentences explaining fundamental strategy)
2. Key components and their responsibilities
3. Data structures and storage choices
4. Pros and cons (be honest about tradeoffs)
5. Red flags to watch for during implementation
"""
```

**Key improvements:**
- Clear MUST vs SHOULD separation
- REQUIRED sections (not optional)
- Explicit about what's needed

### 3. Improve Evaluation Prompt (architect.py)

**Current (lines 173-192):**
- Good priorities listed
- Red flags identified
- But: No explicit scoring instructions
- But: No tie-breaking rules
- But: Vague "judge which is best"

**Improved version in `evaluation_prompt.md`:**

```python
eval_prompt = f"""You are evaluating proposals for a solo developer.

Problem: {run_data.get('description')}

## MUST prioritize (in order):

1. **Simplicity** (HIGHEST) - Solo dev can understand/debug easily
2. **Debuggability** - Observable state, clear errors, REPL-friendly
3. **Flexibility** - Can skip stages, run tools independently
4. **Provenance** - Trace proposal → spec → implementation
5. **Quality gates** - Catch bad specs before implementation

## MUST reject if any:

- Infinite refinement loops
- Hidden automation
- Complex orchestration
- Tight coupling
- Over-engineering (10+ agents)

## Scoring instructions:

For EACH criterion (1-5):
- Score both proposals: 0.0 (terrible) to 10.0 (excellent)
- Use FULL range - don't cluster around 5.0
- Justify with specific evidence

Verdict MUST be consistent with scores:
- Calculate average across all 5 criteria
- Choose higher average
- If tie (< 0.5 difference), choose SIMPLER

Judge which proposal best fits these priorities.
"""
```

**Key improvements:**
- Explicit 0-10 scoring per criterion
- "Use FULL range" (GPT-5 tends to cluster without this)
- Clear tie-breaking rule
- Verdict must match scores (no contradictions)

## Implementation Plan

### Phase 1: Improve Prompts (Low Risk)

1. Update `providers.py` with MUST/SHOULD hierarchy
2. Update evaluation prompt in `architect.py`
3. Test with a few proposal runs
4. Compare quality before/after

**Effort:** ~30 minutes
**Risk:** Low (just prompt changes)
**Impact:** Better proposal quality, clearer evaluation

### Phase 2: Add API Integration (Medium Risk)

1. Install OpenAI SDK: `pip install openai`
2. Rename `providers.py` → `providers_cli.py`
3. Rename `providers_api.py` → `providers.py`
4. Test end-to-end workflow
5. Keep CLI fallback for when API unavailable

**Effort:** ~1 hour (includes testing)
**Risk:** Medium (code changes, new dependency)
**Impact:** Faster, more reliable, type-safe

### Phase 3: Structured Outputs (Optional)

Once API integrated, can use PydanticAI for structured proposals:

```python
from pydantic import BaseModel, Field
from pydantic_ai import Agent

class Proposal(BaseModel):
    approach: str = Field(description="Core approach (2-3 sentences)")
    components: list[str]
    data_structures: str
    pros: list[str]
    cons: list[str]
    red_flags: list[str]

agent = Agent(
    "openai:gpt-5-codex",
    output_type=Proposal,
    system_prompt="..."
)

result = await agent.run(description)
# result.output is validated Proposal object
```

**Effort:** ~2 hours
**Risk:** Medium (architectural change)
**Impact:** Type safety, validation, better error handling

## Testing Checklist

Before deploying changes:

- [ ] Run `skills/architect/run.sh propose "test problem"`
- [ ] Verify all 3 providers (gemini, codex, grok) work
- [ ] Run `skills/architect/run.sh review "test problem"`
- [ ] Check proposal quality (MUST/SHOULD followed?)
- [ ] Check ranking makes sense (scores use full range?)
- [ ] Test fallback (rename `openai` to break API, verify CLI works)

## References

- **GPT-5 Prompting Skill:** `skills/gpt5-prompting/SKILL.md`
- **API Examples:** `skills/gpt5-prompting/examples/api-integration.py`
- **Pitfalls Catalog:** `skills/gpt5-prompting/data/common-pitfalls.edn`

## Questions?

- **Why API over CLI?** More reliable, type-safe, faster, easier to test. CLI is legacy.
- **Why MUST vs SHOULD?** GPT-5 wastes reasoning tokens on contradictions. Clear hierarchy prevents this.
- **Why explicit scoring?** GPT-5 clusters scores around 5.0 without guidance. "Use FULL range" fixes this.
- **Do we lose anything?** No - CLI fallback kept. Only gains in reliability and quality.
