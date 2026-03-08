# Architecture Unification Plan

Status: Executed for current scope
Date: 2026-03-05

## Summary

The kernel is already the elegant part of this repo. The 3-op model, the
transaction pipeline, and the DB/session split are coherent and should stay
stable. The main complexity tax now lives above the kernel:

- The canonical runtime already lives in `shell.executor`, but DOM-to-intent
  adaptation previously leaked across `shell.editor`, extra shell adapters, and
  `components.block`.
- Keyboard policy is split across `keymap.bindings-data`,
  `shell.global-keyboard`, and `components.block/handle-keydown`.
- Selection is reducer-like, but most other session updates are assembled as
  ad hoc maps inside plugins.
- The scripts layer drifts from the real intent/session model.
- Docs and generated overviews disagree with actual repo entrypoints and build
  targets.

This plan does not redesign the kernel. It makes the composition layer smaller,
more explicit, and easier to reason about.

## Progress

Implemented on 2026-03-05:

- Phase 1 groundwork:
  - canonical docs now point at `AGENTS.md`
  - generated overviews are explicitly marked non-authoritative
  - `bb repomix` now exists
  - `bb docs:verify` resolves links relative to `docs/DX_INDEX.md`
- Phase 2 groundwork:
  - scripts now accept structural steps only
  - `scripts.editing` returns structural facts (`:target-id`, `:new-id`,
    `:new-ids`) instead of emitting session intents inside scripts
- Phase 6 groundwork:
  - plugin bootstrap now has an explicit `plugins.manifest` entrypoint used by
    `shell.editor`
- Phase 3 adapter boundary cleanup:
  - `shell.global-keyboard` now owns app-global keyboard handling
  - shared DOM adjacency logic moved to `utils.block-dom`
  - legacy Nexus compatibility code was removed from the repo
- Phase 4 session-update normalization:
  - `utils.intent-helpers` now owns shared selection/edit/cursor builders
  - navigation, structural, editing, page, and context-editing handlers now
    reuse those builders instead of hand-assembling most common session maps
  - `indent-selected` now deep-merges unfold + editing updates, with
    regression coverage
- Phase 5 docs/test canon:
  - `docs/KEYBOARD_OWNERSHIP.md` is now the keyboard ownership matrix
  - AGENTS, CLAUDE, DX index, runtime docs, testing docs, gotchas, and
    adapter/view tests now describe the shipped runtime path accurately
- Phase 7 file decomposition:
  - global keyboard logic moved out of `shell.editor`
  - adapter-only DOM helpers moved out of `components.block`
- Critical review follow-up:
  - `merge-session-updates` now recursively merges nested maps while keeping
    non-map leaves last-write-wins
  - Replicant dispatch now fails closed on stale non-function handler data
    instead of preserving vector-handler compatibility
  - `shell.global-keyboard` now ignores foreign editable targets instead of
    trusting stale edit session state
  - `Cmd/Ctrl+O` now only resolves from an active editor target and reads
    `:pending-buffer` text instead of stale DB text

Follow-on cleanup remains optional:

- deeper `components.block` decomposition if its local editing surface grows
- more aggressive session-update helper extraction for low-value one-off UI maps

## Decisions

### 1. Preserve the core kernel

Keep these as hard constraints:

- `:create-node`, `:place`, `:update-node` remain the only structural ops.
- `kernel.transaction` remains the only structural interpreter.
- Persistent document state stays in DB; ephemeral editing/selection/UI state
  stays outside the DB.
- Plugin handlers remain pure data transforms that return ops and/or
  `:session-updates`.

Reasoning:
The repo's strongest design property is the small instruction set and the
clear separation between persistent graph updates and ephemeral UI state. This
plan removes duplication around that center instead of adding a fourth layer.

### 2. Clarify adapter boundaries around the canonical runtime

Canonical path:

`DOM event -> shell-level adapter/context -> intent map -> executor/apply-intent! -> kernel.api/dispatch`

Implementation direction:

- `shell.executor` is already the canonical runtime entrypoint and stays that
  way.
