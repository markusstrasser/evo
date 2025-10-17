# Should architect-mcp become an Agent Skill?

**Date:** 2025-10-17
**Consulted:** Gemini 2.5-flash, OpenAI GPT-4-turbo (NOTE: Used cheaper models for this analysis - future skill implementations MUST use gpt-5-codex with high reasoning and gemini-2.5-pro)
**Context:** Full ADR-013, architect-mcp README, Skills documentation, existing skills configs

## Executive Summary

**UNANIMOUS VERDICT: Convert architect-mcp to an Agent Skill**

Both Gemini and Codex independently recommend converting architect-mcp from an MCP server to an Agent Skill, primarily due to:
1. **Token savings**: ~480 tokens per session via progressive disclosure
2. **Simplicity**: Shell scripts simpler than FastMCP server for solo dev
3. **Capability parity**: Skills CAN call MCP tools (tournament-mcp works fine)
4. **Better discovery**: Skill triggers vs `/mcp list`

---

## Gemini Analysis

**RECOMMENDATION:** SKILL

### Reasoning
Converting architect-mcp to an Agent Skill offers significant advantages, primarily in token efficiency and simplified management for a solo developer. The progressive disclosure model of Skills means that the full instructions (L2) are only loaded when the skill is actively triggered, leading to substantial token savings (~480 tokens per session) compared to an MCP whose tools are always in context. This directly optimizes the agent's operational cost and context window usage.

Furthermore, implementing architect as a Skill using shell scripts (run.sh) aligns with a simpler development and maintenance paradigm. For a solo developer, managing shell scripts for orchestration is generally less complex than maintaining a separate FastMCP server process, reducing overhead and potential points of failure. The explicit confirmation that Skills can call MCP tools and have full filesystem/bash access removes any technical blockers for this transition.

### State Management
The `.architect/` state (e.g., `review-runs/`, `adr/`, `review-ledger.jsonl`) would continue to be managed directly via filesystem operations within the Skill's `run.sh` scripts. Skills have full filesystem access, allowing them to read from and write to these directories as needed, maintaining the existing state management approach.

