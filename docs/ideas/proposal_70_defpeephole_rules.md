# Proposal 70 · `defpeephole` DSL for Transaction Normalization

## Current friction (Evolver)
- `src/kernel/tx/normalize.cljc:20-86` implements each peephole rule as a standalone function that walks the whole op vector, with manual grouping and `remove`/`partition-by` logic.
- Adding a new rewrite means copying the pattern (loop over ops, detect window, rebuild vector) and remembering to thread it through the fixed-point loop.
- Hard-coded functions make it difficult to attach metadata (e.g., rule name, touched op keywords) or toggle rules for debugging.

## Inspiration
- Datascript’s `case-tree` macro (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/db.cljc:342-380`) compiles declarative pattern trees into nested `if` expressions, giving terse syntax for multi-branch rewrites while producing efficient code.

## Proposed change
Add `kernel.tx.normalize/defpeephole`, a macro that compiles declarative window rewrites into efficient functions and registers them in a rule deck.

```clojure
(defpeephole cancel-create-then-delete
  {:doc "Drop create/delete pairs and subsequent ops on the same id"
   :window 2
   :touches #{:create-node :delete}}
  [[{:op :create-node :id ?id} {:op :delete :id ?id}] _ctx]
  [])

(defpeephole merge-adjacent-patches
  {:doc "Combine consecutive :update-node ops"
   :window :any}
  [[& {:op :update-node :id ?id :props ?props}*] ctx]
  [{:op :update-node :id ?id :props (apply merge (map :props ctx))}])
```

Macro features:
- Compiles pattern clauses into fast predicates using `case-tree`-style expansion (vector destructuring + guards).
- Registers metadata (doc, window size, touched ops) in `rules*`, enabling toggles or targeted debugging (`normalize ops {:only [:cancel-create-then-delete]}`).
- Generates a single normalization pipeline that reduces over `(ordered-rules)` until fixed point, keeping instrumentation (rule hit counts, timing) easy.

## Expected benefits
- **Declarative rules**: Authors express rewrites in terms of small windows and guard predicates. Boilerplate for scanning vectors disappears.
- **Better tooling**: Metadata enables logging which rule fired, measuring coverage, or disabling a rule in REPL sessions when investigating behaviour.
- **Extensibility**: LLM agents can inspect `rules*` to understand available simplifications, aligning with the project’s goal of making planner tooling transparent.

## Trade-offs
- Macro DSL adds new syntax (pattern variables like `?id`, ellipsis for repeated groups). Provide documentation and linting to prevent misuse.
- Need to ensure generated code remains readable for debugging; ship `(macroexpand-1 '(defpeephole ...))` examples in docs/tests.

## Implementation steps
1. Build `rules*` registry and `defpeephole` macro that compiles clauses into predicate + rewrite fn using `case-tree`-style expansion.
2. Port existing rules (`cancel-create-then-delete`, `drop-noop-reorder`, `merge-adjacent-patches`) to the macro.
3. Update `normalize` to reduce over registered rules, recording hit counts when `:trace?` is enabled.
4. Add property-based tests asserting macro-generated rewrites preserve semantics (compare with baseline interpreter across fixtures).
