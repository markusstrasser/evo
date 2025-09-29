# Proposal 40 · IncSeq-Powered Effect Diffs (Electric)

## Problem
Kernel effects currently emit coarse keywords (`:view/scroll-into-view`) leaving adapters to reconstruct DOM diffs. This duplicates work across clients and hinders deterministic replay in tests.

## Inspiration
- **Electric’s incseq diff** implementation (`/Users/alien/Projects/inspo-clones/electric/src/hyperfiddle/incseq/diff_impl.cljc`, `electric_dom3.cljc`) calculates minimal vector permutations and applies them efficiently.
- **IncSeq** already captures grow/shrink/permutation semantics perfect for our tree diff use cases.

## Proposed Implementation
1. Compute preorder vectors before and after each op (already produced in derived data).
2. Use IncSeq’s `diff`/`combine` to produce `{ :grow g :shrink s :permutation p :change c }`.
3. Emit `{:effect :view/diff :delta delta :cause {:op-index i :op op}}` from `kernel.effects/detect`.
4. Provide reference interpreters (`kernel.effects.diff/apply`) for CLJ/CLJS adapters mirroring Electric’s DOM patchers.
5. Maintain backwards compatibility by optionally emitting legacy effects during migration.

## Example Payload
```clojure
{:effect :view/diff
 :delta {:grow 1 :shrink 0 :permutation {3 1} :change {2 {:id "todo-9"}}}
 :cause {:op-index 4 :op :insert}}
```

## Advantages
- **Deterministic replay**: tests and simulators (Proposal 43) can apply diffs to pure data without DOM involvement.
- **Adapter unification**: every client (DOM, TUI, VR) consumes the same diff contract.
- **Performance**: adapters avoid recomputing diffs; they only apply delta instructions.
- **Trace compression**: stage storyboard (Proposal 34) can attach deltas instead of entire DB snapshots, shrinking logs.

## Challenges
- **Dependency footprint**: bundling IncSeq increases CLJS payload; evaluate tree-shaking or vend minimal subset.
- **CPU cost**: diff computation is not free; gate behind `:effects/delta?` flag and profile.
- **Adapter migration**: clients must implement diff interpreters; provide compatibility layer and phased rollout.

## Rollout Strategy
1. Integrate minimal IncSeq functions (or full lib) with attribution.
2. Feature-flag diff emission; run in shadow mode comparing new vs old behaviour.
3. Once stable, deprecate legacy keywords and update documentation/testing harnesses.
4. Expose devtools visualizer showing diffs step-by-step for debugging.
