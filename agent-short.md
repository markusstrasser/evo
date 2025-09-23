# MCP + DataScript Development - Critical Findings

## Tool-Specific Issues

- The `clojurescript_eval` tool IS the ClojureScript REPL - don't try to connect again via `(shadow/repl :frontend)`
- `shadow.cljs.devtools.api` namespace is Clojure-only, not available in ClojureScript REPL
- MCP file safety: Must read file before writing to prevent "File has been modified since last read" errors
- `mapcat-indexed` function doesn't exist - use `(mapcat (fn [[i item]] (fn i item)) (map-indexed vector coll))`

## DataScript Entity Reference Critical Behaviors

- DataScript validates entity references **immediately** within transactions, even before other entities in same transaction exist
- Solution: Use string temporary IDs (`"temp-entity-id"`) instead of lookup references (`[:id "entity-id"]`) for intra-transaction references
- Temporary ID consistency is critical - reference temp ID must exactly match entity creation temp ID
- DataScript validates external entity references immediately - referenced entities must exist before transaction

## DataScript `:db/isComponent` Implementation Issues

- DataScript's `:db/isComponent` behaves differently from Datomic - appears broken for tree structures
- Test case: Schema `{:parent {:db/valueType :db.type/ref :db/isComponent true}}`, deleting child2 incorrectly deletes root and orphans grandchild
- Workaround: Skip `:db/isComponent` entirely, implement manual cascade deletion via Datalog queries

## Datalog Query Variable Binding Bug

- Broken query: `'[:find ?o :in $ ?p :where [_ :parent ?p] [_ :order ?o]]`
- Problem: `_` wildcards can match different entities, returning unrelated `?o` values
- Fix: Use same variable: `'[:find ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]]`

## Clojure Collection Function Gotchas

- `get` function doesn't work on lazy sequences from `sort-by` - returns `nil`
- Fix: Use `nth` instead of `get` for lazy sequences, with bounds checking
- `get-mid-string("m", "mm")` edge case: must return string between "m" and "mm", not equal to either

## Shadow-cljs Build vs REPL State Divergence

- Tests can pass in REPL but fail in build until `bun dev` restart due to compilation cache
- Force full recompilation: `(require '[namespace :as alias] :reload-all)`
- Check loaded code version: `(meta #'namespace/function-name)`

## Datalog Rules for Mixed Query Patterns  

- Problem: Mixing declarative simple queries with imperative recursive functions (`collect-descendant-ids`) creates cognitive dissonance
- Solution: Use Datalog recursive rules for transitive closure instead of host language recursion
- Rules definition enables pure declarative queries: `(subtree-member ?ancestor ?descendant)` replaces imperative tree traversal
- Test rules independently before integration: create test connection, verify rule behavior with known data

## Architecture Insight: Query Language Unification

- Decision framework: "Can operation be expressed as 'what data relationships exist'?" → Use declarative query
- Datalog rules are as foundational as schema - define early, test independently, use consistently
- File organization: Schema → Rules → Logic (all using same query patterns)