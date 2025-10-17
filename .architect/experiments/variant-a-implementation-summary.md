# Variant A Implementation Summary

**Date:** 2025-10-17
**Status:** ✅ Implemented and Tested

---

## What We Built

Specialized prompt system for the architect skill that gives each LLM provider a distinct architectural focus:

| Provider | Specialization | Focus |
|----------|----------------|-------|
| **Gemini** | Reactive/Event-Driven | Event sourcing, streams, immutability, temporal reasoning |
| **Codex** | Functional/Type-Driven | Types, pure functions, composition, effect management |
| **Grok** | Pragmatic/Debuggable | Simplicity, debuggability, operational visibility |

---

## Files Modified/Created

1. **`skills/architect/lib/prompts.py`** (NEW) - Prompt variant system
   - `get_gemini_reactive_prompt()` - Event-driven specialization
   - `get_codex_functional_prompt()` - Functional specialization
   - `get_grok_pragmatic_prompt()` - Pragmatic specialization
   - `get_prompt()` - Unified interface

2. **`skills/architect/lib/providers.py`** (MODIFIED)
   - Added `prompt_variant` parameter to all call functions
   - Integrated with prompts module

3. **`skills/architect/lib/architect.py`** (MODIFIED)
   - Added `prompt_variant` parameter to `propose()` and `review_cycle()`
   - Passes variant through to providers

4. **`skills/architect/test-variant-a.sh`** (NEW) - Test script

---

## Usage

### Python API

```python
import sys
sys.path.insert(0, 'skills/architect/lib')
import architect

# Use baseline (current behavior)
result = architect.propose(
    description="How to implement undo/redo?",
    prompt_variant="baseline"
)

# Use Variant A (specialized)
result = architect.propose(
    description="How to implement undo/redo?",
    prompt_variant="variant-a"
)
```

### Bash (via run.sh - TODO)

```bash
# Baseline
skills/architect/run.sh propose "How to implement undo/redo?"

# Variant A
skills/architect/run.sh propose "How to implement undo/redo?" --prompt-variant variant-a
```

---

## Prompt Specialization Details

### Gemini - Reactive/Event-Driven

**Architectural Biases:**
- Event sourcing over CRUD
- Reactive streams over imperative loops
- Immutable data structures
- Observable patterns

**Output Structure:**
1. Event flows (producers/consumers)
2. State transitions (how events change state)
3. Components (handlers, stores, projections)
4. Data model (event schemas, aggregates)
5. Trade-offs (consistency, complexity)

### Codex - Functional/Type-Driven

**Architectural Biases:**
- Strong static typing
- Pure functions with explicit effects
- Composition via pipelines
- Immutability by default
- Domain modeling with ADTs

**Output Structure:**
1. Type signatures (domain types)
2. Pure functions (transformations)
3. Effect boundaries (side-effect management)
4. API design (signatures as contracts)
5. Trade-offs (ceremony, testability)

### Grok - Pragmatic/Debuggable

**Architectural Biases:**
- Explicit over implicit
- Simple data structures
- Clear control flow
- Observable intermediate states
- Incremental adoption

**Output Structure:**
1. Developer mental model (3-sentence explanation)
2. Core abstractions (2-3 key concepts)
3. Debugging strategy (inspect, trace, debug)
4. Failure modes (detection, recovery)
5. Trade-offs (simplicity vs performance)

---

## Testing Status

✅ **Prompts module:** All three specialized prompts generate successfully
✅ **Content validation:** Each prompt contains expected specialization keywords
✅ **Python API:** `propose()` accepts `prompt_variant` parameter
✅ **Integration:** Variant flows through architect.py → providers.py → prompts.py

🔲 **Bash CLI:** Need to add `--prompt-variant` flag to `run.sh`
🔲 **Live test:** Need to run full proposal generation with real LLMs
🔲 **Diversity measurement:** Need to validate proposals are actually diverse

---

## User Feedback: Rethinking Metrics

**User's Key Insight:**
> "The metrics are kinda lame ... sometimes no diversity means the solution converges which is a good sign"

**You're absolutely right.** Mechanical diversity metrics (embedding distance, TF-IDF) miss the point:

### The Real Question

