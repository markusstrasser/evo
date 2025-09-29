# Proposal 76 · Kakoune-Style Selection Intents

## Problem
Planners currently build intents around *targets* (parent id + position). Every composite op manually threads selection state, which makes multi-caret behaviours (e.g. indent a disjoint selection) hard to express and reason about.

## Inspiration
Kakoune treats selections as the primary state: motions extend or shrink selection tuples, then edits consume the whole selection set. Its key reference shows that movement commands (`h/j/k/l`, `;`, `%`) mutate selections before they invoke editing primitives.cite/Users/alien/Projects/inspo-clones/kakoune/doc/pages/keys.asciidoc:17-120

## Proposed Change
Introduce a `kernel.selection` module that:
1. Represents the user focus as `{::ranges #{[anchor cursor] ...}}` instead of a single `:focus/id`.
2. Provides pure cursor motions (`select-up`, `select-parent`, `shrink-to-cursor`) that map 1:1 to Kakoune actions.
3. Lowers structural intents by *interpreting* the selection ranges.

### Before
```clojure
{:intent :move-up
 :selection {:focused "b"}
 :args {:parent "parent"}}
```

ej: every move intent must carry both the node id and its structural target.

### After
```clojure
{:intent :move-up
 :selection {:ranges #{[:node "b"]}}
 :args {}}
```
`kernel.selection/lower` would read `:ranges`, walk to each range’s “previous visual block”, and emit `[:place {:id node :parent target :pos [:before sibling]}]` ops. Selections become the reusable currency for indent/outdent/drag instead of bespoke logic per intent.

## Expected Benefits
- Cuts duplication across move/indent/outdent: each intent reuses the same cursor navigation functions.
- Gives planners a declarative way to model multi-block operations: simply add more ranges.
- Lets us reuse Kakoune’s proven mental model for range preservation and dot-repeat semantics.

## Trade-offs
- Requires an extra lowering stage (selection → ops), so debugging needs tooling to show intermediate ranges.
- Selections are inherently orderless in Kakoune; we’ll need deterministic ordering when generating ops to keep undo stable.

## Roll-out Steps
1. Add `kernel.selection` with unit tests for range navigation (copy Kakoune’s reference cases).
2. Teach one intent (`move-up`) to consume the new API and gate rollout behind a feature flag.
3. Gradually migrate indent/outdent/multi-select once instrumentation proves parity.
