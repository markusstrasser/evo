Short answer: refs don’t belong in the kernel algebra; they belong in policy/adapters. Why? Because the kernel’s closure is “tree + three ops.” The moment you add refs you’ve changed the algebra to a general (hyper)graph: you inherit edge registries, cycle classes across relations, scrub-on-delete semantics, and query shape guarantees. That explodes invariants, couples unrelated policies (unique? acyclic? typed?) to every place, and kills the “boring, audit-able core” you’re optimizing for.

You still get “graph powers” without contaminating core: treat refs as derived/indexed policy over node props (or a parallel refstore), recomputed alongside derive, and validated by a labs module. Transclusion/backlinks/search stay view-time joins; no kernel mutation required. Minimal contract: core guarantees stable IDs and a cycle-free tree; labs/ref guarantees {rel {src #{dst}}} with its own constraints and scrub rules. If you must touch core later, add a derived-only plugin hook that merges extra indexes (e.g., :backrefs-of)—never new ops. This preserves the tight surface while letting you layer a full DAG when you actually need it.

“Derived” = edges are indexes, not the source of truth. You compute them from canonical node data after every step, same as :parent-of/:index-of.

Where do refs come from?
•	Structured props: make refs explicit in node data, e.g. (:nodes id :props :refs {:mentions #{b c} :embeds #{x}}). All writes go through update-node; derive flattens that into an adjacency map E {:mentions {id #{b c}} :embeds {id #{x}}} and validates existence/constraints. Delete or move a node and the next derive naturally drops edges pointing to it.
•	Parsed content (if you have wikilinks/markdown): adapters/lowering parse text to structured refs before it hits core, then write the normalized :refs prop via update-node. Core doesn’t parse strings; it only sees structured props.

Why not store edges “for real”? You lose nothing except an attractive nuisance. If edges are materialized state, you need separate ops (add-ref/rm-ref), scrub-on-delete rules, multi-rel constraints, and consistency between two sources of truth (props vs edges). By deriving, there’s only one truth (node props); edges are a rebuildable index with checks.

Caveat: if you need edge metadata (timestamps, weights) or refs that don’t live naturally on a single node (e.g., negotiated links), use a labs-side ledger: a separate store keyed by edge with metadata, fed by the same tx stream—still outside core. Default: keep core as tree+three ops; compute E in derive from :props (or from adapter-lowered props).


> how can refs be derived? from what? refs were storing the actual links?

“Derived” = edges are indexes, not the source of truth. You compute them from canonical node data after every step, same as :parent-of/:index-of.

Where do refs come from?
•	Structured props: make refs explicit in node data, e.g. (:nodes id :props :refs {:mentions #{b c} :embeds #{x}}). All writes go through update-node; derive flattens that into an adjacency map E {:mentions {id #{b c}} :embeds {id #{x}}} and validates existence/constraints. Delete or move a node and the next derive naturally drops edges pointing to it.
•	Parsed content (if you have wikilinks/markdown): adapters/lowering parse text to structured refs before it hits core, then write the normalized :refs prop via update-node. Core doesn’t parse strings; it only sees structured props.

Why not store edges “for real”? You lose nothing except an attractive nuisance. If edges are materialized state, you need separate ops (add-ref/rm-ref), scrub-on-delete rules, multi-rel constraints, and consistency between two sources of truth (props vs edges). By deriving, there’s only one truth (node props); edges are a rebuildable index with checks.

Caveat: if you need edge metadata (timestamps, weights) or refs that don’t live naturally on a single node (e.g., negotiated links), use a labs-side ledger: a separate store keyed by edge with metadata, fed by the same tx stream—still outside core. Default: keep core as tree+three ops; compute E in derive from :props (or from adapter-lowered props).