Not "Are proposals different?" but **"Is the diversity valuable?"**

**Valuable diversity:**
- Proposals surface different trade-offs
- Seeing multiple options changes your decision
- Each proposal highlights blind spots in others

**Noise diversity:**
- Proposals just vary arbitrarily
- No new information gained
- Could have picked any one randomly

### Better Metrics (LLM-Judged)

Instead of mechanical metrics, use LLM to judge:

1. **Decision Impact**
   ```
   Prompt: "If you had only seen proposal A, would you have made a different decision
   than after seeing all three proposals?"

   - Yes, proposals surfaced trade-offs I wouldn't have considered → VALUABLE
   - No, proposals were redundant → NOISE
   ```

2. **Trade-off Coverage**
   ```
   Prompt: "Do these proposals explore fundamentally different trade-offs?"

   Example valuable diversity:
   - Proposal A: Prioritizes simplicity, accepts performance cost
   - Proposal B: Prioritizes performance, accepts complexity
   - Proposal C: Balances both, accepts neither extreme

   Each explores a different point in the design space.
   ```

3. **Convergence as Signal**
   ```
   If all 3 specialized prompts produce similar proposals:
   - This might be GOOD → obvious solution, high confidence
   - Not a failure of the system

   Low diversity + high quality = strong agreement on best approach
   High diversity + mixed quality = unclear problem, need human judgment
   ```

4. **Complementarity**
   ```
   Prompt: "After reading all proposals, do you have a more complete understanding
   of the problem space than from any single proposal?"

   - Yes → proposals are complementary (valuable diversity)
   - No → proposals are redundant (noise diversity)
   ```

### Revised Evaluation Approach

**Step 1: Generate proposals** with both baseline and variant-a

**Step 2: Tournament ranking** (existing infrastructure)

**Step 3: LLM meta-judge** answers:
- "Is the diversity valuable or noise?" (binary)
- "Which proposals are complementary vs redundant?" (pairwise)
- "Would you have made a different decision with only 1 proposal?" (counterfactual)
- "Does consensus (low diversity) indicate high confidence?" (convergence analysis)

**Step 4: Human validation** on subset:
- "Did these proposals help you make a better decision?"
- "Which proposals were most valuable?"
- "Was any proposal redundant?"

### Success Criteria (Revised)

**Old (wrong):**
- ✗ 50%+ increase in embedding distance
- ✗ More unique architectural patterns mentioned

**New (right):**
- ✅ 70%+ of runs judged as "valuable diversity" by LLM meta-judge
- ✅ Human validation: "Seeing multiple proposals improved my decision"
- ✅ Complementarity: Each proposal highlights different trade-offs
- ✅ Convergence awareness: Low diversity on simple problems is OK (not penalized)

---

## Next Steps

### Immediate (today):
1. Add `--prompt-variant` flag to `run.sh` bash script
2. Test with real LLM calls on simple example

### Short-term (this week):
3. Run 5 test cases: simple → complex
4. Collect baseline vs variant-a proposals
5. Implement LLM meta-judge for "valuable diversity" assessment
6. Human validation on same 5 cases

### Medium-term (next week):
7. If variant-a shows promise, expand to 20 test cases
8. Write up findings
9. Decide: make variant-a the default, or keep as opt-in flag?

---

## Cost Estimate

**Per run (3 proposals):**
- Gemini 2.5 Pro: ~2000 tokens @ $0.01/1k = $0.02
- GPT-5 Codex: ~2000 tokens @ $0.05/1k = $0.10
- Grok-4: ~2000 tokens @ $0.015/1k = $0.03
- **Total: ~$0.15 per run**

**20 test cases × 2 variants = 40 runs = $6.00**

**Plus tournament evaluation (judges):** ~$2-3 per run × 40 = $80-120

**Total experiment cost: ~$86-126**

---

## Philosophy Check

**Remember:** The goal is not maximum diversity, but **valuable diversity that improves decisions**.

- Low diversity on simple problems = good (convergence on obvious solution)
- High diversity on complex problems = good (exploring trade-off space)
- High noise diversity = bad (arbitrary variation without insight)

**Measure what matters:** Did seeing multiple proposals make you smarter about the problem?

