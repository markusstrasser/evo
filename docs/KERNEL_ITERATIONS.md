# Kernel iterations — deep research

Working document for the narrative essay's "kernel arc" section.
Companion to [ANCESTRY.md](ANCESTRY.md) (chronology) and
[ESSAY_DRAFTS.md](ESSAY_DRAFTS.md) (the convergence + subtraction
arguments). This doc adds per-wave depth on the three waves that
most shape the kernel's identity, plus a forward-looking section
on what was *deliberately left unbuilt*.

Eight waves total — listed at a glance, three detailed below.

## The arc at a glance

| # | Wave | Dates | Op surface | Commits |
|---|---|---|---|---|
| 0 | Browsing's seed | Aug 2025 | 5 ops (multimethod) | `2df4d91`, `14823ac` |
| 1 | First real kernel | Sep 22–25, 2025 | 5 ops, tree DB, plain maps | `a6cad43e` |
| **2** | **Three-op crystallization** | **Sep 29 – Oct 25, 2025** | **3 ops, locked** | **`48922610` → `6507c024`** |
| 3 | Intent + query layers | Oct 25, 2025 | 3 ops, `defintent`, `kernel.query` | `3c6280be`, `a13f0058` |
| **4** | **Session / DB split** | **Nov 21 – Dec 10, 2025** | **3 ops + session atom** | **`52fcd735`, `36f32747`, `fd6a5afe`** |
| 5 | FR registry | Nov 18, 2025 – Jan 6, 2026 | — (specs-as-data) | `b6504c93`, `35fc95e9` |
| 6 | Nexus detour | Nov 28 → Mar 8 | — (dispatch only) | removed `adc3a5b9` |
| **7** | **Alignment with docs** | **Apr 20, 2026** | **3 ops, op-log canonical, stable ids** | **`3e3f5893`, `746da175`, `9f9b5b36`, `8c5686ca`, `d87c1ef7`** |

---

## Wave 2 — Three-op crystallization

*"`:move` and `:delete` were compositions in disguise."*

### Before / after the wave

Browsing shipped with **five kernel primitives**: `:patch`, `:place`,
`:create`, `:move`, `:delete`. They shared a multimethod dispatch
introduced by the Aug-4 "UNIFIED OPERATIONS" commit (`14823ac`).
The dispatcher worked; the surface was incoherent. Two of the five
were compositions of the other three, and there was an ambient
`:update-ui` op crowding in from plugin experiments.

Evo's narrowing to **three primitives** — `create-node`, `place`,
`update-node` — landed as a planned migration:

- `48922610` (Sep 29, 2025) — the migration guide
- `6507c024` (Oct 25, 2025) — the merge that locked it

The canonical DB shape crystallized at the same time:

```clojure
{:nodes              {id {:type kw :props map}}
 :children-by-parent {parent-id-or-keyword [id ...]}
 :roots              #{:doc :trash}
 :derived            {:parent-of {}  :index-of {}
                      :prev-id-of {} :next-id-of {}
                      :pre {}        :post {}
                      :id-by-pre {}}}
```

### The thesis, verbatim

From the migration guide in commit `48922610`:

> *"Tree + three ops = complete closure. No graph contamination in
> kernel. Refs implemented as policy over tree."*

And the generative claim:

> *"The three-op kernel demonstrates the key principle: core stays
> boring while labs can implement arbitrary policies over the
> stable tree + three-op foundation."*

Both sentences survive as invariants in `.claude/rules/invariants.md`
today. They were written as aspirations on Sep 29 and have
governed every kernel change since.

### `:move` as `:place` + parent query

`:move` was never a primitive. It was *"remove from current
parent + insert at new location"* — which is the literal
definition of `:place` when the new location differs from the
current one. `src/kernel/ops.cljc`, the `place` function's
private helpers:

```clojure
(defn- remove-from-current-parent
  "Remove node from whichever parent currently contains it.
   Scans all parents to find and remove the node."
  [db node-id] ...)

(defn- insert-child-at-position [siblings child-id position] ...)
```

