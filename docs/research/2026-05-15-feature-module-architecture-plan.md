# Feature Module Architecture Plan - 2026-05-15

Status: revised after adversarial review. This is a plan and recommendation,
not an implementation record.

Review artifact:
`.model-review/2026-05-15-adversarial-review-of-evo-feature-module-949300/`

## Executive Verdict

Do not implement the original all-in-one `src/modules/<feature>.cljc` proposal.
It is too likely to become fake unification: a second index over the existing
registries, with worse layer boundaries.

Also do not assume a resolved-manifest migration is necessary. The Claude pass
made the central objection sharper: most of the value may come from two cheap
guardrails that do not need a new declaration format.

Pursue in this order:

1. Layer-import lint: prove kernel-safe namespaces never require shell, keymap,
   render, or component namespaces.
2. Registry-state verifier: inspect the existing initialized registries and
   enforce keybinding/intent/derived/render consistency without moving rows.
3. Only if those two leave real pain, consider a resolved-manifest migration.

The module idea is only better than the current architecture if it is narrowed
to this:

1. Keep the current kernel and transaction model unchanged.
2. Treat "module" as a source-of-truth and verification contract, not a plugin
   framework.
3. Split module declarations by layer: kernel declarations cannot require shell,
   keymap, render, or component namespaces.
4. Compile layer-local declarations into one inspectable resolved manifest.
5. Accept a migration only when it deletes old manual entries in the same
   semantic change.

Current architecture beats the original proposal. Current architecture plus
cheap guardrails may also beat the revised manifest plan. A verifier-first,
layer-split manifest plan should proceed only if baseline data shows real
cross-surface maintenance cost that the existing registries cannot verify.

## Scope

- Target users: current personal/local Evo development, with public demo
  stability as a secondary constraint.
- Scale: about 12 plugin namespaces, 4 practical registry surfaces, 3 keymap
  contexts, roughly 50 FRs, and hundreds of tests/registered behaviors.
- Non-goal: no marketplace plugin system, dependency DAG, dynamic loading,
  lifecycle framework, protocol layer, or compatibility bridge.
- Success condition: fewer source-of-truth surfaces for a feature, with stronger
  mechanical checks than today.

## Current Before State

The runtime model is already good:

```text
DOM event
  -> intent map
  -> registered intent handler
  -> nil or {:ops [...] :session-updates {...}}
  -> transaction pipeline
  -> DB plus session patch
  -> render
```

The problem is not the kernel. The problem is that feature ownership is spread
across bootstrap and declaration surfaces.

| Concern | Current home |
| --- | --- |
| Intent handlers | `src/plugins/*.cljc`, via `kernel.intent/register-intent!` at namespace load |
| Intent bootstrap | `src/plugins/manifest.cljc`, side-effect requiring plugin namespaces |
| State allowance | `:allowed-states` inside each intent registration |
| Keybindings | `src/keymap/bindings_data.cljc` |
| Keymap bootstrap | `src/keymap/bindings.cljc` |
| Key ownership proof | `docs/KEYBOARD_OWNERSHIP.md` and `test/keymap/ownership_test.cljc` |
| Derived indexes | `kernel.derived-registry/register-derived!`, currently restored in `plugins.manifest` |
| Render handlers | `src/shell/render/*.cljc`, via `shell.render-registry/register-render!` at namespace load |
| Render bootstrap | `src/shell/render_manifest.cljc` |
| FR citations | `:fr/ids` on intent registrations plus `resources/specs.edn` scenarios |

Concrete example: folding currently has session-only intent handlers in
`src/plugins/folding.cljc`, keybindings in `src/keymap/bindings_data.cljc`, and
bootstrap through `src/plugins/manifest.cljc` plus `src/keymap/bindings.cljc`.

## What The Critique Rejected

The original plan proposed one feature descriptor like:

```clojure
{:id :folding
 :intents [...]
 :keybindings {...}
 :derived [...]
 :renders [...]
 :owned-key-families [...]}
```

That is too broad.

### Rejection Reasons

1. It mixes layers. A namespace containing intent handlers, keybindings, and
   render handler references can accidentally pull shell/component code into
   kernel tests.
2. It creates duplicate authority unless old `register-intent!`, keymap, and
   render side effects are removed immediately.
3. A separate `src/modules/<feature>.cljc` file can split wiring from
   implementation, making developers check more files rather than fewer.
4. `:owned-key-families` is a second ownership table. Ownership should be
   derived from normalized keybinding rows.
5. Runtime descriptors should not carry decorative metadata. FR links matter
   only if the verifier ties them to existing specs/tests.
6. Partial migration is a compatibility bridge in practice, even if not named
   one.

