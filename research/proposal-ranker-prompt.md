# Proposal Ranking Instructions

You are evaluating architectural proposals for a tree database project with a 3-operation kernel.

## Context

The project overview is provided below. Read it carefully to understand:
- The current architecture (3-op kernel, derived indexes, transaction pipeline)
- Design principles (canonical state, pure functions, closed instruction set)
- Goals and constraints

## Ranking Criteria

Rank the proposals based on how well they fit the project's goals and philosophy.

### Primary Criteria (Must Have)

1. **Simplicity** - Does it reduce conceptual complexity?
   - Fewer abstractions, fewer concepts to learn
   - Easy to explain in a paragraph
   - Obvious failure modes

2. **Readability** - Can a human or LLM understand it quickly?
   - Self-documenting structure
   - Clear data flow
   - Minimal indirection

3. **Debuggability** - Can you see what's happening?
   - Observable intermediate states
   - Clear error messages pointing to root cause
   - Easy to inspect in REPL
   - Testable in isolation

4. **Expressiveness** - Does it enable powerful composition?
   - High-level features map naturally to primitives
   - Low lines-of-code for common operations
   - Eliminates boilerplate and ceremony
   - "Pit of success" - right way is easy way

### Secondary Criteria (Nice to Have)

5. **Novel Concepts** - New ideas are welcome IF:
   - They unlock genuinely better expressiveness
   - They're transferable (not one-off tricks)
   - They're well-known or easily graspable
   - They reduce overall complexity elsewhere

6. **DX for Humans + LLMs** - Developer experience matters:
   - Works well with REPL-driven development
   - Good error messages and validation feedback
   - Easy to reason about for AI code assistants
   - Clear extension points

### What NOT to Prioritize

- **Performance** - Ignore unless it's fundamentally impossible to fix later
  - Algorithmic complexity is fine to consider
  - Micro-optimizations are irrelevant

- **Feature Completeness** - Partial solutions are fine if the pattern is clear

- **Backwards Compatibility** - This is a greenfield evaluation

## Your Task

You will receive 15 architectural proposals. For each:

1. **Score** (1-10 scale):
   - 9-10: Exceptional - fundamentally better architecture
   - 7-8: Strong - clear improvements with minor tradeoffs
   - 5-6: Neutral - interesting but no clear win
   - 3-4: Weak - adds complexity or misses the point
   - 1-2: Poor - incompatible with project philosophy

2. **Reasoning** (2-3 sentences):
   - What's the core insight?
   - Why does it fit (or not fit) the project?
   - What's the key tradeoff?

3. **Implementation Risk** (Low/Medium/High):
   - How hard to implement?
   - How much existing code must change?

## Output Format

```markdown
# Architectural Proposal Rankings

## Methodology
[Briefly describe how you evaluated the proposals]

## Rankings

### Tier S (9-10): Exceptional
**Proposal X: [Title]**
- Score: 10/10
- Focus Area: [kernel/indexes/pipeline/extensibility/dx]
- Core Insight: [What's the key idea?]
- Why It Fits: [How does it improve simplicity/readability/debuggability/expressiveness?]
- Tradeoffs: [What are you giving up?]
- Implementation Risk: Low/Medium/High
- Key Quote: "[Most compelling sentence from proposal]"

[Repeat for each Tier S proposal]

### Tier A (7-8): Strong

[Same format]

### Tier B (5-6): Neutral

[Same format]

### Tier C (3-4): Weak

[Same format]

### Tier D (1-2): Poor

[Same format]

## Summary

**Top 3 Recommendations:**
1. Proposal X - [One sentence on why]
2. Proposal Y - [One sentence on why]
3. Proposal Z - [One sentence on why]

**Common Themes:**
- [What patterns emerged across strong proposals?]

**Red Flags:**
- [What anti-patterns appeared in weak proposals?]

## Meta-Observations

[Any insights about the proposals as a whole, or suggestions for combining ideas]
```

## Important Notes

- Be harsh but fair - this is about architectural fitness, not effort
- Consider how proposals interact with REPL-driven development
- Remember: developers are both humans AND LLMs using CLI/test/MCP tools
- Think about what would make debugging at 2 AM easier
- Value proposals that "spark joy" in their elegance

---

**The Project Overview is below. Read it before evaluating proposals.**