`:place` already had to solve `remove-from-current-parent` for its
own correctness — a node can only live under one parent at a
time. Once `place` knew how to evict, there was nothing for a
separate `:move` to do.

### `:delete` as `:place` under `:trash`

`:delete` was never a primitive either. Evo's kernel has two roots:
`:doc` (the live document) and `:trash` (the graveyard). "Deleting"
is moving a node under `:trash`. Restoring is moving it back. The
trash is a real tree you can query, print, undo into.

This design eliminates the special case. Deletion is just a
placement with a canonical parent. It round-trips through the
op log. It preserves subtree structure so undo can restore
children in order.

The only complication this introduces is garbage collection —
you eventually need to actually free tombstoned nodes. That
arrived much later as a startup-time GC pass (`api/gc-tombstones`,
which became Bug #3 in the Wave-7 Close commit — see below).

### The fourth op that didn't stick

In October plugin experiments, a fourth op crept in: `:update-ui`,
for ephemeral state (edit mode, cursor position). Commit
`3a8e2c1c`:

```clojure
(defn update-ui
  "Update ephemeral UI state using recursive merge.
   UI state is never recorded in history (stripped by history/record)."
  [db props]
  (update db :ui #(deep-merge % props)))
```

It lived briefly. The commit that removed it — `51fa4edc`, whose
message is *"Return to true 3-op IR (create/place/update) by
removing fourth :update-ui operation type"* — added a total of
**19 lines of diff across 2 files** to erase every call site. The
small diff tells you `:update-ui` hadn't reached plugin-handler
territory yet. Removing it was cheap because the invariant had
been defended early.

The larger merge `6507c024` ("Return to true 3-op IR with unified
read layer") ultimately:

- **Deleted** `src/kernel/tree.cljc` entirely (67 LOC of
  composition sugar for `:move` and `:delete`)
- **Slimmed** `src/kernel/ops.cljc` from multi-op dispatch to
  three pure functions
- **Refactored** `src/plugins/selection.cljc` so composition
  moved to plugin layer
- **Replaced** `src/keymap/bindings.edn` with a data-driven
  `bindings_data.cljc`
- Net: **170 insertions, 243 deletions** across 20 files; 169
  tests / 586 assertions still passing

### The revealing moment

Two op primitives died in the same week the fourth one was born
and killed. The wave's insight is that *policing the primitive
surface is how you keep a kernel a kernel*. Without the three-op
invariant codified in `invariants.md` and enforced by
`bb check:kernel`, each subsequent `:update-ui`-shaped temptation
would have survived its commit. The rule is the protection; the
wave is what paid for the rule.

---

## Wave 4 — Session / DB split

*"UI state left the DB entirely. 40+ handlers gained a session param."*

### Before / after the wave

Before Nov 21, 2025, the kernel's database contained everything:
the persistent block graph, the editing buffer, *and* the cursor,
selection, folding, and zoom state. Session state lived under a
`:session` key at the top level by convention.

```
Keystroke
  → intent dispatch
    → ops (including session-mutating ops)
      → DB transaction
        → derive-indexes recomputes the whole graph
          → history records the whole DB (including session)
            → render from DB
```

Every keystroke was a full transaction. Every undo rolled back
cursor movement. Snapshots ballooned because every intra-word
caret move was history-worthy.

After the wave:

```
Keystroke (buffer)
  → session/swap-session! (single atom swap)
  [no intent, no ops, no history]

Semantic events (blur, Enter, structural edit)
  → intent dispatch
    → {:ops [...] :session-updates {...}}
      → DB transaction   ← only structural ops touch the DB
      → caller applies session-updates to session atom
        → history records only structural ops
```

Every keystroke no longer hits the transaction pipeline. The op
log records only structural change.

### The three phases

**Phase 1 — Uncontrolled editing boundary** (`52fcd735`,
Nov 21).

The block component's signature changed from deriving
focus/selection off the DB to accepting them as props:

```clojure
;; BEFORE
[{:keys [db block-id depth on-intent embed-set embed-depth]}]
(let [selected? (q/selected? db block-id)
      focus?    (= (q/focus db) block-id)])

;; AFTER
[{:keys [db block-id depth is-focused is-selected
         on-intent embed-set embed-depth]
  :or   {is-focused false is-selected false}}]
(let [selected? is-selected
      focus?    is-focused])
```

With focus/selection now passed down, the block could go
*uncontrolled*: during an edit, the browser owned the text via
`contenteditable`. The DB no longer re-rendered on every
keystroke. The `__lastAppliedCursorPos` guard (still in CLAUDE.md
today) handles the one tricky case — when Replicant's `on-render`
hook fires *after* the browser has already moved the cursor,
don't re-apply a stale cursor position.

**Phase 2 — Intent handler signature migration** (`36f32747`,
Nov 24).

Every intent handler's signature changed from `(fn [db intent])`
to `(fn [db session intent])`. This is a mass refactor
touching 40+ handlers. The `dispatch` API also gains a session
parameter:

```clojure
;; BEFORE
(dispatch db intent)

;; AFTER
(dispatch db session intent)
```

And crucially, the transaction pipeline starts gating history
on "actual structural change":

```clojure
(let [{:keys [ops session-updates]} (intent/apply-intent db session intent)
      record? (and (not (false? enabled?)) (seq ops))]
  ...)
```

Bug #2 in the Wave-7 Close commit (see below) refines this gate
further — `(seq ops)` isn't enough, you need
`(not (identical? db-before db-after))` because normalized-away
no-op places still passed the `seq` check.

**Phase 3 — Buffer plugin deletion** (`fd6a5afe`, Nov 21).

`src/plugins/buffer.cljc` was deleted entirely. "No deprecation"
per the commit body — the plugin existed to route keystrokes
through the intent pipeline, and the pipeline no longer needed
the keystrokes.

The input handler before/after:

```clojure
;; BEFORE — 5-10 calls per keystroke
:on-input (fn [e]
            (on-intent {:type :buffer/update
                        :block-id block-id
                        :text new-text}))

;; AFTER — 1 call per keystroke
:on-input (fn [e]
            (session/swap-session! assoc-in
                                   [:buffer (keyword block-id)]
                                   new-text))
```

The commit body claims *"~80–90% reduction in per-keystroke
overhead"*. Tests went from 298 → 293 (five obsolete
buffer-plugin tests removed); zero *new* failures.

### What broke during the wave

The split surfaced hidden assumptions that the DB shape was the
one true source:

- **`cf5cd98f` (Nov 26) — keymap context resolver.** Called
  `q/editing?` with `db`, but `q/editing?` reads `[:ui
  :editing-block-id]` which had moved to session. Symptom: Escape
  key never matched `:editing` bindings because context was
  always resolved as `:non-editing`.
- **`0f8122b2` (Nov 24) — mass find-replace to update
  `apply-intent` call sites.** Phase 2 changed the signature;
  about a dozen untouched call sites had to be threaded with
  session.
- **`ad85f8f9` (Nov 21) — empty-db init.** The empty DB
  initialization needed to know what shape to hand the new
  session atom.
- **`43657bc2` (Nov 21) — plugin stubs for compilation.** Some
  plugins couldn't be updated in lockstep with the signature
  change; stubs kept the compile green during the transition.

None of these required rolling back the wave. All were
"this-assumed-the-DB-had-that-key" fixes.

### Session atom shape today

From `src/shell/view_state.cljs`:

```clojure
{:cursor {:block-id nil :offset 0}
 :selection {:nodes #{} :focus nil :anchor nil}
 :ui {:folded #{}
      :zoom-root nil
      :current-page nil
      :editing-block-id nil
      :cursor-position nil
      :lightbox nil
      :keep-edit-on-blur false
      :document-view? false
      :journals-view? true
      :drag nil
      :sidebar-visible? true
      :hotkeys-visible? false
      :autocomplete nil
      :quick-switcher nil
      :notification nil
      :editing-page-title? false
      :favorites #{}
      :recents []
      :history []
      :history-index -1}
 :sidebar {:right []}}
```

Everything here used to live in the DB. None of it is
transactable anymore. The DB is *the document*, period.

### The revealing moment

The wave was possible because the DB/session distinction had
accumulated a name but no fence. Once the fence was built, every
subsequent question — *where does fold state live? navigation
history? drag-in-progress state?* — had a one-word answer. The
plan document (`8799d138-kernel-refactor.md`) would later cite
the session split's completeness as prerequisite to the op-log
rework: *"kernel purity — zero imports from `shell/`,
`components/`, or `keymap/` in `src/kernel/`"* became enforceable
only because session had already left.

---

## Wave 7 — Alignment with the docs (last weekend, Apr 20, 2026)

### The plan's own history

`.claude/plans/8799d138-kernel-refactor.md` was written, critiqued
by four cross-model reviewers, and rewritten *twice* in a single
day before implementation began. The §8 revision log, verbatim:

> **2026-04-20 (v3)** — Trimmed to minimum elegant core after
> reviewing v2 against the constraint "every phase must land a
> system simpler or no more complex than today." v2 described the
> end-state at Logseq scale; v3 is what should actually ship now.

> **2026-04-20 (v2)** — Rewritten after cross-model critique
> (Gemini Pro + Gemini Flash + Gemini + GPT-5.4, dispatched via
> `/critique --deep`). Material changes from v1…

The material changes v1 → v2, from commit `03632a55`:

- **Reordered phases.** v1 put delta plugins before op log. The
  reviewers showed the dependency was backwards: deterministic
  replay (log) is the *prerequisite* for validating incremental
  plugins, not the other way around.
- **Added Phase A.** v1 skipped externalizing `:history` from the
  db. Checkpointing a db that contains its own history is
  recursive. v2 makes this an explicit phase.
- **Plugin protocol is per-transaction, not per-op.** Multi-op
  actions (the Script pattern) would expose plugins to invalid
  intermediate states under per-op dispatch.
- **Recompute promoted from fallback to oracle.** v1 had
  `::recompute` as an escape hatch; v2 makes `:initial` the
  normative spec.
- **Plugin isolation rule added.** No cross-plugin index reads,
  eliminating the topological DAG problem.
- **Removed spurious `:delete` op** that violated the three-op
  invariant. (The plan itself was about to reintroduce the fourth
  op.)

The v3 revision (`695fa4eb`) added one governing constraint:

> *Every phase must land a system that is simpler or no more
> complex than today.*

And on that basis v3 cut from v2: branch-aware checkpoint store,
LRU eviction, on-disk persistence, head-db memoization, per-plugin
`:apply-tx` implementations, and an oracle-harness fuzz-replay
test suite. These became "described but deferred" rather than
shipping scope. The four phases shrank from ~7 days to ~2 days:

| Phase | ETA | Purpose |
|---|---|---|
| A | 2 hours | Externalize `:history` (mechanical) |
| B | 1 day | Op log with single checkpoint, no persistence |
| C | 3 hours | Stable block IDs (independent) |
| D | 2 hours | Protocol surface only, no `:apply-tx` impls |

### Why Phase A had to come before Phase B

The plan's Phase A section:

> **Why first.** Phase B (op log) checkpoints the db. If
> `:history` is *inside* the db (as it is today —
> `src/kernel/history.cljc` stores `{:past :future :limit}` under
> `:history`), each checkpoint recursively embeds log-like
> history. **The whole Phase B design collapses unless history is
> outside the db first.** Cross-model critique flagged this as
> the single highest-structural-risk assumption in the first
> draft of this plan.

This is a textbook case for architectural sequencing: the
structural problem (embedding) forces the ordering (externalize
before replace). A first-draft plan missed it. Cross-model review
caught it. The reordering is the most important thing the
critique produced; without it, Phase B would have shipped, worked
on small inputs, and quietly corrupted state at scale.

### Phase B's shape

From the commit body:

```
{:root-db <db>              ; baseline db (set on folder load / reset)
 :ops     [{:op-id :prev-op-id :timestamp :intent
            :ops :session-before}]
 :head    <int>              ; index of current head; -1 = empty
 :limit   <int>}             ; max retained; older ops absorbed
                             ; into :root-db
```

The current db is a pure function of the log:
`head-db = fold(:root-db, ops[0..head])`. Undo is index
arithmetic (`head - 1`); redo advances. Appending after undo
prunes the orphaned tail (matching prior snapshot semantics).

One intentional deviation from the plan: a *linear index* head
instead of a doubly-linked `:prev-op-id` head pointer. Entries
still carry `:prev-op-id` for audit, but traversal is
arithmetic. The plan had preserved the linked structure for
future branching/checkpointing; Phase B's lean cut drops it until
branching is actually needed.

### The Close pattern — four bugs found by cross-model review

After all four phases landed, a **post-implementation** cross-model
review (`/critique close`) surfaced four bugs. All confirmed by
code inspection, all fixed in `d87c1ef7`. The four bugs:

**1. `trim-to-limit` corrupted head-db when head trailed
last-index.** With 4 ops, head=0, limit=2: the old impl set
head=−2 and absorbed ops[0..1] into `:root-db`, *advancing the
visible db past the user's logical position*. The user's undo
position could silently move without user action; redo became
inaccessible. Fix: cap drop-n at `(inc head)` so only ops
at-or-behind head are absorbed.

**Silent-data-loss risk: yes.** This is the bug of the four.

**2. Phantom undo entries when intent ops normalized away.** The
executor gated log append on `(seq ops)`, but ops were
pre-normalization. A no-op `:place` (e.g., moving a block to its
current position) still triggered a log append with
`db-before = db-after`. Every such no-op produced an undo step
that did nothing. Fix: gate on
`(not (identical? db-before db-after))`.

**3. GC tombstones split-brain.** Editor startup called
`(swap! !db api/gc-tombstones)` without updating the log. The
next undo would refold from the log and *resurrect tombstoned
nodes*. Fix: `slog/reset-with-db!` after GC — GC becomes a new
log baseline (history-dropping, acceptable for startup
housekeeping).

**Silent-data-loss risk: possible.** A user who deleted a node,
closed the app, reopened, and hit undo would find the node back.

**4. `id::` parser stole user text.** The new (Phase C) parser
matched `id::` eagerly on any continuation line. A block
containing a literal `id:: foo` mid-text silently lost that line
to the property. Fix in `parse-blocks` + `finalize-block`:
collect all continuations, extract `id::` only if it's the
*final* line. Preserves evo's own serializer round-trip while
refusing to steal non-terminal `id::`-looking user content.

**Silent-data-loss risk: yes, but content-level rather than
state-level.**

Bugs #1 and #3 required specific sequencing to trigger
(rewinding undo deep + calling set-limit, or GC-at-startup +
undo). Bug #2 was stealthy: it only showed up when a user action
produced ops that normalized away. Bug #4 was a parser ambiguity —
the format spec said `id::` is a trailing line, but the parser
didn't enforce trailing-ness.

The `.claude/rules/commit-claim-testing.md` rule applies here:
the Close commit adds *claim-level* regression tests —
`kernel.log trim-preserves-head-db-when-head-trails` and
`shell.storage id-property-only-as-trailing-line` — rather than
only re-running the integration suite.

### What this wave is really about

Wave 7 is where evo's **docs and implementation finally match**.
The constitution says "event-sourced UI kernel" on line 1 of
CLAUDE.md. The FR registry cites event-sourcing as a foundational
property. Until last weekend, the implementation was
snapshot-based (`{:past :future :limit}` — save whole-db
snapshots, restore on undo). Phase B is the moment the claim
becomes true.

This is what *"specs are the product"* forces. A codebase whose
internal docs make structural claims has to pay for them; the
claims are inspectable. In a codebase without an FR registry,
"event-sourced" is marketing. Here it's a failing test until
you implement it.

---

## Part 4 — The not-yet-built: Phase D's `:apply-tx`

Phase D introduced a plugin protocol with two slots:

```clojure
{:initial  (fn [db] -> index-map)        ; MANDATORY oracle
 :apply-tx (fn [prev db-before tx-ops db-after]
              -> index-map | ::recompute)} ; OPTIONAL incremental
```

**No plugin implements `:apply-tx` yet.** Every plugin returns
`::recompute` (or omits the key), falling back to `:initial`.
Today's behavior is identical to the pre-refactor version. The
shape is in place so migrations can happen incrementally, as
evidence demands.

### What Phase D's deferral actually costs

Reading the current plugins in `src/plugins/`:

| Plugin | Derived? | `:initial` cost | `:apply-tx` cost (if built) |
|---|---|---|---|
| `backlinks_index.cljc` | yes | O(\|blocks\|) × regex | O(\|Δrefs\|) on text change, O(\|refs-into-page\|) on page trash |
| kernel's `:parent-of` | yes | O(\|children-by-parent\|) | O(\|place-ops\|) — one entry per place |
| kernel's `:prev-id-of` / `:next-id-of` | yes | O(\|children-by-parent\|) | O(\|affected parents\|) — 4 sibling links max |
| kernel's `:index-of` | yes | O(\|children-by-parent\|) | O(\|children of affected parent\|) |
| kernel's `:pre` / `:post` | yes | O(\|nodes\|) — tree traversal | not worth migrating |
| `pages.cljc` | no — intent handlers | — | — |
| `navigation.cljc` | no — ephemeral session | — | — |
| `folding.cljc` | no — ephemeral session | — | — |

The rest (`structural.cljc`, `editing.cljc`, `selection.cljc`,
`context_editing.cljc`, `text_formatting.cljc`, `clipboard.cljc`,
`autocomplete.cljc`) are intent handlers, not derived plugins —
they don't register with the derived registry.

### Why backlinks is the first candidate

`backlinks_index/:initial` scans every block in the DB, regexes
for `[[page-ref]]` patterns, and accumulates an inverted index.
Cost grows linearly in total block count × per-block regex time.

```clojure
(defn compute-backlinks-index [db]
  (let [all-nodes (:nodes db)]
    (->> all-nodes
         (filter (fn [[_id node]] (= (:type node) :block)))
         (mapcat (fn [[block-id node]]
           (let [text (get-in node [:props :text] "")
                 refs (extract-page-refs text)]  ; <-- regex
             ...))))))
```

At current scale (<100 blocks in testing), this is sub-ms. At
Logseq scale (thousands of blocks), it runs on every keystroke
that passes the transaction pipeline — and post-Wave-4, that's
every *structural* keystroke (Enter, backspace-at-boundary,
paste), not every character, so the pressure isn't as bad as it
could be. But it's still O(full graph) for O(one-block text
change).

