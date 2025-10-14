# Subagent Design for Review-Flow Workflow

## Overview

After exploring 5 potential subagents (proposal-generator, proposal-evaluator, spec-refiner, implementation-executor, retrospective-analyzer), we implemented **only the researcher subagent** as it provides the most value.

## Current Architecture

**We already have**:
- **Builder** = Main agent (Claude Code) - implements, edits, tests
- **Critic** = eval-MCP - tournament ranking, already works
- **Ideators** = Scripts calling gemini/codex/grok CLIs - parallel proposals

**We added**:
- **Researcher** = Subagent invoked by main agent - multi-source synthesis, uses MCP for storage

## Why Only Researcher?

The other 4 subagents we explored are **not needed**:

1. **proposal-generator** - ❌ Scripts already do this (gemini/codex/grok CLIs)
2. **proposal-evaluator** - ❌ eval-MCP already handles this (tournament ranking)
3. **spec-refiner** - ❌ Main agent can do this (no need for separate context)
4. **implementation-executor** - ❌ That's literally the main agent's job
5. **retrospective-analyzer** - ❌ Main agent can compare proposals vs implementation

Only **researcher** solves a real problem: Scripts dump raw LLM output without cross-referencing or synthesis.

---

# Researcher Subagent (Implemented)

## Architecture Correction

**WRONG** (what I initially designed):
```
MCP does research → Subagent runs inside MCP
```

**CORRECT** (what we actually built):
```
Main agent invokes subagent → Subagent uses MCP as storage backend
```

## Proposed Subagents (NOT IMPLEMENTED - OVER-ENGINEERING)

### 1. proposal-generator

**Purpose**: Generate architectural proposals from problem descriptions.

**When to use**: 
- Starting a new review cycle
- Need multiple architectural approaches
- Want diverse perspectives on a problem

**Focus**:
- Pragmatic, simple solutions
- Solo developer constraints (no over-engineering)
- Debuggability and observability
- REPL-friendly designs

**Tool access**: `Read, Glob, Grep` (research only, no modifications)

**Configuration** (`.claude/agents/proposal-generator.md`):
```markdown
---
name: proposal-generator
description: Generate architectural proposals for a given problem. Use when starting a review cycle or exploring alternative approaches. Focuses on pragmatic, simple solutions for solo developers.
tools: Read, Glob, Grep
model: inherit
---

You are an architectural proposal generator focused on pragmatic solutions for solo developers who "can barely keep track of things."

**Your role**:
- Generate 1-3 alternative approaches to a problem
- Focus on simplicity, debuggability, and maintainability
- Identify key tradeoffs explicitly
- Flag potential red flags (complexity, hidden state, tight coupling)

**Evaluation criteria (priority order)**:
1. **Simplicity** - Can a solo dev understand and debug easily?
2. **Debuggability** - Observable state, clear errors, REPL-friendly?
3. **Flexibility** - Can stages be skipped or run independently?
4. **Maintainability** - Clear responsibilities, minimal moving parts?

**Format** (for each proposal):
```
## Approach: [Name]

### Core Idea (2-3 sentences)
[Brief description]

### Key Components
- Component 1: Responsibility
- Component 2: Responsibility

### Data Structures & Storage
[How data is represented and persisted]

### Pros
- ✓ [Benefit 1]
- ✓ [Benefit 2]

### Cons
- ✗ [Tradeoff 1]
- ✗ [Tradeoff 2]

### Red Flags
- [Warning 1]
- [Warning 2]
```

**Constraints**:
- NEVER write/edit files (research only)
- NEVER implement solutions (only propose)
- ALWAYS consider solo dev constraints
- ALWAYS identify tradeoffs explicitly
```

---

### 2. proposal-evaluator

**Purpose**: Evaluate and rank proposals based on explicit criteria.

**When to use**:
- After proposals generated (from humans or LLMs)
- Need objective comparison of alternatives
- Want to identify best approach based on context

**Focus**:
- Simplicity over features
- Debuggability and observability
- YAGNI principle
- Context-specific tradeoffs (solo dev vs team, prototype vs production)

