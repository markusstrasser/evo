# Development Tooling

Generic REPL and testing utilities for the kernel project. **Zero application-specific assumptions.**

## Structure

```
dev/
  repl.clj        - REPL connection helpers (shadow-cljs bridge)
  health.clj      - Process/build health checks (cache, conflicts)
  fixtures.cljc   - Generic test data builders (for kernel.core/db shape)
  repl/           - REPL scenario examples (lens_scenarios.clj, etc.)
```

## Usage

### Quick Start

```clojure
;; From Clojure REPL
(require '[repl :as repl])
(repl/init!)  ; Load kernel namespaces
```

### REPL Helpers (`repl.clj`)

```clojure
(repl/connect!)   ; Connect to shadow-cljs :frontend build
(repl/cljs! '(js/console.log "Hello"))  ; Eval in browser
(repl/clj! '(println "Hello"))          ; Eval in JVM
(repl/init!)      ; Load kernel.{core,ops,interpret,lens}
```

### Health Checks (`health.clj`)

```clojure
(require '[health :as h])

(h/preflight-check!)  ; Full environment check
(h/cache-stats)       ; See shadow-cljs cache sizes
(h/clear-caches!)     ; Nuclear option: rm -rf .shadow-cljs out target
```

### Test Fixtures (`fixtures.cljc`)

```clojure
(require '[fixtures :as fix])

;; Build custom trees
(fix/make-db {"root" {:type :div} "a" {:type :span}}
             {"root" ["a"]})

;; Generate random trees
(fix/gen-linear-tree 5)      ; root -> n1 -> n2 -> ... -> n5
(fix/gen-flat-tree 10)       ; root with 10 children
(fix/gen-balanced-tree 3 2)  ; depth=3, branching=2

;; Predefined fixtures
fix/empty-db
fix/single-node-db
fix/simple-tree  ; root -> [a, b]
```

## Design Principles

1. **No app assumptions** - Works with any ClojureScript project
2. **Kernel-shape only** - Builds `{:nodes :children-by-parent :roots :derived}`
3. **Composable** - Small, focused utilities
4. **REPL-first** - Copy-pasteable examples in comments

## What Was Removed

- `src/agent/` - Evolver-app validation (selection state, cursor, mock DOM)
- `src/dev.clj` - Evolver-app REPL bridge (assumes evolver.core/store)
- `scripts/run-ui-tests.clj` - Evolver-app test harness
- `scripts/cljs-test-runner.clj` - Evolver-app test runner

All removed code assumed evolver application structure that no longer exists.

## Workflow

```clojure
;; 1. Start shadow-cljs
npm run dev

;; 2. Connect REPL
(require '[repl :as repl])
(repl/init!)

;; 3. Build test data
(require '[fixtures :as fix])
(def tree (fix/gen-balanced-tree 2 3))

;; 4. Test operations
(require '[core.ops :as ops])
(def tx (ops/create {:id "new" :type :div}))
(println tx)
```

## Scenarios

See `dev/repl/lens_scenarios.clj` for copy-pasteable REPL workflows.