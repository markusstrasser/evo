# Nexus Action Pipeline & Testing Strategy

**Status:** Ready for implementation

**Date:** 2025-11-14

**Sources:** [`no.cjohansen/nexus`](~/Projects/best/nexus/Readme.md), `docs/REPLICANT.md`, `CLAUDE.md`, `dev/specs/LOGSEQ_EDITING_SELECTION_PARITY.md`

---

## 1. Goals

1. Replace ad hoc event dispatch (global keymap + per-component handlers) with a **single Nexus action pipeline**.
2. Make all side-effects (kernel intents, logging, analytics) data-driven and testable.
3. Provide a **repeatable testing strategy** that covers both pure actions and DOM outcomes.

---

## 2. Architecture Overview

```
DOM Event → Component handler → Nexus action(s) → Nexus dispatcher
           ↓                                    ↓
      (collect DOM facts)              Pure action reducers
                                        ↓
                                  Effect handlers
                                        ↓
                                Kernel intent dispatch
```

### 2.1 Key Concepts (per Nexus README)

| Concept      | Description                                                                         | Evo Mapping                                    |
|--------------|-------------------------------------------------------------------------------------|------------------------------------------------|
| **Action**   | `[:editing/navigate-up {:block-id …}]` – pure fn that returns effect list            | Supersedes direct `handle-intent` calls        |
| **Effect**   | Side-effect fn invoked with store + args                                            | Wrapper that dispatches kernel intents/logging |
| **Placeholder** | Late-bound DOM data (e.g. `:event.selection/start-offset`)                        | Lives next to Replicant handlers               |
| **System**   | Mutable store passed to Nexus (`!db` atom)                                          | Already exists in `shell/blocks_ui.cljs`       |

### 2.2 Required Wiring

1. `no.cjohansen/nexus` dependency already present (pinned to **2025.10.2** for placeholder interpolation fix).
2. Create `src/shell/nexus.cljs`:
   - Build a Nexus map with `:nexus/system->state deref` and registries for actions/effects/placeholders/interceptors (use `nexus.registry` in dev for convenience; compile a static map via `nexus.core` in prod if desired).
   - Export `dispatch!` that proxies to `nexus.core/dispatch` with the Nexus map, our `!db`, and Replicant dispatch data.
3. Replicant wiring (`shell/blocks_ui.cljs`):
   - Update `r/set-dispatch!` so DOM events route through `shell.nexus/dispatch!`. Life-cycle hooks must still fire.
   - Component event handlers emit Nexus actions instead of calling `handle-intent`.
4. Effects:
   - `:effects/dispatch-intent` → wraps `api/dispatch` (or temporary `handle-intent`) and logs validation issues.
   - `:effects/log-devtools` (dev only) → pushes `{action intent timestamp}` into `window.__nexusLog` via `nexus.action-log`.
5. Actions (initial set):
   - `:editing/navigate-up`, `:editing/navigate-down`
   - `:selection/extend-prev`, `:selection/extend-next`
   - `:editing/smart-split`, `:editing/escape`
   - Each action is a pure reducer returning e.g. `[[:effects/dispatch-intent {:type …}]]`.
6. Placeholders:
   - Register `:event.selection/start-offset`, `:event.selection/end-offset`, `:event.caret/row`, `:event.target/value`, etc., mirroring the Nexus README. Component handlers reference these placeholders instead of touching the DOM directly.
7. Dev observability:
   - Install `nexus.action-log` in dev builds and expose `window.__nexusLog`. Playwright helpers rely on this; tests must fail loudly if it’s missing.

---

## 3. Migration Plan

1. **Phase 1 – Bootstrapping**
   - Land `shell.nexus` and wire Replicant to it.
   - Provide a temporary `:effects/dispatch-intent` that still calls the existing `handle-intent` so we can migrate incrementally.

2. **Phase 2 – Editing/Navigation**
   - Migrate arrow/enter/escape flows to Nexus actions.
   - Ensure editing bindings are removed from `keymap/bindings_data.cljc`; the component/Nexus path must be the single source of truth.
   - Add Playwright assertions for “one action per key”.

3. **Phase 3 – Remaining behaviors**
   - Move formatting, smart-split variants, folding, zoom, etc. to Nexus.
   - Remove any lingering `on-intent` plumbing once no component calls it.

4. **Phase 4 – Observability**
   - Keep `window.__nexusLog` available in dev/test builds.
   - Optionally add a Nexus interceptor to dump actions/intents for Dataspex/DevTools.

---

## 4. Coding Rules & DX

- Components never call `api/dispatch` or `handle-intent`; they emit Nexus actions only.
- Actions are pure; effects are thin wrappers (usually `:effects/dispatch-intent`).
- DOM data enters via placeholders.
- Provide helpers (e.g., `dev/nexus.cljs`) to register common placeholders/effects during hot-reload.
- Update `docs/REPLICANT.md`, `CLAUDE.md`, onboarding docs to reference the Nexus pipeline; remove instructions that suggest adding raw keymap bindings for editing keys.

---

## 5. Testing Strategy

### 5.1 Unit (Clojure)
- Add suites like `test/nexus/editing_actions_test.cljc` and `test/plugins/selection_direction_tracking_test.cljc`.
- Feed reducers realistic DB snapshots via `kernel.db` + `kernel.query`.
- Assert action outputs (effect lists) and direction-tracking state.

### 5.2 Effects
- Test `:effects/dispatch-intent` and any batching logic.
- Use `with-redefs`/spies to ensure intents are dispatched once and errors bubble through.

### 5.3 Playwright / Integration
- Helpers: `awaitActionCount(page, n)` and `getLastActions(page)` should read `window.__nexusLog`; tests must fail if the log is unavailable.
- Scenarios:
  1. **Single dispatch per key** – each arrow/enter keypress produces exactly one action.
  2. **Boundary handoff** – at first/last row, Shift+Arrow switches from text selection to block selection.
  3. **Zoom/fold respect** – selection never escapes the current zoom root; folded blocks are skipped.
  4. **Direction tracking** – sequences of Shift+ArrowDown/Up expand/contract as per spec; log payloads include `:direction`.

### 5.4 Regression harness
- Add a Kaocha suite for Nexus reducers/effects.
- Ensure CI fails if Playwright can’t access `window.__nexusLog` (prevents silent instrumentation removal).

---

## 6. Open Questions
- `nexus.registry` vs static map? Recommendation: registry in dev, compiled map in prod (if build size matters).
- Cross-window sync needed? If yes, add an effect for BroadcastChannel/event-based forwarding later.

---

## 7. Acceptance Criteria
- All editing/navigation code paths emit Nexus actions only; no direct `handle-intent` calls remain.
- `window.__nexusLog` (dev) shows dispatched actions + resulting intents.
- Updated integration tests reflect incremental selection semantics.
- New unit + Playwright tests cover direction tracking, boundary handoff, and single-dispatch guarantees.
- Documentation references the Nexus pipeline; no legacy instructions suggest touching `handle-intent` or global keymap for editing keys.

