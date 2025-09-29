# Proposal 95 · Scope-Tagged Invariants Inspired by Zed's Component Scopes

## Pain Point Today
- Invariants, inspectors, and dev helpers live across namespaces with no taxonomy. Agents must skim entire files to find the right tool, and docs cannot surface “only layout-related invariants”.

## Inspiration
- Zed assigns every component a `ComponentScope` (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component.rs:303-344`) such as `Layout`, `Typography`, or `Agent`. The preview UI filters by scope effortlessly.
- Scopes feed into sorted previews (`component_registry.sorted_components()` in `/Users/alien/Projects/inspo-clones/zed/crates/component/src/component.rs:120-180`), improving discoverability.

## Proposal
Adopt scope tagging for kernel instrumentation:

```clojure
(def scopes
  {:structure "Tree / topology"
   :selection "Cursor & selection semantics"
   :effects   "Side-effect orchestration"})

(defmacro definvariant [name {:keys [scope doc]} & body]
  `(do
     (defn ~name [] ~@body)
     (swap! invariant-registry assoc '~name {:fn ~name
                                             :scope ~scope
                                             :doc ~doc})))
```

### Before
```clojure
;; Invariants scattered with no metadata
(defn check-reference-integrity [store] ...)
(defn track-watch-update [atom-key] ...)
```

### After
```clojure
(definvariant check-reference-integrity
  {:scope :structure
   :doc "Ensure reference graph never points at missing nodes"}
  (fn [store] ...))

(definvariant track-watch-update
  {:scope :effects
   :doc "Detect watch loops on frequently updated atoms"}
  (fn [atom-key] ...))

(defn list-invariants [scope]
  (->> @invariant-registry
       (filter (comp #{scope} :scope val))
       (map val)))
```

## Payoff
- **Precision tooling**: Storybook (Proposal 93) and help (Proposal 92) can expose `--scope selection` filters.
- **Better docs**: `docs/kernel_simplification_proposals.md` can group open questions by scope to highlight architectural hotspots.
- **Future adapters**: renderers can opt into only the invariants relevant to their domain (e.g., a text-only shell cares about `:structure`, not `:layout`).

## Considerations
- Keep scope vocabulary small and documented; align with existing MLIR layers (Intent, Core, View).
- Provide lint to ensure no scope-less invariants slip through.
