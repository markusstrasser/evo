# Proposal 97 · Garden-Style Transaction Builders (`defintent`)

## Pain Point Today
- Test fixtures and demos manually assemble op maps (`src/core/demo.clj:18-34`). Reproducing complex flows requires verbose vectors and repeated `{:op ...}` maps.
- Lack of composable builders increases copy/paste and invites mistakes (e.g., forgetting to place after create).

## Inspiration
- Garden’s `defrule` macro builds reusable CSS rule constructors with minimal syntax (`/Users/alien/Projects/inspo-clones/garden/src/garden/def.clj:16-56`).
- Garden’s `rule` helper returns functions that accept children, enabling declarative composition (`/Users/alien/Projects/inspo-clones/garden/src/garden/stylesheet.cljc:15-45`).

## Proposal
Create `kernel.intent/defintent` to define composable transaction templates.

### Before
```clojure
[{:op :create-node :id "doc1" :type :document :props {...}}
 {:op :place :id "doc1" :under :doc :at :last}
 {:op :create-node :id "para1" :type :paragraph :props {...}}
 {:op :place :id "para1" :under "doc1" :at :first}]
```

### After
```clojure
(ns kernel.intent
  (:require [kernel.ops :as ops]))

(defmacro defintent [sym args & body]
  `(defn ~sym ~args
     (into [] (concat ~@body))))

(defintent doc-with-paragraph [doc-id para-id]
  [(ops/create doc-id :document {:title "Doc"})
   (ops/place doc-id :doc :last)
   (ops/create para-id :paragraph {:content "Hello"})
   (ops/under para-id doc-id :first)])

(defintent heading [id]
  [(ops/update id {:props {:style :heading}})])
```

Usage:
```clojure
(into [] (concat (doc-with-paragraph "doc1" "p1")
                 (heading "p1")))
```

## Payoff
- **Reusable grammar**: declarative builders compose intent bundles without repeating raw maps.
- **Readable tests**: fixtures read like user intents rather than mechanical ops.
- **Safer refactors**: renaming an op or changing defaults happens in one place.

## Considerations
- Builders should stay pure data (vectors); keep helpers returning map literals to guarantee determinism.
- Provide convenience fns in `kernel.ops` (e.g., `create`, `place`, `under`) that emit canonical op maps, so `defintent` stays concise.