General idea critique: "feature modules" are not automatically better. They are
better only if they introduce a new enforceable invariant. The useful invariant
is not "every feature has a module file." The useful invariant is:

```text
For each migrated feature, every registered intent, shell-owned keybinding,
derived index, and render tag has exactly one declared owner, and no old manual
bootstrap surface still owns the same row.
```

## Revised After State

There are now two possible after states.

### After State A - Guardrails Only

No production architecture migration.

Add two checks against the current architecture:

1. `bb lint:kernel-imports` rejects forbidden layer imports.
2. `bb lint:registry-state` boots the current app, snapshots registries, and
   verifies:
   - every keybinding target intent exists;
   - normalized key identities are unique;
   - derived declarations emit exactly their declared keys;
   - render tags are unique;
   - repeated init does not duplicate keybindings or stale handlers.

This may close most of the gap with far less churn.

### After State B - Resolved Manifest, Only If Needed

Unify at the resolved manifest, not in one mixed namespace.

Layer-local declarations remain near the code they govern:

```text
src/plugins/folding.cljc          ; kernel-safe intent declarations and handlers
src/keymap/feature_bindings.cljc  ; shell-owned keybinding declarations by feature
src/plugins/backlinks_index.cljc  ; derived declaration near derived implementation
src/shell/render/*.cljc           ; render declarations near render handlers
src/system/manifest.cljc          ; explicit production assembly list
src/system/compile.cljc           ; pure manifest compiler and validation helpers
```

The production assembly produces one inspectable data value:

```clojure
{:intents
 {:toggle-fold {:feature :folding
                :allowed-states nil
                :handler plugins.folding/toggle-fold}}

 :keybindings
 {[ :global {:key ";" :mod true} :shell :any]
  {:feature :folding
   :intent {:type :toggle-fold}}}

 :derived
 {:backlinks {:feature :backlinks
              :keys #{:backlinks-by-page}
              :initial plugins.backlinks-index/compute-backlinks-index}}

 :renders
 {:page-ref {:feature :page-ref
             :handler shell.render.page-ref/render-page-ref}}}
```

Then a boring loader projects the resolved manifest into existing registries:

- `kernel.intent/register-intent!`
- `keymap.core/register!`
- `kernel.derived-registry/register-derived!`
- `shell.render-registry/register-render!`

The loader must be idempotent. Re-running it must not duplicate keybindings or
leave stale handler references.

Render migration is not part of the default plan. It should become in-scope only
if the baseline audit shows the render manifest is a real maintenance or
correctness problem.

## Before And After

### Folding Shortcut

Folding is a useful explanatory example, but it is not a sufficient pilot. It
is too centralized already, so a successful folding migration may prove only
that the easiest case can be renamed.

Before:

1. Define handler and call `register-intent!` in `src/plugins/folding.cljc`.
2. Add shortcut in `src/keymap/bindings_data.cljc`.
3. Ensure `src/plugins/manifest.cljc` side-effect requires the plugin.
4. Ensure `src/keymap/bindings.cljc` reloads the binding table.
5. Keep keyboard ownership docs/tests aligned by hand.

After:

1. Define named handler plus kernel-safe intent declaration in
   `src/plugins/folding.cljc`.
2. Define shell-owned folding keybinding rows in
   `src/keymap/feature_bindings.cljc`.
3. `src/system/compile.cljc` builds a resolved manifest.
4. `bb lint:modules` verifies intent existence, key ownership, and absence of
   stale old rows.
5. The loader registers the resolved rows into existing registries.

What changes:

- The keybinding row no longer lives in the global `bindings_data` table after
  folding migrates.
- The folding intent no longer self-registers at namespace load after folding
  migrates.
- Ownership is derived from the normalized keybinding row, not repeated in a
  separate `:owned-key-families` table.

### Render Handler

Before:

1. Create `src/shell/render/<tag>.cljc`.
2. Call `register-render!` at namespace load.
3. Add the namespace to `src/shell/render_manifest.cljc`.

After, only if render migration proves worthwhile:

1. Keep render implementation in `src/shell/render/<tag>.cljc`.
2. Export a render declaration from that namespace.
3. The resolved manifest proves every known parser tag has one render owner.
4. The old render manifest entry is deleted in the same change.

Render migration is lower priority than keymap/intent ownership. Do not move it
first.

### Derived Index

Before:

1. Implement derived computation.
2. Register with `kernel.derived-registry/register-derived!`.
3. Restore that registration in `plugins.manifest/init!` if tests clear the
   registry.

After:

1. Export a derived declaration near the derived implementation.
2. The resolved manifest declares exact emitted keys.
3. The verifier checks emitted keys against canonical fixture DBs.
4. Loader restores the derived registration idempotently.

