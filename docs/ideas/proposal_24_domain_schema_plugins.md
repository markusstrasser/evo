# Proposal 24 · Domain Schema Plugins (Slate)

## Why we need this
- Evolver’s core schema (`src/kernel/schemas.cljc`) covers generic trees, but domain clients (Slate rich text, Figma layout, VR scene graphs) require additional invariants (e.g., lists only contain list-items, autolayout frames keep constraints). Today those rules live in downstream adapters.

## Reference implementations
- **Slate normalizers** (`slate/packages/slate/src/core/normalize-node.ts:1-120`) plug domain rules into the editor by overriding `normalizeNode`. Slate applies plugins in order, each transforming/validating nodes opportunistically.
- **Slate “with” plugins** (e.g., `slate/packages/slate/src/editor/normalize.ts:61-110`) show how operations pass through plugin hooks like `transform` and `normalize`, mirroring what we need before Evolver executes operations.
- **Pathom3 plugin ordering** (see Proposal 1 references) offers a reusable registry model for dependency-aware execution. We can reuse similar compile-time ordering to avoid conflicting domain plugins.

## Proposed API
```clojure
(defprotocol DomainPlugin
  (op-pre [plugin ctx op])    ;; transform op before validation/application
  (op-post [plugin ctx op db]) ;; inspect result, add hints/effects
  (normalize-db [plugin ctx db]))

(defn register-plugin!
  [{:keys [id before after fn]}]
  (swap! registry add-plugin {:id id :before before :after after :plugin fn}))

(defn apply-plugins [ctx hook & args]
  (reduce
    (fn [state {:keys [plugin]}]
      (if-let [f (hook plugin)]
        (apply f state args)
        state))
    ctx
    (:ordered @registry)))
```

- Hooks integrate with the transaction pipeline (Proposal 7): `:prepare` runs `op-pre`, `:apply` runs `op-post`, and a dedicated stage runs `normalize-db` after each op or batch.
- Plugins declare ordering constraints (like Slate’s `withReact` running before custom normalizers). We reuse the compile/validate logic from Pathom’s plugin registry.

## Example plugins
- **Slate text**: ensure block nodes always have text children (copy of Slate’s rule in `normalize-node.ts:17-78`).
- **Figma frames**: enforce autolayout metadata before placement, derived from Figma docs.
- **VR scene graph**: guarantee transform nodes provide quaternion + position combos.

## Trade-offs & mitigations
- Ordering conflicts become tricky; provide diagnostics (`ex-info` includes plugin graph) and optional visualization (dot graph).
- Performance: plugin hooks run for every op. Offer coarse toggles (`:plugins/off #{:normalize-db}`) for performance-critical contexts.
- State leakage: plugins must stay pure. Enforce this via type hints (return new context) and tests.

## Next steps
1. Implement `kernel/domain_plugins.cljc` with registration, ordering, and hook dispatch (leveraging Proposal 1’s registry utilities).
2. Port a concrete Slate normalizer sample into a plugin to prove the API.
3. Update docs to teach downstream teams how to add domain plugins, including trade-offs and sample tests.
