# Proposal 100 · Datascript-Style CLJC Helpers to Kill `#?` Boilerplate

## Pain Point Today
- `src/agent/core.cljc` interleaves `#?(:cljs ...)` and `#?(:clj ...)` branches throughout `detect-environment`, tracing, and error handling. The branching duplicates logic and obscures intent.

## Inspiration
- Datascript defines `defn+` and `defrecord-updatable` macros to write cross-platform functions once while still giving CLJ/CLJS-specific optimisations (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/db.cljc:33-125`).
- The macros patch metadata and expansion so ClojureScript gets direct invokes without warnings.

## Proposal
Introduce a small `kernel.cljc.macros` namespace with utility macros:

```clojure
(defmacro defn+ [name & fdecl]
  `(do
     (defn ~name ~@fdecl))) ; extend later with platform-specific tweaks

(defmacro try-js [body cljs-fallback]
  `#?(:cljs (try ~@body (catch :default _# ~cljs-fallback))
     :clj (try ~@body (catch Exception _# ~cljs-fallback))))
```

### Before
```clojure
(defn detect-environment []
  {:browser? #?(:cljs (try (boolean js/window) (catch :default _ false))
                :clj false)
   :node?    #?(:cljs (try (boolean js/process) (catch :default _ false))
                :clj (try (boolean (resolve 'clojure.java.shell/sh)) (catch Exception _ false)))
   ...})
```

### After
```clojure
(defn+ detect-environment []
  {:browser? (try-js [(boolean js/window)] false)
   :node?    (try-js [(boolean js/process)]
                     (boolean (resolve 'clojure.java.shell/sh)))
   ...})
```

## Payoff
- **Readable CLJC**: shared logic stays linear; platform differences live in reusable helpers.
- **Less duplication**: tracing, time, and logging code can share macros for timestamps and printing.
- **Optimised output**: macros can emit CLJS-friendly metadata (à la Datascript) if perf becomes a concern.

## Considerations
- Keep helper macros tiny and documented; avoid over-abstracting real platform differences.
- Ensure tests cover both CLJ and CLJS expansions to prevent hidden regressions.