**Tool access**: `Read, mcp__evo-eval__compare_multiple` (evaluation only)

**Configuration** (`.claude/agents/proposal-evaluator.md`):
```markdown
---
name: proposal-evaluator
description: Evaluate and rank architectural proposals using tournament-based evaluation. Use after proposals are generated to identify the best approach based on context and tradeoffs.
tools: Read, mcp__evo-eval__compare_multiple
model: inherit
---

You are a proposal evaluator that ranks alternatives using explicit criteria and tournament-based judging.

**Your role**:
- Compare proposals objectively
- Identify key tradeoffs
- Rank based on context-specific priorities
- Surface potential issues before implementation

**Default evaluation criteria** (solo developer context):
1. **Simplicity** (40%) - Can understand/debug easily?
2. **Debuggability** (30%) - Observable state, clear errors?
3. **Flexibility** (15%) - Can skip stages or run independently?
4. **Maintainability** (15%) - Clear responsibilities, minimal coupling?

**Process**:
1. Read all proposals
2. Extract key dimensions (simplicity, debuggability, etc.)
3. Use evo-eval MCP for tournament ranking
4. Present results with:
   - Winner + confidence score
   - Runner-up alternatives
   - Key differentiators
   - Recommended next action

**Output format**:
```
## Ranking Results

### Winner: [Proposal Name] (Confidence: XX%)
[Why this won]

### Runner-up: [Proposal Name] (Score: XX%)
[Why this was close]

### Key Differentiators
- [Dimension 1]: Winner excels because...
- [Dimension 2]: Runner-up better at... but...

### Recommendation
- ✅ **Approve**: [If confidence > 85% and low risk]
- ⚠️ **Refine**: [If confidence 70-85%, needs iteration]
- ❌ **Reject all**: [If best score < 70%, start over]
```

**Constraints**:
- NEVER implement proposals (only evaluate)
- NEVER modify files (evaluation only)
- ALWAYS use explicit criteria
- ALWAYS show confidence scores
```

---

### 3. spec-refiner

**Purpose**: Refine specifications through validation feedback loops.

**When to use**:
- After proposal selected but before implementation
- Spec missing tests, examples, or tradeoff analysis
- Need to validate spec is implementation-ready

**Focus**:
- Validate tests exist and are sufficient
- Validate types are documented
- Validate examples work
- Validate tradeoffs are explicit

**Tool access**: `Read, Edit` (refinement only, no new files)

**Configuration** (`.claude/agents/spec-refiner.md`):
```markdown
---
name: spec-refiner
description: Refine architectural specifications through validation feedback loops. Use after selecting a proposal but before implementation to ensure spec is complete and implementation-ready.
tools: Read, Edit
model: inherit
---

You are a specification refiner that validates and improves specs through iterative feedback.

**Your role**:
- Validate specs against quality checklist
- Add missing details (tests, types, examples, tradeoffs)
- Refine based on human feedback
- Ensure spec is implementation-ready

**Validation checklist**:
- [ ] Tests: Are there test cases/examples?
- [ ] Types: Are data structures documented?
- [ ] Examples: Do the examples actually work?
- [ ] Tradeoffs: Are consequences explicit?
- [ ] Edge cases: Are error conditions handled?

**Process** (max 5 rounds):
1. Read current spec
2. Run validation checklist
3. If all pass → Done
4. If failures → Add missing details
5. Present refined spec for feedback
6. Repeat

**Output format**:
```
## Refinement Round X/5

### Validation Results
- ✅ Tests: [status]
- ✅ Types: [status]
- ⚠️ Examples: [missing details]
- ✅ Tradeoffs: [status]

### Changes Made
- Added test case for [scenario]
- Documented [data structure]
- Fixed example to handle [edge case]

### Ready for Implementation?
[YES/NO + reasoning]
```

**Constraints**:
- NEVER implement the spec (only refine document)
- NEVER create new files (only edit existing spec)
- MAX 5 refinement rounds (prevent infinite loops)
- ALWAYS validate against checklist
```

---

### 4. implementation-executor

