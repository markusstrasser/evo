# Proposal 56 · Rope-Based Text Nodes à la Xi Editor

## Problem
- Large textual payloads (markdown blocks, code snippets) live inside `:nodes` as plain strings. Operations like `:update-node` replace entire blobs, making diffs, cursor math, and derived metrics (`:child-ids-of`, `:index-of`) oblivious to intra-node edits.
- Undo/redo logic cannot share structure; storing full copies of large strings compounds memory pressure.

## Inspiration
- Xi Editor’s rope library models text as a persistent B-tree (`xi-editor/rust/rope/src/rope.rs:114-185`). Ropes expose incremental edits (`rope.edit`, `RopeDelta`) and carry auxiliary metrics like line counts and UTF-16 lengths for free.

## Proposed change
1. Introduce an optional rope backend for string-like node props: wrap large text nodes in a `Rope` record that stores the Xi-style metrics (lines, utf16) and the structural tree.
2. Extend sugar ops (`:update-node`) to accept lightweight `{:delta ...}` payloads mirroring `RopeDelta`, so planners can apply character-range edits instead of whole-string replacements.
3. Store rope metadata in derived data (`:derived :text-metrics`) for fast line-based navigation and convert to plain strings lazily for adapters that still expect text.

```clojure
;; sketch
(defrecord TextRope [rope metrics])

(defn apply-text-delta [rope {:keys [range insert]}]
  (-> rope
      (rope/edit range insert)
      (assoc :metrics (rope/info rope))))

(update-node* db {:id id :props {:text (apply-text-delta old-rope delta)}})
```

## Expected benefits
- Structural sharing slashes memory churn for repeated text edits (Xi’s ropes guarantee ~log n updates with small allocations).
- Consistent metrics enable precise cursor positioning and selection logic for planners without scanning strings.
- Delta-based updates pair nicely with Proposal 55’s changed ranges—text edits register as fine-grained spans automatically.

## Trade-offs
- Requires Clojure port/binding of Xi’s rope; we can vendor the Rust implementation via WASM or port core algorithms (the rope modules are ~1.5k LOC).
- Need adapter shims so existing code that expects `string?` continues to work—provide `text->string` helpers and mark rope props via metadata.
- Additional complexity for small strings; keep plain strings by default and auto-upgrade nodes whose text exceeds a threshold (e.g., >4 KB).