## Module Contract

Use two declaration families, not one universal descriptor.

Kernel-safe intent declaration:

```clojure
{:feature :folding
 :intents
 [{:type :toggle-fold
   :doc "Toggle expand/collapse state for a block."
   :spec [:map [:type [:= :toggle-fold]] [:block-id :string]]
   :fr/ids #{:fr.fold/toggle-block}
   :allowed-states nil
   :handler toggle-fold}]}
```

Shell keybinding declaration:

```clojure
{:feature :folding
 :keybindings
 [{:context :global
   :key {:key ";" :mod true}
   :target :shell
   :state-family :any
   :intent {:type :toggle-fold}}]}
```

Derived declaration:

```clojure
{:feature :backlinks
 :derived
 [{:id :backlinks
   :keys #{:backlinks-by-page}
   :initial compute-backlinks-index}]}
```

Render declaration:

```clojure
{:feature :page-ref
 :renders
 [{:tag :page-ref
   :doc "Render a page reference token."
   :handler render-page-ref}]}
```

Normalized key identity:

```clojure
[context normalized-key normalized-mods target state-family]
```

Ownership uniqueness is checked over this tuple, not over physical key alone.

## Verification Contract

The verifier is the main value. Without it, this is file reshuffling.

First write verifier checks against the current architecture. Do not require a
new manifest format just to learn whether the current registries can answer the
important questions.

`bb lint:registry-state` should verify:

1. Every keybinding resolves to an existing intent.
2. Every normalized key identity has exactly one owner.
3. Block-owned contenteditable keys are absent from shell keybindings.
4. Derived declarations emit exactly their declared keys on canonical fixtures.
5. Render tags are unique.
6. Loader/init paths are idempotent: two consecutive init calls produce the same
   registry snapshot.

If a resolved manifest is later justified, `bb lint:modules` should verify:

1. Feature IDs are unique.
2. Intent types are unique across migrated declarations.
3. Registered migrated intents exactly equal the resolved manifest projection.
4. Every keybinding resolves to an existing intent.
5. Every normalized key identity has exactly one owner.
6. Block-owned contenteditable keys are absent from shell keybindings.
7. Derived declarations emit exactly their declared keys on canonical fixtures.
8. Render tags are unique.
9. Parser tag coverage is checked only against an explicit tag inventory or
   fixture corpus.
10. Cited FRs exist and are linked to at least one test/spec scenario, or are
    explicitly exempt.
11. For each migrated feature, old bootstrap/manual surfaces contain zero stale
    entries for the migrated rows.
12. Loader init is idempotent: two consecutive init calls produce the same
    registry snapshot.

Verifier output should include a source-of-truth reduction report:

```edn
{:feature :folding
 :old-authoritative-surfaces 4
 :new-authoritative-surfaces 2
 :duplicate-declarations 0
 :stale-old-rows []}
```

Accept a migration only if the feature cuts authoritative surfaces by at least
half and leaves zero duplicate declarations.

The stale-old-row check is a migration check, not permanent architecture. It can
be deleted once every row in a migrated surface has exactly one declaration path
and the old surface is gone.

## Migration Plan

### Phase 0 - Baseline Pain Audit

No architecture changes.

Produce two numbers before authorizing manifest work:

1. Last-six-month commits that touched at least 3 authoritative surfaces for one
   logical feature change.
2. Current files touched for a representative behavior change before and after
   the proposed architecture.

If the first number is small, the pain is probably theoretical and the resolved
manifest should be deferred.

Also produce a current-state report for one hard candidate feature, not folding:

- intent rows;
- keybinding rows;
- bootstrap rows;
- ownership rows/docs/tests;
- derived/render rows if applicable;
- current verification coverage.

Preferred candidate: page references/backlinks, because it plausibly crosses
parser/render/derived/navigation boundaries. If the audit shows render is not
actually part of the pain, keep render out of scope.

Decision gate:

- If current registries already answer most questions through snapshots, narrow
  the plan to guardrails and stop.
- If the report shows real source-of-truth spread, continue.

### Phase 1 - Independent Guardrails

Ship these as standalone improvements:

1. `bb lint:kernel-imports`
2. `bb lint:registry-state`

These do not require moving production behavior.

Acceptance:

- forbidden kernel import fixture fails;
- duplicate normalized key tuple fixture fails against current data;
- missing target intent fixture fails against current data;
- repeated init produces an identical registry snapshot;
- report names owners and exact tuples.

Decision gate:

- If these guardrails remove the actual risk, stop. Do not build modules.
- If they expose duplicate declarations or persistent cross-surface pain that
  cannot be solved against current registries, continue.

