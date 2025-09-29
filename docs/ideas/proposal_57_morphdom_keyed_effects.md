# Proposal 57 · Keyed Morphdom-Style View Effects

## Problem
- `kernel.effects/detect` currently emits a single `:view/scroll-into-view` hint; adapters compute DOM diffs ad hoc. Without structured diff metadata we can’t express keyed removals, deferred cleanup, or reordering semantics.

## Inspiration
- `morphdom` performs DOM reconciliation using keyed lookups and staged removals (`morphdom/src/morphdom.js:1-188`). It indexes the existing tree, defers removal of keyed nodes, and runs hooks (`onBeforeNodeDiscarded`, `onNodeAdded`) to let consumers manage side effects.

## Proposed change
1. Extend the effect detector to emit morphdom-like instructions: `{:effect :view/diff :ops [{:op :move :id ... :before ...} ...]}` derived from transaction deltas.
2. Maintain a keyed lookup table (mirroring `fromNodesLookup`) based on persistent node IDs so adapters can apply moves/removals without re-rendering entire subtrees.
3. Provide lifecycle hooks (`:before-remove`, `:after-add`) in the effect payload so frontends can attach/cleanup resources exactly once, matching morphdom’s callback strategy.

```clojure
{:effect :view/diff
 :ops [{:op :retain :id "node-42"}
       {:op :move :id "node-17" :before "node-42"}
       {:op :remove :id "node-88" :defer? true}]}
```

## Expected benefits
- Dramatically smaller patches for adapters (especially DOM shells): they can apply structural updates in place rather than diffing data themselves.
- Enables progressive enhancement: hooks let us animate removals, support portals, or integrate with canvas renderers while preserving kernel purity.
- Aligns with existing keyed ID discipline in the kernel; we simply expose the movement/removal plan as data.

## Trade-offs
- Requires a diffing layer that understands keyed vectors; leverage Proposal 55’s dirty ranges plus child adjacency info to compute the ops.
- Frontends must opt into interpreting the richer effect payload—provide a reference implementation (e.g., morphdom wrapper) to ease adoption.
- Need guardrails to ensure `:defer?` removals eventually fire; track outstanding keys and emit cleanup on transaction commit.