The incremental version, sketched:

```clojure
(defn apply-tx-backlinks [prev db-before tx-ops db-after]
  (try
    (let [affected-blocks (set (mapcat extract-op-ids tx-ops))]
      (reduce
        (fn [idx block-id]
          (let [refs-before (extract-page-refs
                              (get-in db-before [:nodes block-id :props :text] ""))
                refs-after  (extract-page-refs
                              (get-in db-after  [:nodes block-id :props :text] ""))
                added   (set/difference refs-after  refs-before)
                removed (set/difference refs-before refs-after)]
            (-> idx
                (remove-backlink-entries block-id removed)
                (add-backlink-entries block-id added))))
        prev affected-blocks))
    (catch _ ::recompute))) ; Bail to :initial on any error
```

Cost: O(\|affected-blocks\| × \|text-delta-refs\|), which is
O(1) per keystroke in the common case. The `catch` ensures the
incremental path is never *less correct* than the recompute
path — the oracle remains the safety net.

### The cheapest wins are in the kernel itself

The kernel's own derived indexes (`:parent-of`, `:prev-id-of`,
`:next-id-of`, `:index-of`) are trivially incremental because
only `:place` ops can change them:

```clojure
(defn apply-tx-parent-of [prev db-before tx-ops db-after]
  (let [place-ops (filter #(= :place (:op %)) tx-ops)]
    (if (empty? place-ops)
      prev  ;; no placement changes → index unchanged
      (reduce (fn [idx {:keys [id under]}]
                (assoc idx id under))
              prev place-ops))))
```

