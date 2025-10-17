# Project Constraints for Architect

This file defines the design goals, constraints, and evaluation criteria for architectural decisions in this project. The architect skill uses these to generate and evaluate proposals.

## Context

Solo developer building a REPL-driven ClojureScript UI framework (Replicant). The focus is on simplicity, debuggability, and explicit control over clever abstractions or automation.

## MUST Requirements (Hard Constraints)

- Focus on simplicity over cleverness
- Debuggability is critical (observable state, clear errors)
- Solutions must be REPL-friendly (easy to test interactively)
- Avoid hidden automation or complex orchestration
- Maintainable by a solo developer who "can barely keep track of things"

## SHOULD Preferences (Soft Constraints)

- Prefer explicit over implicit
- Minimize dependencies
- Document tradeoffs clearly
- Pure functions and explicit data flow where possible
- Synchronous patterns unless async explicitly justified

## Evaluation Criteria (in priority order)

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

5. **Quality Gates**
   - Bad specs caught before implementation
   - Validation happens early
   - Clear go/no-go criteria

## Red Flags (MUST reject if any)

- Infinite refinement loops (no escape hatch)
- Hidden automation (magic that can't be inspected)
- Complex orchestration (hard to debug when stuck)
- Tight coupling (can't run stages independently)
- Over-engineering (10+ agents, dynamic planning, meta-workflows)

## Notes

- This file can be customized per project
- Place in `.architect/project-constraints.md` at project root
- Can be overridden via `--constraints <file>` CLI flag
- If missing, architect will use minimal defaults or prompt for constraints
