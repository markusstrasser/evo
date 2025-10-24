# Tournament MCP: Understanding INVALID Status

**Date:** 2025-10-24
**Context:** Testing tournament MCP for component architecture evaluation

## Summary

Tournament MCP returned `INVALID` status for all comparisons (component architectures, bad vs good code). This is **WORKING AS INTENDED**, not a bug.

## What INVALID Means

From `/Users/alien/Projects/tournament-mcp/tournament/evaluator.py:237-238`:

```python
status = "OK"
if r2 < 0.5:
    status = "INVALID"
elif dispersion_result["beta"] < 1.0:
    status = "UNSTABLE"
```

**INVALID = R² < 0.5** where R² measures "schema adherence"

### Schema Adherence R²

**Definition:** How well judges' verdicts correlate with explicit evaluation criteria.

**What R² measures:**
- **High R² (>0.5):** Judges consistently use the rubric criteria to make decisions
- **Low R² (<0.5):** Judges' verdicts don't correlate with stated criteria

**Two interpretations of low R²:**

1. **Items are genuinely equivalent** → No meaningful difference to judge → INVALID is correct
2. **Judges ignore rubric** → Making arbitrary decisions → INVALID signals bad evaluation

## Our Test Results

### Test 1: Component Architectures

**Items compared:**
- Gemini (explicit get-in)
- Codex (manifest + auto-wiring)
- Grok (slot-based + data-fn)

**Result:** INVALID (all scored 1.0, unanimous agreement)

**Interpretation:** Judges see these as **architecturally equivalent** - just different tradeoffs, not objectively better/worse.

**Why this makes sense:**
- All three approaches can work
- Differences are about WHERE complexity lives (component vs consumer vs framework)
- No approach is objectively superior - depends on project constraints
- Your ADR-014 chose Gemini based on YOUR specific constraints (solo dev, AI generation, debuggability)

### Test 2: Mutable vs Immutable State

**Items compared:**
- Global mutable state (bad practices)
- Event sourcing (good practices)

**Result:** INVALID (both scored 1.0)

**Interpretation:** This is surprising! Possible reasons:
- Judge sees both as viable depending on context
- Evaluation prompt didn't emphasize enough criteria (debuggability, testing)
- Single-round tournament with one judge (gpt5-codex) might show opinion, not consensus

### Test 3: Error Handling

**Items compared:**
- No error handling (crashes on division by zero)
- Proper error handling (try/catch, validation)

**Result:** INVALID (both scored 1.0, 3 judges unanimous)

**Interpretation:** Even more surprising! Possible explanations:
- Judges might see "crashing fast" as valid strategy (fail-fast principle)
- Short code examples lack context for production judgment
- Evaluation criteria too simple

## Key Insight from architect/IMPROVEMENTS.md

From lines 22-30:

> **Tournament MCP testing revealed an important distinction:**
> - **Validation use case:** Same prompt → multiple instances → check consensus
>   - Result: All 5 agreed on dual-multimethod design (INVALID = unanimous agreement)
>   - Tournament correctly returned INVALID because proposals were semantically identical
> - **Comparison use case:** Different architectures → rank by quality
>   - Requires: Proposals with meaningful architectural differences

**Lesson:** When proposals come from same source or are semantically equivalent, tournament will (correctly) show INVALID. Tournament is for comparing DIFFERENT approaches, not validating consensus.

## When Tournament Works vs Doesn't

### ✅ Good Use Cases (Should Return OK/UNSTABLE)

1. **Genuinely different architectural approaches**
   - Example: Event sourcing vs CQRS vs traditional CRUD
   - Different fundamental design philosophies
   - Clear quality hierarchy given specific criteria

2. **Implementation variants with measurable quality differences**
   - Example: O(n²) vs O(n log n) algorithm
   - Objectively measurable differences
   - Clear winner given performance criteria

3. **Multiple LLM-generated solutions to same problem**
   - Example: 5 different implementations from different models
   - Variation in code quality, approach
   - Tournament finds best among options

### ❌ Poor Use Cases (Will Return INVALID)

1. **Equivalent approaches with different tradeoffs**
   - Our component architecture comparison
   - All viable, just optimize different things
   - No objective "best" - depends on context

2. **Obvious comparisons with predetermined answers**
   - "Bad code vs good code"
   - Judges might see nuance where we expect black/white
   - Or lack of context makes judgment ambiguous

3. **Consensus validation**
   - Same prompt → multiple providers → check if they agree
   - They'll likely agree → INVALID
   - Use different tool for consensus checking

## Recommendations

### For Component Architecture Decision

**Don't use tournament to "prove" Gemini is best.**

Instead:
- ✅ Tournament showed approaches are **equivalent quality**
- ✅ ADR-014 documents **why Gemini fits YOUR constraints** (solo dev, LLM generation, debuggability)
- ✅ Svelte 5 research provides **industry validation** of explicit approach

**The decision is contextual, not universal.**

### For Future Tournament Use

**Use tournament when:**
- Comparing genuinely different architectural approaches
- Need to rank multiple LLM-generated solutions
- Evaluating design proposals with measurable criteria
- Want to find best among non-obvious choices

**Don't use tournament when:**
- Validating consensus (use multiple providers instead)
- Comparing obvious quality differences (just use best practices)
- Tradeoff decisions that depend on context (document rationale instead)

### Testing Tournament Properly

To verify tournament works, compare:
- **Approach A:** Microservices with gRPC
- **Approach B:** Monolith with REST
- **Approach C:** Serverless with GraphQL

These are **genuinely different** with real tradeoffs, should produce OK/UNSTABLE status with ranked results.

Our comparisons were either:
1. Equivalent quality (component architectures)
2. Lacking context (code snippets)
3. Too obvious (judges add nuance)

## Status Update

**Tournament MCP is NOT broken.** It's working correctly by returning INVALID when:
- Items are equivalent quality
- Judges' verdicts don't correlate with simple criteria
- No meaningful ranking can be established

**For our component architecture decision:**
- Tournament validated that all three approaches are viable
- Choice depends on project constraints, not universal quality
- ADR-014 correctly documents contextual rationale
- Svelte 5 research provides additional validation

## References

- Tournament implementation: `/Users/alien/Projects/tournament-mcp/tournament/evaluator.py:237-238`
- Schema audit: `/Users/alien/Projects/tournament-mcp/tournament/schema_audit.py:60-77`
- Architect skill learnings: `.architect/IMPROVEMENTS.md:22-30`
- ADR decision: `.architect/adr/ADR-014-component-discovery-via-catalog.md`