- The cleanup target is adapter duplication around that runtime.
- Legacy Nexus compatibility code is removed instead of preserved.
- `shell.editor` should stop owning dispatch policy and return to composition,
  startup wiring, storage wiring, and render wiring.
- The same phase must update docs/tests so they describe the real runtime path.

Reasoning:
The duplication is not in structural execution. It is in how DOM context is
translated into intents before reaching the already-canonical runtime.

### 3. Make keyboard ownership explicit, not universal

Create one ownership table for keyboard behavior and only extract pure policy
where it is actually separable from DOM work.

Planned ownership rule:

- browser default owns native text editing behavior that does not require
  Evo-level policy
- `components.block` owns DOM-coupled editing behavior, IME guards, and cursor
  boundary reads
- shell/global handlers own app-level non-editing shortcuts
- pure helper functions may be extracted for decision logic once DOM ownership
  is explicit

Planned role boundaries:

- `keymap.core` stays the declarative key binding resolver.
- `components.block` keeps DOM measurement helpers and editing-local context.
- A shell-level adapter may exist for global keys, but it is not the owner of
  DOM-coupled editing behavior.
- The first deliverable is an ownership matrix per key family, not a wholesale
  move of editing behavior out of the component.

Reasoning:
The current "single source of truth" claim is false, but the fix is not to
force all editing logic into one shell namespace. The fix is to make ownership
explicit and then extract only the parts that are policy rather than DOM work.

### 4. Normalize session-updates the same way ops are normalized

Extend `utils.intent-helpers` into the shared helper layer for common result
shapes:

- selection result builders
- edit state entry/exit builders
- cursor-position builders
- common op bundles that pair structural changes with predictable
  `:session-updates`

Keep `plugins.selection` as the canonical reducer for selection state
transitions. Other plugins should stop hand-writing selection/editing/UI maps
where a shared builder or reducer already exists.

Explicit exception:

- `!buffer` remains a separate performance-sensitive mechanism until typing
  behavior is measured and a replacement proves it does not regress
  contenteditable performance.

Reasoning:
The repo already has one good example of disciplined ephemeral state
transitions: selection. The rest of session state should adopt the same
constraint instead of remaining free-form.

### 5. Narrow the scripts layer to structural composition only

Scripts should no longer compile session-sensitive intents against an empty
session.

Target rule:

- Scripts may simulate structural ops on a scratch DB.
- Scripts may return structural ops only.
- Any final focus/selection/edit-mode updates must be computed in the
  enclosing handler after the structural target is known.
- Stale intent dialects such as `:select` are not allowed in script outputs.
- Script dev/test paths must bind `intent/*validate-intents*` so stale intent
  names fail immediately instead of silently compiling.

Reasoning:
The scripts layer is useful for multi-step structural logic, but it becomes
misleading when it pretends to model session behavior without a real session.
Narrowing its responsibility is simpler than making it a second session
runtime.

### 6. Replace plugin startup side effects with an explicit manifest

Move plugin startup into an explicit bootstrap manifest.

Planned end state:

- one namespace for plugin registration/bootstrap
- `shell.editor` requires that manifest explicitly during startup
- startup does not rely on "require this namespace so its side effects happen"
  for plugin loading
- keymap bootstrap stays explicit through `bindings/reload!`; it is not part of
  this cleanup

Reasoning:
Plugin loading is still hidden in require lists. Keymap loading is already in a
better state and should not be re-abstracted.

### 7. Demote generated overviews; canonize real docs

Canonical docs:

- `README.md` for quick start
- `docs/DX_INDEX.md` for doc navigation
- `AGENTS.md` for agent repo guidance
- the new `docs/ARCHITECTURE_UNIFICATION_PLAN.md` for this initiative

Generated overviews should be treated as disposable summaries, not onboarding
truth, until their prompts are derived from actual repo inventory and config.

Reasoning:
The generated overview files are currently useful as rough orientation, but
they drift from `package.json`, `bb.edn`, `shadow-cljs.edn`, and the real repo
layout. They should not be part of the canonical truth chain.

## Execution Order

