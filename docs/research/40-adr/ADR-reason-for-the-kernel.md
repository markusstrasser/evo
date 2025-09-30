You’re bumping into the line between “tree rewriting” and “state machine with contracts.”
Label the concept: this is an API boundary question—query/transform libs (tree-seq, zipper, Specter) vs a document kernel (small algebra + invariants + trace).

Cutoff rule (use this like a decision test):
•	If all you need is offline rewrites, deterministic traversals, or one-shot “turn this tree into that tree,” then zipper/Specter is strictly better—fewer moving parts, maximal expressive power, no runtime law obligations.
•	If you need any two of these: stable IDs, user-visible anchors (before/after, clamped indices), per-op issues instead of exceptions, replayable trace, invariants (no dup siblings, unique parent), and a derived contract that adapters can trust, then you’ve crossed into “kernel” land. That’s a state machine, not a zipper.

Why the kernel exists despite being tiny: zipper/Specter give you how to change structure; they don’t define what counts as a valid edit, don’t produce an issue model, don’t guarantee a closed algebra you can law-test (concat of txs == sequential apply), and don’t standardize anchors or derive. The 3-op kernel is the “thin waist” that pins those behaviors so plugins and UIs can compose without inventing their own physics.

API design heuristics (keep it brutally small):
•	Nouns: :doc/* (payload), :tree/* (topology), :der/* (indexes). That’s it.
•	Verbs (ops): :k/create, :k/place, :k/update. If a feature can’t lower to these, your op set is wrong or your feature is policy, not kernel.
•	Functions: interpret(db txs) -> {:db :issues :trace}, derive(db) -> db', validate(db) -> {:ok? :errors}. Total, no throws.
•	Contracts: integer anchors clamp, before/after require sibling membership, duplicates forbidden, derived must match recompute, issues > exceptions.
•	Laws: associativity over tx concatenation, idempotent place when already positioned, parent uniqueness invariant preserved.

Mental model: keep queries and rewrites in userland (Specter/zipper are great there). Use the kernel only for state transitions you want to log, validate, and explain to other code (plugins, adapters, multi-client sync). When in doubt: if you can replace it with a pure Specter rewrite without losing guarantees you care about, do it; if you’d then have to re-implement issues/derive/invariants/trace, keep it in the kernel.