No iteration over the DB. One `assoc` per `:place` op. This is
the shape the plan wanted for every kernel-maintained index —
when `:apply-tx` arrives, the kernel's derive function gets to be
mostly empty except in the "rebuild on load" path.

`:pre` / `:post` / `:id-by-pre` are the exception: tree
traversal is the clean form, and any delta version is more
complex than recompute. Plan marks these "never migrate."

### The evidence that would trigger un-deferring

The plan is explicit that deferral ends when profile shows need.
Three kinds of evidence would qualify:

1. **Perf profile showing derived-recompute >10% of frame
   budget.** Chrome DevTools Performance or a Replicant-level
   timing on `derive-indexes` call cost. Current signal:
   unknown — the plan cites "will be expensive at Logseq scale,"
   not measurement. Nobody's profiled because nobody's hit
   latency.
2. **Real session on a multi-page graph of >500 blocks showing
   perceivable lag.** "Backlinks pane updates at 60fps in Logseq;
   at 20fps in evo" is the kind of complaint that un-defers
   immediately.
3. **Cross-page block refs shipping.** Phase C's `id::` lines
   are the prerequisite. Once blocks can reference each other
   across pages, the number of affected entries per structural
   change can grow sharply — and backlinks becomes a hot path on
   *every* edit, not just text ones.

