# Proposal 65 · Internal Representation → Ops Transducer

## Problem
- Bulk edits (“paste these blocks”, “clone this subtree”) currently require planners to emit dozens of low-level ops by hand. The result: repetitive boilerplate, easy-to-miss ordering bugs, and high cognitive load for the agent.

## Inspiration
- Athens converts a nested “internal representation” into ordered atomic ops through a pure transducer pipeline (`athens/common_events/bfs.cljc:33-174`). Helpers like `enhance-internal-representation`, `move-save-ops-to-end`, and `internal-representation->atomic-ops` enrich the tree with context, auto-generate UIDs, and produce the minimal op sequence.

## Proposed change
1. Adopt a similar pipeline: accept nested EDN describing desired tree state (`{:node {:children [...]}}`) and run it through reusable passes (assign ids, annotate parents, flatten by BFS) to yield kernel ops.
2. Wrap the pipeline in REPL-friendly helpers (`ops/from-ir`, `ops/paste-ir`) so large edits are just data transformations.
3. Provide hooks for default positioning (Athens’ `default-position`) and automatic grouping of dependent ops (e.g., emit saves after creates).

```clojure
(ops/from-ir db [{:type :heading :props {:text "Hello"}
                  :children [{:type :paragraph :props {:text "World"}}]}])
;; => [{:op :create-node ...} {:op :insert ...} {:op :update-node ...} ...]
```

## Expected benefits
- Massive reduction in planner boilerplate: bulk workflows are described once as nested data, then materialized by the shared pipeline.
- Deterministic ordering (creates before saves, parent-first traversal) removes a whole class of subtle bugs and keeps undo logs sensible.
- Easy REPL prototyping: agents can stash IR templates, tweak them in memory, and immediately see the canonical op expansion.

## Trade-offs
- Need to codify what the IR looks like; start with a minimal subset (type/id/children) and grow as needed.
- Pipeline must stay pure and transparent so agents can inspect intermediate stages; mirror Athens by exposing individual helper fns (`enhance`, `ensure-uids`, `flatten`).