### Phase 2 - Hard Multi-Layer Pilot, Only If Needed

Do not use folding as the proof. Folding can be an audit fixture, but it is too
easy to validate the architecture.

Pick a feature that currently spans several surfaces. The current likely
candidate is page references/backlinks, subject to Phase 0 evidence.

Required deletions in the same change:

- migrated self-registration side effects in the owning plugin namespace;
- migrated key rows from `src/keymap/bindings_data.cljc`;
- any manual ownership rows that duplicate verifier output;
- stale bootstrap rows for the migrated feature.

Keep:

- kernel transaction pipeline unchanged;
- shell/component DOM ownership unchanged.

Acceptance:

- source-of-truth reduction ratio is at most 0.5;
- behavior-change file count drops on the pilot;
- `bb lint:modules` passes and catches injected conflicts for the pilot feature;
- existing feature tests pass;
- hot reload or repeated init does not duplicate keybindings;
- requiring kernel-safe declarations does not import `shell.*`,
  `components.*`, or `keymap.*`.

Decision gate:

- If the pilot becomes descriptor ceremony over the same code, revert it and
  keep the current architecture plus Phase 1 guardrails.
- If the pilot removes real duplicate authority, continue feature by feature.

### Phase 3 - Selection And Structural

Only after a hard multi-layer pilot passes.

Reason:

- selection and structural behavior are where key/state ownership matters most;
- movement shortcuts are high-value for normalized key ownership checks;
- regressions are easy to feel and test.

Acceptance:

- non-editing navigation/selection keys are declared and verified by feature;
- structural movement keys are declared and verified by feature;
- old global binding rows for migrated behavior are gone.

### Phase 4 - Editing Boundary

Do not move browser/contenteditable ownership into descriptors.

Allowed:

- shell-visible editing intents;
- shell-owned shortcut rows that already route through keymap;
- FR/test linkage for those rows.

Not allowed:

- moving `components.block` DOM-sensitive key handling into a module contract;
- abstracting cursor/selection DOM logic behind descriptor metadata.

### Phase 5 - Render, Only If It Deletes A Real Surface

Render migration is a default non-goal.

Do it only if Phase 0 proves render bootstrap is part of the actual maintenance
problem and an explicit parser tag inventory plus manifest deletion make the
system simpler. Otherwise keep the existing render registry and manifest.

### Phase 6 - Docs Cleanup

If the architecture survives pilots, update docs to facts:

- `docs/DX_INDEX.md` routes feature changes to declarations and verifier output;
- `docs/KEYBOARD_OWNERSHIP.md` becomes generated or shrinks to policy;
- migration narrative is removed or archived.

## Acceptance Gates

Baseline before any migration:

```bash
\bb lint:kernel-imports
\bb lint:registry-state
\bb test:kernel
\bb test:int
\bb test:view
\bb check
npm run lint:e2e-keyboard
npm run test:e2e:smoke
```

Per migrated phase:

```bash
\bb lint
\bb lint:kernel-imports
\bb lint:registry-state
\bb lint:modules
\bb test:kernel
\bb test
\bb check
npm run test:e2e:smoke
```

After structural/editing-adjacent changes:

```bash
\bb test:int
\bb test:view
\bb lint:fr-tests
npm run lint:e2e-keyboard
npm run test:e2e:smoke
```

## Kill Criteria

Stop the plan if any of these are true:

- Phase 1 guardrails answer the real questions without manifest migration;
- baseline pain audit shows few real cross-surface feature changes;
- behavior-change file count does not drop on the pilot;
- a migrated feature still has duplicate source-of-truth rows;
- kernel tests need shell/render/keymap imports;
- `src/system/manifest.cljc` becomes just another manual list without deleting
  old lists;
- key ownership still depends on hand-written mirror tables;
- hot reload or repeated init accumulates stale/duplicate handlers;
- folding migration touches broad unrelated behavior;
- the verifier cannot produce a concrete source-of-truth reduction report.

## Recommendation

The original module idea was too good to be true.

The durable recommendation is narrower:

1. Do a baseline pain and source-of-truth report first.
2. Ship import lint and registry-state verifier as standalone guardrails.
3. Stop there if they solve the real problem.
4. Try a hard multi-layer pilot only if the report proves current spread that
   existing registries cannot verify.
5. Continue only if the pilot deletes old rows, reduces authority by at least
   half, and reduces the number of files touched for a behavior change.

If the first pilot does not delete more than it adds, keep the current
architecture. The current system is already coherent; the bar is not aesthetic
unification, it is fewer facts to maintain and stronger failure modes.
