# Improved Evaluation Prompt for Architect Tournaments

Based on GPT-5 prompting best practices - clear hierarchy, no contradictions, explicit scoring.

## Current Issues

1. No explicit scoring instructions
2. Priorities are clear but judging criteria vague
3. Missing guidance on how to handle ties

## Improved Version

```markdown
You are evaluating architectural proposals for a solo developer who "can barely keep track of things."

## MUST prioritize (in order):

1. **Simplicity** (HIGHEST PRIORITY)
   - Solo dev can understand without reference docs
   - Can be explained in < 5 minutes
   - Debugging is straightforward when things break

2. **Debuggability**
   - Observable state at every step
   - Clear error messages (no "something went wrong")
   - REPL-friendly (can test parts interactively)

3. **Flexibility**
   - Can skip stages if needed
   - Can run tools independently
   - No forced workflows

4. **Provenance**
   - Can trace which proposal → spec → implementation
   - Audit trail exists
   - Decisions are documented

5. **Quality gates**
   - Bad specs caught before implementation
   - Validation happens early
   - Clear go/no-go criteria

## MUST reject if any:

- Infinite refinement loops (no escape hatch)
- Hidden automation (magic that can't be inspected)
- Complex orchestration (10+ steps that must run in sequence)
- Tight coupling (can't run stages independently)
- Over-engineering (10+ agents, dynamic planning, meta-workflows)

## Scoring instructions:

For EACH criterion (1-5 above):
- Score both proposals: 0.0 (terrible) to 10.0 (excellent)
- Use the FULL range - don't cluster around 5.0
- Justify score with specific evidence from proposal

Your verdict MUST be consistent with scores:
- Calculate average across all 5 criteria for each proposal
- Choose the proposal with higher average
- If tie (< 0.5 point difference), choose the SIMPLER one

## Output format:

{
  "criteria": {
    "simplicity": {"left": 8.5, "right": 6.0},
    "debuggability": {"left": 7.0, "right": 8.5},
    ...
  },
  "verdict": "left" | "right",
  "confidence": 0.85,  // 0-1, based on score margin
  "reasoning": "Brief explanation of key differences"
}
```

## Usage in architect.py

Replace lines 173-192 with:

```python
# Prepare evaluation prompt
eval_prompt = f"""You are evaluating architectural proposals for a solo developer who "can barely keep track of things."

Problem: {run_data.get('description', 'No description')}

## MUST prioritize (in order):

1. **Simplicity** (HIGHEST) - Solo dev can understand/debug easily
2. **Debuggability** - Observable state, clear errors, REPL-friendly
3. **Flexibility** - Can skip stages, run tools independently
4. **Provenance** - Trace proposal → spec → implementation
5. **Quality gates** - Catch bad specs before implementation

## MUST reject if any:

- Infinite refinement loops
- Hidden automation
- Complex orchestration (hard to debug when stuck)
- Tight coupling (can't run stages independently)
- Over-engineering (10+ agents, dynamic planning)

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

## Key Improvements

1. **Clear hierarchy**: MUST prioritize vs MUST reject
2. **Explicit scoring**: Exact 0-10 scale, full range, no clustering
3. **Tie-breaking rule**: Choose simpler when close
4. **No contradictions**: All requirements aligned
5. **Structured output**: Forces consistent format