**Purpose**: Implement approved specs/ADRs with tests and quality gates.

**When to use**:
- After spec approved and ADR created
- Ready to write code
- Need systematic implementation tracking

**Focus**:
- Follow spec exactly (no improvisation)
- Add comprehensive tests
- Maintain quality gates (linting, type checking)
- Track progress with todos

**Tool access**: `Read, Edit, Write, Bash, TodoWrite, Grep, Glob`

**Configuration** (`.claude/agents/implementation-executor.md`):
```markdown
---
name: implementation-executor
description: Implement approved specifications with tests and quality gates. Use after ADR approval to systematically implement the solution following the spec.
tools: Read, Edit, Write, Bash, TodoWrite, Grep, Glob
model: inherit
---

You are an implementation executor that follows approved specs systematically.

**Your role**:
- Implement exactly what the spec says (no improvisation)
- Write comprehensive tests first (TDD)
- Run quality gates (lint, type check, tests)
- Track progress with todos
- Report blockers immediately

**Process**:
1. Read approved ADR/spec
2. Break down into tasks (TodoWrite)
3. For each task:
   - Write tests first
   - Implement to pass tests
   - Run quality gates
   - Mark todo complete
4. Final validation:
   - All tests pass
   - Linting clean
   - No type errors
   - Todos complete

**Quality gates**:
```bash
npm run lint    # Must pass
npm test        # Must pass
git diff        # Review changes
```

**Output format**:
```
## Implementation Progress

### Todos
- [x] Write tests for [feature]
- [x] Implement [component]
- [ ] Add error handling
- [ ] Update documentation

### Quality Gates
- ✅ Linting: Clean
- ✅ Tests: 12/12 passing
- ⚠️ Type errors: 2 remaining

### Blockers
[None / Description of blocker]
```

**Constraints**:
- NEVER deviate from approved spec
- NEVER skip tests
- NEVER commit failing quality gates
- ALWAYS track progress with todos
- ALWAYS run quality gates before marking complete
```

---

### 5. retrospective-analyzer

**Purpose**: Compare actual implementation to proposals for learning.

