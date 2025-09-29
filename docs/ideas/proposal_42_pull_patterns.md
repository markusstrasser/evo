# Proposal 42 · Graph Pull Patterns for Refs (Datascript Pull API)

## Need
Graphs encoded in Evolver DBs (`:refs`, tree topology) require repeated manual traversals to answer questions like “who references this node?” or “give me descendants with props”. Every tool writes its own recursive walk, risking inconsistency.

## Prior Art
- **Datascript pull API** (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/pull_api.cljc`) compiles declarative patterns into reusable frames, handling recursion limits, defaults, and cardinality.

## Proposed Design
1. Define a DSL mirroring Datascript pulls but tuned for Evolver axes: e.g. `[:node/id {:children ...} {:refs/by :alias [:node/id :props/name]}]`.
2. Build a parser that produces execution frames, caching them via Proposal 39’s LRU.
3. Implement executor functions `pull-one`, `pull-many`, `pull-tree` that walk kernel derived indices (`:parent-id-of`, `:child-ids-of`, `:refs`).
4. Support recursion depth control and wildcard pulls (e.g. `'*` for all properties).
5. Integrate with datafy (Proposal 37) so `nav` queries can embed pull expressions.

## Example
```clojure
(pull db
      [:node/id :node/props
       {:node/children [:node/id]}
       {:ref/backlinks [:node/id]}]
      "node-42")
```

## Benefits
- **Declarative querying**: callers describe desired shape without manual loops.
- **Consistency**: one implementation handles backlinks, domain refs, invariants.
- **Performance**: compiled frames reuse caches and avoid repeated schema parsing.
- **Agent friendliness**: LLM instructions can reference pull syntax directly.

## Concerns
- **Scope creep**: pull DSL might grow into full query language; keep focus on graph navigation, not arbitrary predicates.
- **Complexity**: executor must respect invariants (no infinite recursion); enforce depth caps and cycle detection.
- **Lock-in**: ensure plain map traversals remain possible for simple scripts.

## Execution Plan
1. Sketch DSL grammar and compile to frame structures.
2. Implement executor leveraging derived indices; add tests mirroring Datascript semantics.
3. Document usage with examples (tooling scripts, REPL).
4. Phase migration by wrapping existing helper functions (`graph/backlinks`, etc.) with `pull`.