### Phase 1. Docs and tooling canon

Edits:

- Update agent/human docs so build targets and dev commands match actual repo
  config.
- Resolve the canonical-agent-guide naming mismatch across README, DX index,
  and agent docs.
- Explicitly mark generated overviews as non-authoritative until the generator
  is fixed.

Exit criteria:

- Canonical docs do not claim a non-existent build or command as the preferred
  path.
- Canonical docs do not point humans and agents at competing top-level guides.

### Phase 2. Script alignment

Edits:

- Remove stale `:select` script outputs.
- Restrict scripts to structural composition.
- Compute session effects outside script execution.
- Bind `intent/*validate-intents*` in script dev/test paths so stale intent
  names fail loudly.

Exit criteria:

- Scripts do not pretend to simulate session behavior.
- Stale intent names fail loudly in development.

### Phase 3. Adapter boundary cleanup

Edits:

- Remove adapter duplication around `shell.executor`.
- Delete dead compatibility adapters instead of preserving them.
- Remove dispatch policy from `shell.editor` that duplicates adapter logic.
- Update docs/tests in the same phase so they describe function-handler dispatch
  only.

Exit criteria:

- DOM-to-intent adaptation has one clear story across code, docs, and tests.
- `shell.executor` remains the canonical runtime center.

### Phase 4. Keyboard ownership matrix and pure policy extraction

Edits:

- Write an ownership matrix for the parity-sensitive key families:
  Enter/Shift+Enter, arrows, Shift+Arrow, Backspace/Delete, Cmd+A cycle.
- Keep DOM-coupled editing behavior in `components.block`.
- Extract pure helper functions only where decision logic can be separated from
  DOM reads and cursor mutation.

Exit criteria:

- Each high-value key family has one documented owner.
- Playwright coverage exists for each ownership boundary that matters to
  parity-sensitive behavior.

### Phase 5. Session-update normalization

Edits:

- Expand `utils.intent-helpers` with result builders for selection/editing/UI
  transitions.
- Replace repeated hand-built session maps in plugins with shared builders or
  reducer calls.
- Keep selection transitions centralized in `plugins.selection`.
- Leave `!buffer` separate unless profiling proves a safer unification path.

Exit criteria:

- Plugins no longer repeat large inline `:session-updates` maps when a builder
  exists.
- Selection/editing transitions follow consistent shape and naming.

### Phase 6. Explicit plugin manifest

Edits:

- Add an explicit registration/bootstrap namespace for plugins.
- Replace plugin side-effect requires as the primary startup pattern.

Exit criteria:

- Plugin composition is visible in one small startup surface.
- Keymap bootstrap remains explicit and unchanged.

### Phase 7. File splits after seam stabilization

Split only after phases 2-6 land. Otherwise the repo risks distributing the
same ambiguity across more files.

Expected split boundaries:

- `components.block`: rendering, editing handlers, autocomplete, drag/drop,
  component assembly
- `shell.view-state`: keep one state atom, split helpers by concern
  (editing, history, sidebar, notifications, quick switcher)
- `shell.editor`: startup, storage/bootstrap, global listeners, render wiring

Exit criteria:

- File boundaries reflect stable responsibilities rather than arbitrary LOC
  budgets.

## Validation

### Code and behavior checks

- `bb check`
- `bb test`
- `bb docs:verify`
- `rg -n "{:type :select\\b" src/scripts test/scripts docs AGENTS.md`
- targeted E2E coverage for keyboard/focus behaviors that move during the
  runtime and keyboard phases

### Review step

After this plan is written, run a narrow cross-model review against this
document. The review should:

- use provider-level `llmx` entrypoints so CLI is preferred but fallback is
  allowed
- review this plan doc plus the repo files cited here as evidence
- extract and disposition every concrete finding
- patch this plan if any verified finding materially improves the execution
  order, acceptance criteria, or locked architectural decisions

## Non-goals

- No new kernel primitive
- No protocol-heavy rewrite
- No async core redesign
- No attempt to make generated overviews canonical before their prompt/source
  drift is fixed