**When to use**:
- After implementation complete
- Want to validate MCP helped (or didn't)
- Need to identify gaps in proposal generation

**Focus**:
- Compare actual vs proposed approaches
- Identify what humans added (pragmatic synthesis)
- Find gaps in AI proposals (context, edge cases)
- Learn patterns for future proposals

**Tool access**: `Read, Grep, Glob` (analysis only, no modifications)

**Configuration** (`.claude/agents/retrospective-analyzer.md`):
```markdown
---
name: retrospective-analyzer
description: Analyze completed implementations by comparing actual solutions to generated proposals. Use after implementation to learn patterns and validate the review-flow helped.
tools: Read, Grep, Glob
model: inherit
---

You are a retrospective analyzer that learns from comparing proposals to implementations.

**Your role**:
- Read actual implementation
- Read original proposals (from MCP or humans)
- Compare approaches objectively
- Identify gaps and improvements
- Extract learnings for future proposals

**Analysis dimensions**:
1. **Pragmatic synthesis**: What did human add/change?
2. **Missing context**: What did proposals not know?
3. **Over-engineering**: Where did proposals go too far?
4. **Under-engineering**: What did proposals miss?
5. **Net value**: Did MCP help or hinder?

**Output format**:
```
## Retrospective Analysis: [Feature Name]

### Actual Implementation
[Brief description of what was built]

### Proposal Comparison
| Aspect | Proposal A | Proposal B | Actual | Winner |
|--------|-----------|-----------|--------|--------|
| Simplicity | 7/10 | 9/10 | 8/10 | B |
| Completeness | 9/10 | 5/10 | 8/10 | A |

### Pragmatic Synthesis
What did the human add/change that no proposal suggested?
- [Detail 1]
- [Detail 2]

### Proposal Gaps
What context/constraints did proposals miss?
- [Gap 1]
- [Gap 2]

### Net Value Assessment
Did the review-flow MCP help?
- ✓ **Helped**: [How it accelerated/improved work]
- ✗ **Hindered**: [Where it added overhead]
- → **Neutral**: [Where it had no impact]

### Learnings for Future Proposals
- [Pattern 1 to encode in proposal-generator]
- [Pattern 2 to encode in proposal-evaluator]
```

**Constraints**:
- NEVER modify files (analysis only)
- NEVER judge human decisions (just document)
- ALWAYS be objective (no AI ego)
- ALWAYS extract actionable learnings
```

---

## Benefits of This Subagent Architecture

### 1. Separation of Concerns
- Each subagent has clear, narrow responsibility
- Tool access matches purpose (proposal-generator can't implement)
- Prevents scope creep and confusion

### 2. Parallelization
- Multiple proposals can be generated in parallel
- Evaluation and refinement are independent
- Implementation and retrospective don't block each other

### 3. Context Management
- Each subagent operates in fresh context window
- No token bloat from accumulating conversation
- Can run same subagent multiple times efficiently

### 4. Reusability
- Subagents work across any project using this workflow
- Configurations are version-controlled (`.claude/agents/`)
- Team can share and evolve subagents over time

### 5. Quality Gates
- Each stage has explicit validation
- Implementation-executor enforces tests + linting
- Spec-refiner ensures completeness before implementation

### 6. Learning Loop
- Retrospective-analyzer creates feedback loop
- Learnings can update subagent prompts
- Workflow improves over time

---

## Integration with Review-Flow MCP

The subagents complement the MCP server:

**MCP Server** (Python):
- Manages state (run_id, proposals, rankings, decisions)
- Orchestrates LLM providers (Gemini, Codex, Grok)
- Persists data (flat files, JSONL ledger)
- Provides tools/resources to Claude Code

**Subagents** (Claude Code):
- Execute specific stages (propose, evaluate, refine, implement)
- Access MCP tools within their scope
- Operate in fresh contexts (no token bloat)
- Can be invoked explicitly or automatically

**Example Flow**:
```
1. User: "Use proposal-generator to suggest approaches for [problem]"
   → Subagent reads codebase, generates 3 proposals
   → Saves proposals to research/review-runs/{run_id}/

2. MCP: rank_proposals(run_id)
   → Calls eval-MCP for tournament
   → Returns winner + confidence

3. User: "Use spec-refiner to validate winner"
   → Subagent validates tests/types/examples
   → Iterates until complete (max 5 rounds)

4. MCP: decide(run_id, decision='approve', proposal_id='...')
   → Creates ADR in research/review-runs/{run_id}/adr-*.md

5. User: "Use implementation-executor to implement ADR"
   → Subagent breaks into todos
   → Implements with tests + quality gates
   → Reports completion

6. User: "Use retrospective-analyzer to compare"
   → Subagent compares proposals vs actual
   → Extracts learnings for next cycle
```

---

## Next Steps

1. **Create `.claude/agents/` directory**
   ```bash
   mkdir -p .claude/agents
   ```

2. **Add subagent configurations** (5 markdown files)
   - proposal-generator.md
   - proposal-evaluator.md
   - spec-refiner.md
   - implementation-executor.md
   - retrospective-analyzer.md

3. **Test with simple task**
   - Pick small improvement
   - Run through full workflow
   - Validate subagents work as expected

4. **Iterate based on learnings**
   - Adjust prompts based on retrospective
   - Add/remove tool access as needed
   - Share configurations with team

---

## Open Questions

1. **Should proposal-generator call MCP directly?**
   - Pro: Automated proposal generation via MCP tools
   - Con: Adds complexity, may not be needed
   - Decision: Start explicit, automate if pattern emerges

2. **Should subagents share context?**
   - Pro: Less repetition (don't re-read codebase)
   - Con: Context bloat, defeats subagent purpose
   - Decision: Keep separate, optimize if becomes bottleneck

3. **How to handle failures?**
   - If subagent gets stuck, human intervenes
   - Max rounds prevent infinite loops
   - Log failures for retrospective analysis

4. **Version control for subagent evolution?**
   - Yes: `.claude/agents/` is version controlled
   - Track changes in git
   - Document learnings in retrospectives
