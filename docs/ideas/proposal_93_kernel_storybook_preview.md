# Proposal 93 · Kernel Storybook Inspired by Zed's Component Preview

## Pain Point Today
- The only “demo” lives in `src/core/demo.clj:15-54`, a REPL script that prints text. There is no canonical catalog of transactions, derived state, or expected traces.
- Designers/agents must execute ad-hoc code to understand how the three-op kernel behaves, increasing onboarding time.

## Inspiration
- **Zed’s `ComponentPreview` workspace** (`/Users/alien/Projects/inspo-clones/zed/crates/zed/component_preview.rs:1-220`) builds a navigable catalog of UI components categorized by scope, filterable, and persistable.
- **Component layout helpers** (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component_layout.rs:1-160`) show how to compose reusable “example groups” with metadata and descriptions.

## Proposal
Create a `docs/storybook/kernel.clj` namespace that renders transaction stories into EDN (suitable for Clerk/Portal) using a structure patterned after Zed's component groups.

### Before
```clojure
;; src/core/demo.clj:18-31
(let [ops [{:op :create-node :id "doc1" ...}
           {:op :place :id "doc1" :under :doc :at :last}
           ...]
      result (interp/interpret db ops)]
  (println "Document children:" (get-in result [:db :children-by-parent "doc1"]))
  ...)
```

### After
```clojure
(ns docs.kernel.storybook
  (:require [core.db :as db]
            [core.interpret :as interp]
            [kernel.storybook :as sb]))

(def doc-skeleton
  (sb/example
    {:title "Document skeleton"
     :category :structure
     :ops [{:op :create-node :id "doc1" :type :document :props {:title "Doc"}}
           {:op :place :id "doc1" :under :doc :at :last}]}
    (fn []
      (let [result (interp/interpret (db/empty-db) (:ops sb/*context*))]
        {:db (:db result)
         :trace (:trace result)
         :issues (:issues result)})))

(def catalog
  (sb/example-group
    {:title "Canonical flows"}
    [doc-skeleton
     selection-cycle
     reference-roundtrip]))
```

`kernel.storybook` would provide `example`, `example-group`, and `render` helpers analogous to Zed’s `single_example` / `example_group`, emitting browsable EDN the dev tooling can hydrate.

## Payoff
- **Executable documentation**: every proposal/example lives beside assertions on resulting DB/trace.
- **Deterministic regression tests**: a single `(sb/run catalog)` can feed CI snapshots.
- **Agent-ready**: LLMs can query the catalog for “show me an update-node before/after”.

## Considerations
- Decide on viewer (Clerk, Portal, custom HTML). Keep runtime optional so the catalog remains pure data + functions.
- Persist story layout filters similar to Zed’s `ComponentPreviewDb` if the UI grows beyond a few examples.