The smallest rewrite that ships: **backlinks `:apply-tx` on
text-change + page-to-trash,** roughly 100 LOC, with the oracle
harness (from the deferred v2 scope) validating that the delta
matches `:initial` on random op sequences. That's the next
natural un-deferral when any of the three evidence triggers
arrives.

### The pattern this models

Phase D is a structural commit that ships *zero new behavior*.
It exists to give the next version of the codebase a place to
land without refactoring everything. This is the opposite of
speculative generality — the protocol has exactly one real
caller today (the kernel's own derive function, calling
`:initial` on everything) and exactly one hypothetical caller
tomorrow (itself, but with `:apply-tx` wired in).

The "dumbest representation that satisfies the invariants"
rule from the Subtraction section applies here in its dual:
*the dumbest protocol surface that makes the future migration
cheap.* Phase D's map with two keys beats a `defprotocol`,
beats a version-numbered handler registry, beats a capability
manifest. It's the minimum shape that supports the incremental
path without mandating it.

---

## For the essay

The three waves narrate three different kinds of compression:

- **Wave 2 compresses the op surface.** Five ops → three by
  recognizing two were compositions. The rule that protects it
  (`invariants.md`) is the wave's most durable output.
- **Wave 4 compresses the state model.** Everything-in-one-DB →
  document-in-DB / UI-in-session. The rule that protects it
  (`bb check:kernel` on shell imports) is, again, the wave's
  most durable output.
