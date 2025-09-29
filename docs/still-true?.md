>
That is different. React/Svelte/Elm optimize for human authors (ergonomics, escape hatches); you need determinism, reversibility, locality, and introspection so an LLM can synthesize, diff, and repair without guessing hidden runtime state. FRP is useful, but only as a thin calculus the interpreter can reason about—signals/derivations/effects with explicit lifetimes and zero “magical” subscriptions. The novelty isn’t widgets; it’s a small algebra of intents + a reactivity kernel + self-describing errors that lets models compose interfaces like they compose SQL.

* Underlying concept: edit algebra over a tree has four independent dimensions?:
  existence, topology, order, attributes.
Order only exists relative to a parent, so topology+order is a single axis
operationally. Splitting it forces two ops for the common “move-and-place” and
bloats logs/undo without adding invariants you can’t encode as preconditions.

“insert/move/reorder/patch/delete/ref” (four axes: existence/topology/order/attributes), while your current kernel names are “create-node*/place*/prune*/delete*”. “Prune” never appears in the README, and “delete” is ambiguous there (tombstone vs purge)


Why this is better (problem class: free algebra over trees): fewer primitives ⇒ clearer laws (idempotency, confluence, subtree invariants), simpler property tests, easier lowering from higher “intents,” and a smaller blast radius when
you tweak adapters. If you truly need trash semantics, prove it as a composition (place→:trash) and keep the kernel sovereign.


Short: performance is a backend problem, not an algebra problem. You won’t need to mint move/delete/reorder primitives later if you freeze create-node, place, update-node (with prune lowered to place→:trash). The algebra is closed; speed comes from representation and scheduling.

What you do when it’s slow:
•	Re-derive smarter, not more: switch derive from “recompute all after every op” to incremental invalidation (update only the touched parent(s) and their sibling indexes). Same API, different engine.
•	Use faster sibling structures: keep :children-by-parent canonical, but maintain auxiliary indexes (RRB/finger trees, gap buffers, or fractional :pos keys) in :derived. place assigns a position; the view sorts—no API change.
•	Batch without new ops: interpret already takes a tx vector; defer derive to end or per-parent micro-derives. If needed, add {:op :batch :txs [...]} as sugar that lowers to the same three ops.
•	Whole-list reorder: introduce an optional permute-siblings op only if profiling proves O(n^2) from many places. It’s additive and lowerable to multiple places, so existing clients stay valid; you’re not changing the core semantics, just giving a cheaper path.
•	“Delete for perf”: hard-delete is a policy/GC concern. Keep it in labs (expunge that cleans :trash), or, if ever promoted, it’s orthogonal and doesn’t force renaming place/update-node.

Net: 95% of wins—incremental derive, transient-heavy hot paths, auxiliary order indexes, and batching—are invisible to the public API. If you later add permute-siblings for a real hotspot, make it purely additive and define lowering so nothing breaks. Your integration tests (golden traces over the 3-op surface) remain the guardrail while you swap engines under the hood.