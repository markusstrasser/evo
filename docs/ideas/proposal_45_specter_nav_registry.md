# Proposal 45 · Specter-Style Navigators for Kernel Lenses

## Problem
- `src/kernel/lens.cljc:16-115` duplicates navigation boilerplate (`children-of`, `parent-of`, `prev-id`, `next-id`, etc.). Each helper hard-codes `get-in` paths and manual branching.
- Planner code and sanity checks must remember which helper to call for each relationship; composing navigations (e.g. "prev sibling of parent") inflates helper count or falls back to raw `get-in` calls again.
- Maintaining derived lookups requires touching several functions whenever `:derived` keys are renamed or extended.

## Inspiration
- **Specter navigators** compile navigation paths into specialized functions while supporting inline caching and composition (`/Users/alien/Projects/inspo-clones/specter/src/clj/com/rpl/specter/macros.clj:1-48`). Libraries such as Specter expose `defnav` so callers compose domain-specific traversals declaratively without repeated plumbing.

## Proposed change
1. Introduce `kernel.nav` namespace with a tiny wrapper macro (`defnav+`) that mirrors Specter’s `defnav`, emitting specialized `select*`/`transform*` helpers.
2. Re-express existing lens helpers as composable navigators, e.g. `children-nav`, `parent-nav`, `prev-sibling-nav`, and expose precompiled selectors (`children-of`, `prev-id`, …) for ergonomics.
3. Extend `kernel.lens` to provide only higher-level wrappers that reuse these navigators, shrinking duplicate index math.

```clojure
;; before (src/kernel/lens.cljc)
(defn prev-id [db node-id]
  (let [siblings-vec (siblings db node-id)
        idx (index-of db node-id)]
    (when (and (>= idx 0) (> idx 0))
      (nth siblings-vec (dec idx) nil))))

;; after (sketch)
(ns kernel.nav
  (:require [kernel.nav.macros :refer [defnav+]]))

(defnav+ siblings-nav [db]
  {:select* (fn [_ id next-fn]
              (next-fn (get-in db [:derived :child-ids-of id] [])))})

(def prev-sibling
  (comp siblings-nav
        (nav/indexed dec)
        nav/node))

(defn prev-id [db id]
  (nav/select-one prev-sibling db id))
```
- `nav/indexed` and `nav/node` are thin wrappers built on the same macro; composing them replaces hand-written loops.

## Expected benefits
- Cuts repetition across navigation helpers; new traversals are composed rather than hard-coded, reducing chance of inconsistent edge handling.
- Inline caching from the generated helper functions can remove redundant `get-in` allocations in hot loops.
- Future planners can build ad hoc navigation pipelines (e.g. "first non-leaf ancestor") using the same building blocks, improving LLM ergonomics.

## Trade-offs
- Adds a small macro layer; we’ll need clj-kondo hints/tests to keep generated code transparent.
- Contributors must learn navigator composition. Mitigate with REPL utilities (`nav/preview path db id`) and documentation in `docs/`.

## Migration sketch
1. Ship `kernel.nav.macros/defnav+` (thin wrapper over Specter’s expansion adapted to our data model).
2. Incrementally port existing helpers; keep the public API (`lens/prev-id`, etc.) for stability.
3. Add property tests comparing old and new helpers before deleting legacy implementations.
