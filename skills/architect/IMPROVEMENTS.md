# Architect Skill Improvements

**Date:** 2025-10-24
**Based on:** Real usage during plugin architecture evaluation

## Summary of Issues Found

### 1. Tournament Integration is Broken
**Problem:** Uses subprocess call to `tournament` CLI which fails every time
```python
subprocess.run(["tournament", "compare", ...])  # Always exits with status 1
```

**Solution:** Use tournament MCP server instead (already configured in `.mcp.json`)
```python
# Should use Claude Code's MCP tool:
mcp__tournament__compare_items(left=prop1, right=prop2, evaluation_prompt=...)
```

**Impact:** All ranking fell back to simple heuristic instead of proper tournament evaluation

**Update (2025-10-24):** Tournament MCP testing revealed an important distinction:
- **Validation use case:** Same prompt → multiple instances → check consensus (what we did)
  - Result: All 5 agreed on dual-multimethod design (INVALID status = unanimous agreement)
  - Tournament correctly returned INVALID because proposals were semantically identical
- **Comparison use case:** Different architectures → rank by quality (what tournament is for)
  - Requires: Proposals with meaningful architectural differences
  - Example: Compare dual-multimethod vs single-multimethod vs direct dispatch

**Lesson:** When proposals come from same prompt, tournament will (correctly) show no difference. Use tournament to compare DIFFERENT architectural approaches, not to validate consensus.

### 2. Prompt Construction Needs Improvement

**What I learned:**
- LLMs need VERY explicit context about what they're evaluating
- First attempts confused `:update-meta` with ClojureScript's metadata system
- Needed to pipe full source code to get correct understanding
- Name "update-meta" triggered wrong associations

**Improvements:**
1. **Always provide source code context** when evaluating architectural decisions
2. **Name operations carefully** - avoid terms with existing language meanings
3. **Be explicit about "ANALYZE THIS vs DESIGN NEW"** - LLMs default to proposing solutions
4. **Front-load constraints** - mention "event sourcing", "3-op kernel", etc. early

**Example of what works:**
```markdown
# YOU ARE EVALUATING AN EXISTING SYSTEM (not designing new)

## Current 3-Operation Kernel
[source code here]

## The Proposal: Add :assoc-path (not :update-meta)
[explicit description]

## Your Task: Honest Evaluation
[specific questions]
```

### 3. Insufficient Context on First Attempts

**Iterations required:**
1. First try: Generic proposal → LLMs misunderstood completely
2. Second try: Better prompt → Still misunderstood (wrong problem space)
3. Third try: Full source code piped → FINALLY understood

**Lesson:** Don't be stingy with context. Pipe relevant source files directly.

### 4. Multiple Provider Instances Not Supported

**Issue:** Original code created ID collisions when running 5x codex
```python
proposal_id = f"{run_id}-{provider_name}"  # Collision!
```

**Fixed:** Added instance numbering
```python
provider_counts[name] = provider_counts.get(name, 0) + 1
proposal_id = f"{run_id}-{provider_name}-{instance}"
```

**Now works:** `--providers codex,codex,codex,codex,codex`

## Recommended Changes

### Priority 1: Fix Tournament Integration

**File:** `skills/architect/lib/architect.py`

**Current (broken):**
```python
# Check if tournament CLI is available
TOURNAMENT_AVAILABLE = shutil.which("tournament") is not None

# ... later ...
result = subprocess.run(["tournament", "compare", ...])
```

**Proposed:**
```python
# NO subprocess calls - use MCP tool from Claude Code context
def rank_via_tournament_mcp(proposals, evaluation_prompt):
    """
    Rank proposals using tournament MCP server.
    NOTE: This function is called FROM Claude Code, which has mcp__tournament__ tools.
    """
    # Build items dict
    items = {p["id"]: p["content"] for p in proposals}

    # NOTE: This would need to be called from Claude Code context, not subprocess
    # The architect.py script runs via llmx/subprocess, so it CAN'T call MCP tools
    #
    # SOLUTION: Return a signal that Claude Code should handle ranking
    return {"status": "needs_mcp_ranking", "items": items, "prompt": evaluation_prompt}
```

**BETTER SOLUTION:** Architect skill should OUTPUT ranking request, Claude Code calls MCP:

```python
# In architect.py
def rank_proposals(run_id, ...):
    # Don't try to rank here - return data for Claude Code to rank
    return {
        "needs_ranking": True,
        "items": {p["id"]: p["content"] for p in proposals},
        "evaluation_prompt": eval_prompt
    }
```

```python
# In skills/architect/run.sh
# After Python returns, check if needs_ranking
if result.get("needs_ranking"):
    echo "Rankings requires MCP tournament tool..."
    echo "Run this from Claude Code:"
    echo "  mcp__tournament__compare_multiple(...)"
fi
```

**Actually:** Since architect runs in subprocess, it CAN'T access MCP tools. Options:

1. **Keep fallback heuristic** (current, works but suboptimal)
2. **Have Claude Code call tournament MCP separately** after architect finishes
3. **Rewrite architect as Claude Code native tool** (big refactor)

**Recommended:** Option 2 - Document that ranking is separate step

### Priority 2: Improve Prompt Template

**File:** `skills/architect/lib/providers.py`

