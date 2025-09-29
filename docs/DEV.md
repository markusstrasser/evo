# Development Guide

## Quick Start

```clojure
;; Load dev environment
(require '[repl :as repl])

;; Initialize REPL (loads core.{db,ops,interpret}, fixtures)
(repl/init!)

;; Build test data
(require '[fixtures :as fix])
(def tree (fix/gen-balanced-tree 2 3))
```

## Dev Environment Structure

Located in `dev/` directory:
- **`repl.clj`** - Shadow-cljs REPL bridge (connect!, init!, cljs!, clj!)
- **`health.clj`** - Build health checks (preflight-check!, cache-stats)
- **`fixtures.cljc`** - Test data builders (make-db, gen-linear-tree, etc.)
- **`README.md`** - Complete workflow documentation

## Core Functions

### REPL Connection (`dev/repl.clj`)
- `(repl/connect!)` - Connect to shadow-cljs REPL for :frontend build
- `(repl/init!)` - Load core namespaces and fixtures
- `(repl/cljs! "code")` - Evaluate ClojureScript in browser
- `(repl/clj! "code")` - Evaluate Clojure in JVM

### Health Checks (`dev/health.clj`)
- `(h/preflight-check!)` - Validate environment (shadow-cljs, cache)
- `(h/cache-stats)` - Show cache sizes (.shadow-cljs, out, target)
- `(h/clear-caches!)` - Nuclear option: clear all caches
- `(h/check-shadow-conflicts)` - Detect process conflicts

### Test Fixtures (`dev/fixtures.cljc`)
- `(fix/make-db nodes children-map)` - Build custom db shape
- `(fix/gen-linear-tree depth)` - Generate chain: root→n1→n2→...
- `(fix/gen-flat-tree count)` - Generate root with N children
- `(fix/gen-balanced-tree depth branch)` - Generate balanced tree
- `fix/simple-tree` - Predefined 3-node tree
- `fix/empty-db` - Minimal empty database

## Development Workflows

### 1. Build Test Data
```clojure
(require '[fixtures :as fix])

;; Custom tree
(def db (fix/make-db {"root" {:type :div :props {}}
                      "child" {:type :span :props {}}}
                     {"root" ["child"]}))

;; Generated trees
(def linear (fix/gen-linear-tree 5))
(def flat (fix/gen-flat-tree 10))
(def balanced (fix/gen-balanced-tree 3 2))
```

### 2. Test Operations
```clojure
(require '[core.ops :as ops])

;; Build incrementally
(def db (-> (fix/make-db {} {})
            (ops/create-node "root" :div {})
            (ops/create-node "header" :header {})
            (ops/place "header" "root" :first)))

;; Verify structure
(:nodes db)
(:children-by-parent db)
(:roots db)
```

### 3. Validate Invariants
```clojure
(require '[core.db :as db])

;; Re-derive should be stable
(= (:derived my-db)
   (:derived (db/derive my-db)))

;; Roots should be correct
(db/roots-of my-db)
```

## Testing

### Running Tests
```bash
# All tests
npm test

# Specific test file
npx shadow-cljs compile test
node out/tests.js
```

### Interactive Testing
```clojure
;; Build scenario
(def scenario (fix/gen-flat-tree 5))
(def db (:db scenario))

;; Apply operations
(def updated (ops/create-node db "new" :span {}))
(def placed (ops/place updated "new" (:root-id scenario) :last))

;; Verify
(get (:children-by-parent placed) (:root-id scenario))
```

## Architecture Patterns

### Pure Operations
All operations are pure functions:
```clojure
(ops/create-node db id type props)  ; → new db
(ops/place db id parent-id at)      ; → new db
(ops/update-node db id props)       ; → new db
```

### Canonical DB Shape
```clojure
{:nodes {id {:type :div :props {}}}
 :children-by-parent {parent-id [child-ids]}
 :roots #{root-ids}
 :derived {:parent-id-of {id parent-id}
           :index-of {id index}}}
```

### Fixtures for Testing
Use fixtures to avoid repetitive setup:
```clojure
;; Instead of building manually
(def db {:nodes {...} :children-by-parent {...} ...})

;; Use fixture
(def db (:db (fix/gen-balanced-tree 2 3)))
```

## Troubleshooting

### Environment Issues
```clojure
;; Check health
(require '[health :as h])
(h/preflight-check!)

;; View cache stats
(h/cache-stats)

;; Clear if needed
(h/clear-caches!)
```

### Common Patterns
- **Always use** fixtures for test data
- **Project-local files** only (no /tmp dependencies)
- **Pure functions** for all operations
- **Validate** with db/derive after changes

## See Also
- `dev/README.md` - Complete dev tooling documentation
- `src/core/db.clj` - Canonical db shape and invariants
- `src/core/ops.clj` - Three core operations