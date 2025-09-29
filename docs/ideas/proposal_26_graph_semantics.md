# Proposal 26 · Graph Reference Semantics (Logseq-style Outliner)

## Why
- Evolver already supports reference edges via `:refs`, but no derived graph indexes exist. Clients like Logseq/Roam need fast backlinks, transclusion paths, and graph analytics.

## Inspiration
- **Datascript entities** (`datascript/src/datascript/impl/entity.cljc:60-170`) compute forward and reverse attributes on demand using indexes; they also expose friendly APIs (`entity`, `pull`) to traverse the graph.
- **Logseq** (not vendored yet) builds backlinks and block graphs on top of Datascript. We can mimic this by precomputing indices in Evolver’s derived data.

## Proposed incremental index
```clojure
(defrecord GraphIndex [forward backlinks tags captures])

(defn build-graph-index [{:keys [refs]}]
  (let [forward refs
        backlinks (reduce-kv
                     (fn [m src rels]
                       (reduce-kv
                         (fn [m' rel dsts]
                           (reduce #(update-in %1 [rel %2] (fnil conj #{}) src)
                                   m' dsts))
                         m rels))
                     {} refs)]
    (->GraphIndex forward backlinks {} {})))

(defn graph->suggestions [graph node]
  {:dangling (remove #(contains? (:nodes graph) %) (mapcat second (get-in graph [:forward node])))})
```

- Maintain `GraphIndex` inside the derivation registry (Proposal 1). A pass consumes `:refs` and produces `:graph-index`, keeping structure incremental by diffing mutated refs.
- Expose pure APIs in `kernel.graph`: `backlinks`, `forward-refs`, `find-cycles`, `shortest-path`. Compose with Missionary flows for live graph analytics if needed.

## Trade-offs
- Indexing adds cost; guard with incremental updates (diff refs per op rather than recomputing entire map) and allow opt-out for clients without references.
- Graph metadata can bloat derived data. Store in compact structures (maps of sets) and support pluggable extensions (e.g., tag hierarchies) via Domain Plugins (Proposal 24).

## Next steps
1. Implement a derivation pass that builds `:graph-index` from `:refs`, using Medley helpers to keep code terse.
2. Add API wrappers (`kernel.graph/backlinks`, `kernel.graph/find-path`) with unit tests and property tests (e.g., verifying symmetry of backlinks).
3. Prepare sample domain scenarios (Logseq-like notes, Figma component graphs) to validate performance and correctness.