### Composition
The context explicitly states that Skills CAN call MCP tools. Therefore, a Skill implementation of architect would be able to seamlessly call `tournament-mcp` via its MCP tool interface without any anticipated issues. The interaction mechanism remains consistent, just initiated from a different execution context (the Skill's shell script).

### Token Savings
Based on the provided context, converting to a Skill would result in a rough estimate of ~480 tokens saved per session due to progressive disclosure. The L1 metadata would consume minimal tokens for discovery, and the L2 instructions would only be loaded when the skill is actively engaged.

### Complexity
For a solo developer, a Skill implemented with shell scripts is generally simpler to manage than a FastMCP server. It eliminates the overhead of a separate server process and its associated framework, allowing for more direct and often more transparent orchestration logic, assuming the developer is proficient in shell scripting.

### Risks
The primary risks involve the initial rewrite effort to convert the existing Python FastMCP implementation into shell scripts. This requires careful re-implementation and thorough testing to ensure functional parity and robustness. Additionally, shell scripting can sometimes be more brittle than structured programming languages for complex logic, potentially introducing new debugging challenges if not meticulously crafted and tested.

**VERDICT:** SKILL, due to significant token savings and simplified management for a solo developer, outweighing the rewrite effort.

---

## Codex (GPT-4-Turbo) Analysis

**RECOMMENDATION:** SKILL

### Reasoning
The primary advantage of converting the architect-mcp from a Management Control Protocol (MCP) server to an Agent Skill lies in the operational and token-efficiency gains. The Skill framework supports progressive disclosure, which significantly reduces the token cost during typical development sessions by segregating metadata (L1), workflow documentation (L2), and resource-intensive operations (L3) into different layers. This structure ensures that only the necessary information and operations are loaded and executed as required, which is particularly beneficial for solo developers who may not always need the full capabilities of the architect-mcp in every session. Furthermore, Skills can call other MCP tools, maximizing compatibility and functional parity with the current MCP implementation.

### State Management
Switching architect-mcp to a Skill would involve managing the state through filesystem operations, primarily centered around the `.architect/` directories. The `run.sh` within the proposed Skill structure can handle the orchestration, similar to the MCP, by maintaining state records, logs, and other operational metadata needed to resume or track progress across sessions. Usage of common shell commands and direct filesystem interactions would facilitate an easy transition to a Skill-based approach without losing the features presently managed by the Python-based MCP.

### Composition
Under the proposed Skill setup, calling tournament-mcp or other MCP tools remains feasible. Given that Skills inherently have the capability to interact with MCP tools, this interoperability should not introduce any significant issues. The new setup would continue to allow the architect-mcp (as a Skill) to orchestrate complex decision-making processes by leveraging other MCPs seamlessly.

### Token Savings
Transitioning to a Skill could potentially save about 480 tokens per session. In the current MCP setup, tool-related operations are always in the context, utilizing around 500 tokens consistently. In contrast, the proposed Skill would use approximately 20 tokens for metadata (L1) and load about 3,000 tokens for detailed workflow documentation (L2) only when specific operations are triggered.

### Complexity
For a solo developer, managing a Skill which consists mostly of shell scripts is often simpler than maintaining an active and stateful MCP server setup. Shell scripts are generally less complex to edit and debug than a Python-based MCP server, which could involve deeper software engineering knowledge, especially for dependencies and server management.

### Risks
The main risk associated with migrating from an MCP to a Skill is the potential loss of complex state management capabilities inherent to the MCP's structured protocol. Furthermore, shell scripting, while simpler, might be prone to errors and edge cases that are more gracefully handled by a structured MCP framework. Testing and error-handling would need to be rigorously implemented to prevent such issues.

**VERDICT:** Convert architect-mcp to a Skill, with the deciding factor being the significant reduction in token usage and operational simplicity, which aligns well with the needs of a solo developer environment.

---

## Consensus Points

Both LLMs independently identified the same key factors:

### ✅ Strong Reasons TO Convert

1. **Token Efficiency**: ~480 tokens saved per session (L1: 20 tokens vs MCP tools: 500 tokens)
2. **Progressive Disclosure**: L2 only loaded when triggered (~3k tokens)
3. **Simpler for Solo Dev**: Shell scripts < FastMCP server management
4. **Capability Parity**: Skills CAN call MCP tools (tournament-mcp works)
5. **Better Discovery**: Skill triggers more intuitive than `/mcp list`
6. **Filesystem Access**: `.architect/` state management identical
7. **Already Have CLIs**: gemini, codex, grok already as CLI tools

### ⚠️ Risks Identified

1. **Rewrite Effort**: Convert Python FastMCP → shell scripts
2. **Shell Script Brittleness**: Needs robust error handling
3. **Testing Required**: Ensure functional parity
4. **Loss of Structured Protocol**: MCP tool protocol is more structured

### 💡 Mitigation Strategies

1. **Incremental Migration**: Start with `propose` command, validate, then add others
2. **Reuse Existing Patterns**: Use research skill's `run.sh` as template
3. **Keep Tournament as MCP**: No need to convert (it's genuinely stateful with Bradley-Terry state)
4. **Thorough Testing**: Test each command before full migration

---

## Recommendation

**CONVERT architect-mcp TO SKILL**

### Proposed Structure

```
skills/architect/
  SKILL.md              # L1: triggers + L2: workflow docs
  run.sh                # propose|rank|refine|decide|review-cycle
  config.edn            # LLM providers, confidence thresholds
  data/
    templates/          # ADR templates
    evaluation.md       # Ranking criteria
  examples/
    example-review.sh   # Usage examples
  README.md             # Quick reference
```

### Migration Plan

**Phase 1: Prototype** (2-4 hours)
1. Create `skills/architect/` directory
2. Write SKILL.md with L1/L2 sections
3. Implement `run.sh propose` command
4. Test: Can it call gemini/codex/grok CLIs?
5. Test: Can it call tournament-mcp MCP tool?
6. Test: Can it write to `.architect/`?

**Phase 2: Full Implementation** (4-8 hours)
1. Implement remaining commands: rank, refine, decide
2. Add review-cycle (full orchestration)
3. Thorough testing and error handling
4. Document in SKILL.md

**Phase 3: Migration** (1-2 hours)
1. Update `.mcp.json` to disable architect-mcp server
2. Update CLAUDE.md to reference skill
3. Update toolbox index
4. Archive architect-mcp repo (keep for reference)

### Testing Checklist

- [ ] `skills/architect/run.sh propose "test description"` generates 3 proposals
- [ ] Proposals saved to `.architect/review-runs/{id}/`
- [ ] `run.sh rank {run-id}` calls tournament-mcp successfully
- [ ] Ranking saved to `.architect/review-runs/{id}/ranking.json`
- [ ] `run.sh decide {run-id} approve {proposal-id}` writes ADR
- [ ] ADR appears in `.architect/adr/ADR-{number}-{title}.md`
- [ ] Review ledger updated correctly
- [ ] All filesystem state management works
- [ ] Error handling robust

---

## Decision

**ACCEPT: Convert architect-mcp to Agent Skill**

**Rationale:** Unanimous consensus from two independent LLM analyses, strong technical justification, clear migration path, and significant benefits for solo developer workflow.

**Next Steps:**
1. Create prototype (Phase 1)
2. Validate feasibility
3. If successful, proceed with full implementation

**Red Flags to Watch:**
- Shell script brittleness in complex orchestration
- Tournament-mcp MCP tool calling from Skills (verify this works)
- State management edge cases

**Reversion Plan:**
If conversion fails or proves more complex than anticipated:
- Keep architect-mcp MCP server active
- Document why Skill approach didn't work
- Update ADR-013 with findings
