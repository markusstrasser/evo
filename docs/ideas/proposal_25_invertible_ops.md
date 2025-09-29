# Proposal 25 · Invertible Operations & History Bridge (Slate)

## Why
- Many clients (Slate, collaborative editors) reason in terms of invertible operations. Evolver currently exposes only forward ops; undo/redo reconstructs deltas by replaying history manually.

## Inspiration
- **Slate operations** (`slate/packages/slate/src/interfaces/operation.ts:1-210`) define a closed set of operations plus `Operation.inverse` (lines 204-252). Inverses capture prior state (e.g., `move_node` stores `path` + `newPath` to invert correctly).
- **Slate transforms** ensure operations carry full context (pre-change node, offsets). We can adopt the same pattern for Evolver sugar ops.

## Proposed data model
```clojure
(defrecord InvertibleOp [tx inverse metadata])

(defn make-invertible [db op]
  (let [prev (capture-prev-state db op)
        tx (expand-op op)
        inverse (compute-inverse db op prev)]
    (->InvertibleOp tx inverse {:prev prev}))

(defmethod compute-inverse :move [{:keys [db]} {:keys [id parent-id pos]} prev]
  {:op :move
   :id id
   :to-parent-id (:parent prev)
   :pos [:index (:index prev)]})
```

- `capture-prev-state` hoists data the inverse needs (e.g., previous parent/index). `kernel.core/place*` already has the logic; expose helper functions so we do not duplicate smarts.
- `expand-op` rewrites sugar ops into primitive sequences so both forward and inverse ops are grounded in the registry (Proposal 8).

## Slate bridge
- Translator `slate->tx` maps Slate operations to Evolver ones (`insert_node` → `{:op :insert ...}` etc.). Use data from `Operation.isOperation` to validate incoming payloads.
- Translator `tx->slate` uses the inverse metadata to produce Slate-compatible ops for CRDT sync.

## Trade-offs
- Requires storing more context (pre-state). Mitigate by keeping metadata minimal and optional for non-history contexts.
- Must guarantee inverse correctness. Add round-trip property tests: `db' = (apply forward); db = (apply inverse db')`.
- Some ops (e.g., derived commands) may not be easily invertible; mark them as `:invertible? false` and handle separately.

## Next steps
1. Define `kernel/invertible.cljc` with `InvertibleOp`, `make-invertible`, and translation helpers.
2. Implement Slate <-> Evolver mapping for the core operation set; base tests on fixtures from `slate/packages/slate/src/interfaces/operation.ts`.
3. Extend the registry metadata (Proposal 8) with `:inverse` hints so we can auto-derive inverses when possible.
