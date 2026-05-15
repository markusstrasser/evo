# Feature Module Architecture — Phase 0 + Phase 1 Result

Companion to `2026-05-15-feature-module-architecture-plan.md`.
Records what the verifier-first execution actually found and applies the
plan's own kill criteria.

## Phase 0 — baseline pain audit

Script: `scripts/phase0_audit.clj` (one-shot, not retained — query reproduced
below for re-derivation).

```bash
git log --since="6 months ago" --name-only --pretty=format:"=== %h %s"
```

Authoritative surfaces counted per the plan's table:
intent handler / intent bootstrap / keybindings / keymap bootstrap / keymap
ownership doc / keymap ownership test / derived registry / render handlers /
render bootstrap / spec FRs.

| Window         | Commits | Touching 3+ surfaces | Touching exactly 2 |
| -------------- | ------- | -------------------- | ------------------ |
| Last 6 months  | 864     | 3                    | 19                 |

The three 3-surface commits:

- `56a83972 [proofs] Add checked guardrails — expose remaining gaps` — touches
  the ownership test, the keybindings table, and other keymap code. This is
  a guardrail commit, not a feature change, so the multi-surface touch is
  intrinsic, not friction.
- `b9ce70a6 [kernel] Enforce derived ownership — fail on collisions` — touches
  intent handler, intent bootstrap, derived registry. Again a kernel
  enforcement commit, not user-feature friction.
- `8c5686ca [kernel] Phase D — derived-plugin protocol surface (map-based)` —
  same shape.

The 19 two-surface commits are almost entirely `intent-handler + keybindings`
— the normal pattern for "add a new intent and bind a key to it." That is the
intended workflow under the current architecture, not a smell.

## Plan's kill criteria, applied

> If the first number is small, the pain is probably theoretical and the
> resolved manifest should be deferred.

3 / 864 = 0.35 %. The number is small. None of the three are feature changes.

> If current registries already answer most questions through snapshots,
> narrow the plan to guardrails and stop.

Phase 1 shipped two static verifiers plus an integration idempotency test. All
of them passed on the current architecture without any source-of-truth moves.
The current registries do answer the important questions.

## Phase 1 — guardrails shipped

| Surface | Command | Backing script |
| --- | --- | --- |
| Kernel imports | `bb lint:kernel-imports` | `scripts/verify_kernel_boundaries.clj` (existed; now wired under a friendlier task name and added to `bb check`) |
| Kernel imports selftest | `bb lint:kernel-imports:selftest` | injects a forbidden `[shell.executor]` require under `src/kernel/`, confirms the lint rejects it, restores the tree |
| Registry state | `bb lint:registry-state` | `scripts/lint_registry_state.clj` — static analysis: every keybinding's intent target exists, normalized key tuples unique per context, block-owned editing keys absent from `:editing`, render tags unique |
| Registry state selftest | `bb lint:registry-state:selftest` | injects three violations (unknown intent, duplicate key tuple, block-owned editing key) and confirms each category is caught |
| Idempotent init | `test/integration/registry_idempotency_test.cljc` (runs under `bb test:int`) | calls `plugins.manifest/init!`, `shell.render-manifest/init!`, and `keymap.bindings/reload!` twice each, asserts byte-identical registry snapshots |

`bb check` now runs `lint:registry-state` and `lint:kernel-imports` alongside
the existing lints.

### Pre-existing findings the lint surfaced

`:undo` and `:redo` keybindings dispatch through
`shell.global_keyboard.cljs` directly into the session log replay
(`slog/undo!` / `slog/redo!`), bypassing `register-intent!`. They are
allowlisted in the verifier with a citation to the dispatch site. This is
documented architectural state, not a finding to fix — the carve-out comment
exists so the next person to ask "why are these missing from the intent
registry?" can find the answer at the lint site.

No duplicate keys, no block-owned key leaks into `:editing`, no unknown
intent targets, no duplicate render tags. The state file is honest.

## Decision gate

**Stop here.** Per the plan's Phase 1 decision gate, do not proceed to the
resolved-manifest migration (Phase 2 / Phase 3 / Phase 5).

Reasons:

1. Phase 0 produced ~zero genuine cross-surface feature commits in 6 months.
2. Phase 1 guardrails pass on the current architecture without any
   restructuring — the existing registries are answering the questions the
   manifest was meant to enable.
3. The verifier's selftest proves the lints would catch the failure modes
   the manifest was meant to prevent (unknown intent targets, duplicate keys,
   block-owned key collisions, non-idempotent init).
4. The plan explicitly flagged folding as too easy to be a real pilot and
   listed "Phase 1 guardrails answer the real questions without manifest
   migration" as a top-line kill criterion. Both apply.

## What would re-open the plan

The plan should re-open if any of the following appear in a future audit:

- A future 6-month window shows ≥10 commits touching 3+ authoritative surfaces
  for a single logical feature change (≈1 / month sustained).
- A bug is shipped because the current registries cannot answer a question
  that a resolved manifest would (e.g., a parser tag with no render owner
  that escaped review).
- A new authoritative surface gets added — at that point the spread genuinely
  expands and unification might pay back.
- The `:undo` / `:redo` shell-bypass list grows past a couple of intents,
  signalling that the bypass pattern is becoming structural rather than
  exceptional.

Until then, "feature module" is a solution to a problem the architecture does
not have.

## Touched files

- `bb.edn` — new tasks `lint:kernel-imports`, `lint:kernel-imports:selftest`,
  `lint:registry-state`, `lint:registry-state:selftest`; `check` runs the two
  new lints.
- `scripts/lint_registry_state.clj` — new.
- `scripts/lint_registry_state_selftest.clj` — new.
- `scripts/verify_kernel_boundaries_selftest.clj` — new.
- `test/integration/registry_idempotency_test.cljc` — new.

<!-- knowledge-index
generated: 2026-05-15T05:46:30Z
hash: 68145f94a07d


end-knowledge-index -->
