# Architectural Review: Simplify and Improve

You are reviewing the complete Evo codebase - an event-sourced UI kernel with declarative operations and generative AI tooling for building Logseq-like block editors.

## Context Provided

1. **Full codebase** (via repomix-output.xml) - All source code, tests, and documentation
2. **LOGSEQ_SPEC.md** - Ground truth behavioral specification from upstream Logseq
3. **VISION.md** - Project philosophy and architectural principles
4. **CLAUDE.md** - Development guidelines and patterns

## Your Task

Review the architecture from first principles and propose improvements focused on:

1. **Simplification** - Can we reduce complexity while maintaining correctness?
2. **Clarity** - Are the boundaries between kernel/plugins/shell clear and well-motivated?
3. **Debuggability** - Is the state machine easy to understand and trace?
4. **Correctness** - Does the design make it hard to create invalid states?

## Key Areas to Examine

- **Three-op kernel** (create, place, update) - Is this the right primitive set?
- **Intent → Operations pattern** - Does the plugin architecture scale well?
- **Derived indexes** - Are we computing the right things at the right time?
- **Session state** (editing, selection, cursor) - Is the unification working?
- **Replicant integration** - Is the view layer boundary clean?

## Evaluation Criteria

- Solo developer can understand and debug
- REPL-friendly (no hidden state)
- Immutable event sourcing throughout
- Clear separation of concerns
- Makes common tasks easy, complex tasks possible

## Important

**If the current design is already solid and elegant, say so.** We don't want to change unnecessarily. Only propose improvements if they genuinely simplify or strengthen the architecture.

Focus on structural improvements, not implementation details or syntax.
