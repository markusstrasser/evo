# LESSONS.md

Thematic distillation from 1661 commits. Chronology lives in
[ANCESTRY.md](ANCESTRY.md); this doc extracts the rules.

## The dominant pattern: build → use → subtract

> Build complex thing → use it → realize the domain is simpler than
> the abstraction → delete.

Most of evo's best architectural commits are deletions. A
non-exhaustive roll call:

| Abstraction built | Replacement | Commits |
| --- | --- | --- |
| Fractional (Greenspan) ordering | Integer `:pos` + renumber-on-collision, then plain ordered vector in `:children-by-parent` | `2d04dc67` → `62888df3` → `46a2e88a` → `70195e5e` |
| DataScript kernel | Plain Clojure map | `9ca2f8e0` → `c2b9d880` |
| Five-op IR (`:patch/:place/:create/:move/:delete`) | Three-op IR (`:create-node/:place/:update-node`) | `48922610` → locked `6507c024` |
| Nexus dispatcher (3 months) | Direct function calls | introduced Nov 28 → `adc3a5b9` Mar 8 |
| `:update-ui` op | UI state out of op language | `6507c024` |
| Buffer plugin in persistent log | Ephemeral session atom | `fd6a5afe` |
| Controlled-editable engine | Uncontrolled DOM + `__lastAppliedCursorPos` | `2a482be5` → `76297bc1` |
| Visible-order index | Derived on demand in plugins | Nov 17 add → deprecate |
| Plugin manifest v1 | Simpler loader | Nov 20 → Dec 16 |

## Signal: you're on the third iteration of the same abstraction

Fractional indexing was rewritten three times before being replaced
— CLJC-safe fix (`82557c39`), canonical Greenspan algorithm
(`4cd31049`), then `62888df3` gave up and switched to integers.

If a patch commit fixes a *category* of bug (CLJC-safety, canonical
algorithm, tie-breaking) rather than an instance, the abstraction is
probably wrong. Next iteration: try deleting it.

## The replacement is usually dumber than "acceptable"

Each of these looked naive at the point of substitution. Each was
right:

- Plain map vs. DataScript
- Ordered vector vs. fractional string keys
- Direct function call vs. dispatch registry
- Integer position vs. CRDT-safe ordering

The productive question isn't "what's the best abstraction for X?"
It's "what's the dumbest representation that still satisfies the
invariants?"

## Counter-example: when defensive complexity legitimately stays

The 2026-04 MathJax incident produced three defensive layers —
`\bmath\b` regex anchor, `.math-ignore` class contract on every
content surface, and a `bb lint:rename-escape` linter (`0fb73022`,
`3b6fdcd7`, `7b74cc3f`). All three stayed.

The distinguishing feature: each layer guards a different failure
mode (global DOM mutation, opt-in class overlap, renamed-core-fn
leak), not the same one at different levels of polish. **Defensive
breadth on distinct failure modes is legitimate; defensive depth on
one failure mode is the smell.**

## Polish has a high revert rate

Three "polish" commits were reverted within 24 hours in a single
recent week:

- `cb0fbdbf` → `716a9ad4` — A/B toggle polish v1
- `dadf595f` → `82e2732c` — A/B toggle polish v2
- `f48848d5` → `98f42cdf` — editorial-flavor toggle

Already captured in auto-memory (`polish-skill-not-a-fit`). The
rule: appearance-only polish warrants a sleep cycle before merge.

## Subtraction's opposite: extraction

Specviewer (Apr 2026) is the rare *addition* — a new tool built
*from* the kernel, not inside it. That's what extraction mode
produces: independent artifacts that prove the kernel doesn't need
its own shell.

New code belongs here, or in the kernel itself. New code anywhere
else is probably the pattern above in disguise.