**Add source code inclusion helper:**
```python
def format_proposal_prompt(description, constraints, source_files=None):
    """
    Build proposal prompt with optional source code context.

    Args:
        description: Problem description
        constraints: Project constraints
        source_files: Optional dict of {filename: content}
    """
    prompt_parts = [
        f"<role>{role_text}</role>",
        format_constraints_prompt(constraints),
        f"<task>{description}</task>"
    ]

    if source_files:
        context = "\n\n".join(
            f"## {filename}\n```clojure\n{content}\n```"
            for filename, content in source_files.items()
        )
        prompt_parts.insert(1, f"<source_context>\n{context}\n</source_context>")

    return "\n\n".join(prompt_parts)
```

**Add explicit framing:**
```python
EVALUATION_FRAME = """
YOU ARE EVALUATING AN EXISTING SYSTEM.

This is NOT a greenfield design. You have a working system and are evaluating
a specific proposal for modification.

Your task: Provide honest assessment of tradeoffs, NOT propose alternatives.
"""
```

### Priority 3: Update Documentation

**File:** `skills/architect/SKILL.md`

**Add section on context:**
```markdown
## Providing Context

Architect skill works best with FULL context. Options:

### Pipe Source Files
```bash
cat proposal.md src/core/*.cljc | skills/architect/run.sh propose "..."
```

### Use --source-dir (TODO: not implemented yet)
```bash
skills/architect/run.sh propose "..." --source-dir src/
```

### Learnings:
- **Generic prompts → generic/wrong answers**
- **Pipe relevant source code → accurate understanding**
- **Name operations carefully** (avoid language keyword collisions)
```

**Add section on ranking:**
```markdown
## Ranking Limitations

The `rank` command tries to use tournament evaluation but falls back to
simple heuristics because:
- Architect runs in subprocess (no MCP access)
- Tournament CLI not available

**Workaround:**
After `propose`, manually call tournament MCP from Claude Code:
```clojure
(mcp__tournament__compare_multiple
  items {"prop-1" "content..." "prop-2" "..."}
  evaluation_prompt "...")
```

Then use ranking to make `decide` call.
```

### Priority 4: Phase Out Outdated Instructions

**Files to update:**

1. **`skills/architect/SKILL.md`** - Main docs
   - Remove references to features not implemented
   - Add learnings from real usage
   - Document MCP ranking limitation

2. **`skills/architect/run.sh`** - Help text
   - Update examples to show source piping
   - Note ranking limitations
   - Add troubleshooting section

3. **`.architect/project-constraints.md`** - Constraints file
   - Update with learnings (pipe source, name carefully)
   - Add examples of good vs bad prompts

4. **`dev/claude-skills-howto.md`** - If exists
   - Update architect skill usage examples
   - Show real session from today

### Priority 5: Add Post-Session Analysis

**New file:** `skills/architect/lib/analyze_run.py`

```python
def analyze_run(run_id):
    """
    Analyze a completed architect run for quality/learnings.

    Reports:
    - Were proposals diverse or repetitive?
    - Did they understand the problem correctly?
    - Quality metrics (length, specificity, etc.)
    - Suggested improvements for next run
    """
    run_data = storage.load_run(run_id)
    proposals = run_data["proposals"]

    # Simple heuristics
    lengths = [len(p["content"]) for p in proposals]
    similarities = compute_jaccard_similarities(proposals)

    return {
        "diversity": 1.0 - avg(similarities),
        "avg_length": avg(lengths),
        "understood_correctly": detect_misunderstandings(proposals),
        "recommendations": generate_recommendations(run_data)
    }
```

## Implementation Plan

1. ✅ **Document issues** (this file)
2. **Fix provider instances** (already done in session)
3. **Update SKILL.md** with learnings
4. **Add source piping docs**
5. **Note tournament MCP limitation**
6. **Create improved prompt template**
7. **Add troubleshooting guide**

## Learnings for Future Skills

### What Worked
- **Iterative refinement** - Run, analyze, improve prompt, run again
- **Full source code context** - Piping actual code files was critical
- **Explicit framing** - "YOU ARE EVALUATING" vs "DESIGN"
- **Multiple providers** - 5 independent opinions better than 1

### What Didn't Work
- **Generic prompts** - Led to misunderstanding
- **Assuming LLMs infer correctly** - They don't, be explicit
- **Tournament CLI fallback** - Subprocess can't access MCP tools
- **Stingy with context** - Give them everything relevant

### Patterns to Reuse
- **Three-iteration pattern**: Generic → Better prompt → Full context
- **Unanimous agreement = validated** - When all 5 agree, trust it
- **Misunderstanding = prompt problem** - Not model stupidity
- **Name collision detection** - Check for language keyword conflicts

## Questions for Future

1. **Should architect be native Claude Code tool?** (vs subprocess)
   - Pro: Could use MCP tournament directly
   - Con: Loses independence from Claude Code

2. **Should we cache source context?**
   - Large source files repeated across providers
   - Could template with references

3. **Should we add auto-context detection?**
   - Detect mentioned files (src/core/ops.cljc)
   - Auto-include in prompt

4. **Should proposals be shorter?**
   - Current: 3-5k chars each
   - Could ask for structured bullets instead

## References

**Runs completed today:**
- `7102cb63-7401-4b80-ba86-83abb519b54b` - Plugin expressivity (misunderstood)
- `0d7ea747-2898-4e65-b910-749f96661e2e` - :update-meta op (misunderstood)
- `6c6159a7-ca25-4f4c-90cb-9da1acd89711` - :assoc-path op (understood!)
- `7fd9ac2a-7bfe-4e1a-9037-39b6d177f888` - Intent router (understood + approved!)

**Success rate:**
- First 2 runs: Completely misunderstood (0%)
- Last 2 runs: Perfectly understood (100%)
- Difference: Full source code + explicit framing

**Key insight:** Context quality matters more than model choice.
