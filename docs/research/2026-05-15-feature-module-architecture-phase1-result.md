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

### Methodology addendum — alternate denominators and intra-file churn

Cross-model review flagged that cross-file dispersion can mask intra-file
contention. Two follow-up numbers:

**Alternate denominator** — commits restricted to those that touch any
authoritative surface at all (the conditional risk subset GPT-5.5 asked for):

| Universe                                | Commits | 3+ surfaces |
| --------------------------------------- | ------- | ----------- |
| All commits                             | 864     | 3 (0.35 %)  |
| Touching at least one authoritative file| 215     | 3 (1.4 %)   |

Both fractions are small. The conclusion does not change.

**Per-file churn** — single-file churn the cross-file metric does not see:

| File                              | Commits, 6 mo |
| --------------------------------- | ------------- |
| `src/keymap/bindings_data.cljc`   | 22            |
| `resources/specs.edn`             | 17            |
| `src/keymap/core.cljc`            | 3             |
| `src/plugins/manifest.cljc`       | 3             |
| `src/shell/render_manifest.cljc`  | 1             |

`bindings_data.cljc` is the most-touched authoritative file at ≈one commit
per eight days. Whether this is "pain" depends on the failure mode: the
feature-module migration would have split `bindings_data` into per-feature
files. That would have reduced commits per file but added N files to the
codebase. Net is unclear and was not addressed by the original plan.

`specs.edn` churn is a separate workstream (FR registry maintenance) and is
not a candidate for the module unification — it has no register-keybinding
or register-intent counterpart.

The two numbers refine the picture without overturning the Phase 1
decision: the cross-surface friction the manifest was designed to remove
is genuinely small, and the largest single-file contention is in a file
the manifest would have *replaced*, not *consolidated*.

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
| Registry state | `bb lint:registry-state` | `scripts/lint_registry_state.clj` — static analysis with comment/string stripping: every keybinding's intent target exists, normalized key tuples unique per context, block-owned editing keys absent from `:editing`, render tags unique, modifier values are booleans, shell-bypass allowlist citations resolve to real dispatch sites |
| Registry state selftest | `bb lint:registry-state:selftest` | injects six violations (unknown intent, duplicate key tuple, block-owned Enter / ArrowLeft / Shift+ArrowRight in `:editing`, commented-out registration in a real plugin file) and confirms each category is caught |
| Shared ownership data | `src/keymap/ownership_data.cljc` | block-owned editing-key set lifted to a single source consumed by both the lint and `test/keymap/ownership_test.cljc` |
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

## Cross-model close review — verified findings

Ran `/critique close` against the initial Phase 1 commit. Cross-model
review (Gemini 3.1 Pro + GPT-5.5) returned 21 raw findings; verifier marked
6 CONFIRMED, 7 HALLUCINATED, 8 INCONCLUSIVE. After manual cross-check of
the HALLUCINATED bucket (the verifier flagged file paths with the wrong
extension — `.clj` instead of `.cljc` — so the semantic content of several
findings was actually real), the convergent findings worth acting on were:

1. **System/exit inside try/finally** (Gemini + GPT, CRITICAL, confirmed).
   JVM `Runtime.exit` does not run in-flight `finally` blocks — a failed
   selftest would leave the injected fixture in the working tree and break
   every subsequent `bb check`. Both selftests refactored to accumulate the
   failure inside the `try`, restore the fixture in `finally`, and call
   `System/exit` exactly once at the top level.
2. **Shift+ArrowLeft / Shift+ArrowRight missing from block-owned set**
   (Gemini + GPT, confirmed against CLAUDE.md keyboard ownership section).
   Editing-mode Shift+Arrow is contenteditable/browser-owned per
   `CLAUDE.md`, but the original set covered only the vertical pair. Added
   both, plus selftest cases for `ArrowLeft` and `Shift+ArrowRight` in
   `:editing`.
3. **Regex discovery matched docstring / commented examples** (cross-model,
   confirmed: `src/kernel/intent.cljc:74` carries a docstring example
   `(register-intent! :smart-split …)` that the regex would have happily
   counted as a real registration). Added a small comment / string
   stripper (`strip-noise`) before regex scanning, plus a multi-file
   selftest that appends a commented `register-intent!` to a real plugin
   file and asserts the lint rejects a keybinding pointing at it.
4. **Shell-bypass allowlist was unverified** (cross-model). Each entry
   in `shell-bypass-intents` now carries `{:file … :why …}` and the lint
   asserts the cited file exists and contains the intent keyword — if a
   future agent adds an entry without a real dispatch site, the lint
   fails.
5. **Block-owned set was duplicated** between the lint and
   `test/keymap/ownership_test.cljc` (Gemini, confirmed). Moved to a
   single shared CLJC namespace `src/keymap/ownership_data.cljc`; both
   the lint and the test now consume it.
6. **Non-boolean modifier values** would diverge between lint
   normalization and runtime `=` matching (GPT, confirmed by inspection
   of `src/keymap/core.cljc` `key-matches?`). Added a
   `check-modifier-types` pass that fails if any modifier value is
   non-boolean.
7. **Phase 0 denominator** (cross-model, methodology). Did not change
   the conclusion but the addendum above now records the conditional
   risk subset (3 / 215 = 1.4 %) and per-file churn (`bindings_data.cljc`
   leads at 22 commits / 6 mo, contained to one file).

Findings explicitly NOT acted on (with reason):

- **Register `:undo` / `:redo` through the normal intent pipeline**
  (Gemini, medium). This would be an architectural change to how the
  session log replays work. The current allowlist is now self-validating,
  so the lint can no longer drift silently. Deferred as a separate
  workstream; in scope for re-opening the manifest plan if the bypass
  pattern grows.
- **Replace regex discovery with full AST analysis** (cross-model).
  Comment / string stripping closes the realistic false-positive surface;
  full AST analysis would catch `(comment …)` macro forms but adds reader
  complexity for marginal gain. Documented limitation in the lint header.
- **Global-binding shadowing reachability check** (GPT). Theoretical —
  no current binding is shadowed in both contexts. Deferred.
- **Phase 0 / Phase 1 verdict was premature** (Gemini, principles). The
  intra-file-churn addendum addresses the methodology gap. The verdict
  stands.

## Touched files

- `bb.edn` — new tasks `lint:kernel-imports`, `lint:kernel-imports:selftest`,
  `lint:registry-state`, `lint:registry-state:selftest`; `check` runs the two
  new lints.
- `scripts/lint_registry_state.clj` — new; comment-stripped regex discovery,
  modifier-type check, self-validating shell-bypass allowlist.
- `scripts/lint_registry_state_selftest.clj` — new; six injected-violation
  cases covering each issue category.
- `scripts/verify_kernel_boundaries_selftest.clj` — new; `System/exit`
  outside the cleanup `try`/`finally`.
- `src/keymap/ownership_data.cljc` — new shared source of truth for
  block-owned editing keys.
- `test/keymap/ownership_test.cljc` — switched to consume
  `keymap.ownership-data`.
- `test/integration/registry_idempotency_test.cljc` — new.

<!-- knowledge-index
generated: 2026-05-15T06:01:03Z
hash: 1f9901b80f81


end-knowledge-index -->
