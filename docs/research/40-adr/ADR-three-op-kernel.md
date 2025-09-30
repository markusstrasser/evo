ADR-001: Three-Op Kernel, Namespaced Schema, and Plugin Architecture

Status: Accepted
Date: 2025-09-29
Owner: Kernel team
Scope: Kernel surface, data model, naming, plugin contract, migration

⸻

1) Context

Previous code mixed primitives, sugar ops, refs, policies, and adapters. This bloated the core, made reasoning/testing brittle, and confused layers (intent vs op). We want a boring, auditable kernel and fast-moving userland (plugins/adapters).

⸻

2) Decision (high-level)
   •	Freeze the kernel to exactly 3 operations and 3 functions:
   •	Ops: :k/create, :k/place, :k/update.
   •	API: interpret(db txs) -> {:db db' :issues [] :trace [...]}, derive(db) -> db', validate(db) -> {:ok? bool :errors [...]}.
   •	Namespaced canonical schema with precise semantics (no “-id” suffix soup).
   •	Everything else is userland: structure editing, refs, transclusion, undo, policies, UI adapters.
   •	Plugins provide: intents (lower → core txs), derive-ext (indexes), validate-ext (policy issues).
   •	Lenses/defop-style helpers move to a labs SDK (read-only lenses + defintent macro).
   Kernel never imports labs.

⸻

3) Canonical data model (names & semantics)

3.1 Top-level db keys

{:doc/nodes         {<id> {:node/type kw :node/props map}}
:tree/children     {<parent> [<child-id> ...]}   ;; parent is node-id or sentinel kw
:der/indexes       {:der/parent-of  {<id> <parent>}
:der/index-of   {<id> int}
:der/prev-id-of {<id> <id|nil>}
:der/next-id-of {<id> <id|nil>}}
:tree/roots        [:tree/root]                  ;; vector of sentinel roots (default one)
}

Naming rules
•	Use namespaces to disambiguate domains: :doc/* for stored node data, :tree/* for canonical topology, :der/* for recomputed indexes, :node/* for per-node fields.
•	Vectors of children live only in :tree/children. No duplicates allowed.
•	Parents may be a node id or a sentinel keyword (e.g., :tree/root, :bin/trash), enabling multiple roots without inventing fake nodes.

3.2 Derived (authoritative)

derive(db) fully recomputes :der/indexes from :tree/children. Kernel treats anything under :der/* as derived truth, never user-authored.

⸻

4) Kernel surface (ops & API)

4.1 Operations (only 3)
•	:k/create — create a node shell; may immediately place via :under+:at for ergonomics (still one op).

{:op :k/create :id "n" :node/type :p :node/props {}
:under :tree/root :at :last}  ;; :under/:at optional; if present it places


	•	:k/place — reparent and/or reorder within a parent.

{:op :k/place :id "n" :under <id|:tree/root> :at <anchor>}


	•	:k/update — deep-merge node props; maps merge recursively, scalars overwrite.

{:op :k/update :id "n" :node/props {:style {:bold true}}}



Anchors (:at):
•	:first | :last | int (int clamps to [0..len])
•	{:before <id>} | {:after <id>} (must reference a current sibling)

4.2 API (pure, total)
•	interpret(db, txs)
Normalize → validate → apply → derive per step → accumulate :trace and non-fatal :issues. Never throws; on invalid op, records an issue and skips mutation.
•	derive(db)
Rebuilds :der/indexes (parent/index/prev/next) from :tree/children. O(n).
•	validate(db)
Invariants:
•	Every child id exists in :doc/nodes.
•	No duplicates in any :tree/children vector.
•	Each non-sentinel child appears in at most one parent vector.
•	:der/indexes equals recomputation from :tree/children.

⸻

5) Terms & boundaries (clear semantics)
   •	Intent: high-level user action (e.g., :wrap, :split, :add-ref). Lives in plugins. Pure: (db, payload) -> [core-ops].
   •	Tx: a vector of core ops (:k/create|:k/place|:k/update).
   •	Op: single kernel operation (above).
   •	Interpret: kernel function applying a tx to a db (plus trace/issues).
   •	Plugin: userland pack of {intents, derive-ext, validate-ext}. Returns extra indexes under its own ns (e.g., :graph/*) and policy issues.
   •	Adapter: UI glue. Maps events → plugin intents → interpret; merges plugin derived indexes for rendering. No direct writes to db.

⸻

6) Labs SDK (userland, optional)
   •	defintent macro (successor to defop): declares intent schema + lowering to core ops.
   •	Lenses (read-only): helpers like children-of, parent-of, next-id, prev-id, path, implemented solely over :tree/children + :der/*.
   •	Anchor helpers: compute stable :at anchors from ids/indices.
   •	Policies: pure validators that return issues; never mutate.

Example:

(defintent :structure/wrap
[:map [:id :string] [:new-id :string] [:type keyword?]]
(fn [db {:keys [id new-id type]}]
(let [p (get-in db [:der/indexes :der/parent-of id])]
[{:op :k/create :id new-id :node/type type :node/props {} :under p :at {:before id}}
{:op :k/place  :id id :under new-id :at :last}])))


⸻

7) Refs & transclusion (design stance)
   •	Not core. Truth lives in node props (:node/props), e.g. {:refs {rel #{dst-id ...}}} or {:embed {:target <id> ...}}.
   •	Derived indexes (userland):
   :graph/edges (forward), :graph/backlinks (reverse) computed from the above.
   •	Intents: :graph/add-ref, :graph/rm-ref, :graph/transclude lower to :k/update and/or :k/create + :k/place.
   •	Policies (userland): uniqueness, acyclicity across a relation, missing targets, etc., emitted as issues.

This keeps the kernel graph-agnostic while enabling graph power at the edges.

⸻

8) Alternatives considered
   •	Put refs into core → rejected (policy-laden, high coupling, hard to law-test).
   •	Keep sugar ops in core → rejected (surface creep, ambiguous semantics).
   •	Protocol-based kernel → rejected (abstraction inversion; we want a value kernel).

⸻

9) Consequences

Pros
•	Small, auditable surface; easy to property-test and law-check.
•	Clean promotion path: any stable plugin feature can be standardized without widening core ops.
•	UI/client velocity via plugins; kernel stability.

Cons
•	Some features need two steps (e.g., wrap = create+place); mitigated by intents.
•	Plugin authors must learn lowering and anchors; SDK reduces friction.



13) File layout

/core
db.clj            ; empty-db, derive, validate (invariants)
/core
ops.clj           ; :k/create, :k/place, :k/update (pure)
/core
interpret.clj     ; normalize→validate→apply→derive, issue model, trace
/core
schema.clj        ; Malli contracts + describe-ops

/labs
sdk/{intents.clj,lens.clj,anchor.clj}
structure/plugin.clj
graph/plugin.clj
...

/adapters
<ui shells and mappings>

/ir/examples/*.edn
/test/{core_kernel_test.clj,laws_prop_test.clj,golden_replay_test.clj}


⸻

14) Glossary (final disambiguation)
    •	Intent (plugin): human/UI action → lowered to core ops.
    •	Op (kernel)
    •	Tx: vector of kernel ops.
    •	Interpret: apply a tx to a db, returning {:db :issues :trace}.
    •	Validate: invariant check over the canonical db; no policy.
    •	Plugin: provides intents, derive-ext, validate-ext—never mutates directly.
    •	Adapter: bridges UI↔intents↔interpret and merges derived views.

This locks the kernel into a small, provable algebra; everything else gets to be fun, fast, and replaceable.