- **Wave 7 compresses the gap between docs and code.** The
  constitution claimed "event-sourced"; the implementation wasn't.
  Specs-as-product forced the alignment. The *plan's own
  revision history* is the most interesting artifact — v1 was
  wrong in a way that only cross-model review caught, and the
  Close commit shows that post-implementation review catches
  bugs code review misses.

Each wave follows the same shape: a documented claim, a pressure
that exposed the gap, a revision that closed it, and a rule
that makes the revision permanent. That's the pattern worth
naming in the essay.

## Loose ends worth tracking

- **Wave 7 Phase D's un-built half.** When it ships, it will be
  the natural bookend to this research — the first plugin to
  implement `:apply-tx` validates the protocol's shape.
- **Wave 4's cost in tests.** Five buffer-plugin tests were
  deleted as obsolete during Phase 3. It would be worth confirming
  that none of them covered behaviors that no longer have any
  test at all — the Wave-7 Close pattern suggests cross-model
  review of the *test suite* at architectural-pivot moments, not
  just the code.
- **The fourth-op ghost.** `:delete-node` was added and replaced
  by tombstone in `0c1679d7` → `569406ca`. This is a third
  instance of the fourth-op temptation (after `:update-ui` in
  Oct 2025 and the spurious `:delete` the cross-model reviewers
  killed in the plan). Worth a sentence in the essay: the three-op
  invariant isn't self-enforcing; it's been tested three times
  and held each time because the rule was written down.
