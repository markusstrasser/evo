# Essay drafts — the evo narrative

Working document. Raw material for the narrative essay, organized
as liftable sections. Not canonical prose — paragraphs here should
be cut, reordered, and rewritten into the final piece.

Cross-reference: [ANCESTRY.md](ANCESTRY.md) is the chronology;
[LESSONS.md](LESSONS.md) is the rule distillation. This doc is
the middle layer — the *argument* each of those feeds.

---

## 1 — The convergence: outliner ≡ UI tree

*(Recommended as the essay's thesis section.)*

For two years the target was "generative UI." Synth wrote it into
its README in 2024: *"AI-driven STEM learning platform that adapts
content and interaction types to individual users … through
generative UI."* Synthoric productized it — `generateComponent` as
a live HTTP endpoint writing `Dynamic_${timestamp}.svelte` files
to disk. Flowread carried the ambition forward with a six-edge
graph model: a `UnifiedNode` could be `contained` by another node,
or `reference` it, or `annotate` it, or `define` it, or
`visualize` it, or `comment` on it. Six ways for two pieces of
content to relate. Six rendering decisions the system had to make
for every edge. Six places a plugin could hook.

Evo's kernel knows one:

```clojure
:children-by-parent {:doc ["a" "b"] "a" ["c"]}
```

That's it. One relationship: `contains`. There is no edge type in
the kernel. There is no reference, annotation, definition,
visualization, or comment primitive anywhere in `src/kernel/`.
Grep the directory for `ref`, `backlink`, `page-ref` — zero matches
in any kernel file. Page references live in `src/parser/page_refs.cljc`;
the backlinks index lives in `src/plugins/backlinks_index.cljc`.
Neither is part of the kernel's state shape. Both read the kernel's
tree and derive their own indexes on top of it.

The compression the project discovered — and this is the thesis I
think the essay should hinge on — is that **a structural text
editor and the UI tree that renders it are the same thing**.

Not identical. Close enough that one data shape does both jobs.

Consider the outline domain. What are the legitimate questions a
block can answer? *Who is my parent? Who are my siblings in order?
What's my text? Am I expanded?* All of these are answered by
"where am I in a parent/child tree, what are my props." Consider
the render domain. What does Replicant need to know? *What nested
structure should I produce? What's the text at each leaf?* Same
questions. Same tree. The outline's `:children-by-parent` is the
same shape as the DOM's nested `<li>` tags, with one-to-one node
correspondence.

Trees have a property graphs don't: **they don't have a placement
problem.** In a graph, when two nodes are related by `annotates`,
something has to decide *where the annotation appears on screen*.
Synthoric built a full `placement` enum (`main | sidebar |
tooltip | floating | inline`) to answer that, and a
`componentRegistry.ts` to dispatch on it. Flowread inherited that
machinery. In evo, there is no placement question to answer,
because children render inline under parents — the kernel's tree
*is* the layout tree. The shell adds scroll position, focus state,
and styling; it doesn't make placement decisions the kernel
doesn't already imply.

Everything graph-shaped still exists in the product. Page
references work. Backlinks work. Block embeds are possible. But
they aren't in the kernel's state shape — they're derived views
computed from block text by plugins that read the tree. The
backlinks index is to the document what the derived `:parent-of`
index is to `:children-by-parent`: a recomputed projection, not a
primitive. The hierarchy is:

```
kernel:    one relationship  (contains)
parser:    text → structured facts (page refs, inline format, images)
plugins:   facts → derived views (backlinks, autocomplete, folding)
shell:     views → DOM (Replicant components, keyboard)
```

Each layer reads the one below and produces projections. The
kernel is the narrowest; the graph nature of the product lives in
the layers built on top of it. Flowread tried to make the graph
the primitive. Evo kept the tree as the primitive and discovered
the graph fell out for free as plugin territory.

The generative-UI ambition isn't dead — it's pushed up the stack.
If an LLM wants to emit a block with a page ref, it emits a
`create-node` op with `:props {:text "[[Target Page]]"}`. The
parser turns the text into a ref; the backlinks plugin turns the
ref into a derived index; the shell turns the derived index into
a sidebar. Five layers, each doing one thing, all of them reading
the same tree. *Generative UI* became *generative blocks whose
rendering is deterministic.* The LLM only needs to understand the
kernel. It doesn't need to decide placement. It doesn't need to
emit Svelte source. It just appends to the tree.

This is what "domain-specific" bought. The moment the project
narrowed from "arbitrary generative UI" to "outliner editing,"
the edge-type explosion collapsed. A domain with one relationship
needs one primitive. A UI tree that renders that domain is the
same shape the primitive already produces. Kernel and view agree
on structure because the structure is just — a tree.

The generative-UI-from-a-1000-component-library version of this
project would still be alive and still failing. The outliner
version works because the representation compresses all the way
down to one relationship. Extraction mode is the natural
next step: a kernel that describes one relationship is much more
extractable than a kernel that has to describe six.

---

## 2 — Language as medium

*(The architecture came first. The language let it breathe.)*

The move from TypeScript/Svelte to ClojureScript wasn't a pivot
of the thesis. It was a pivot of the medium.

By the time flowread's `/unified` route was working in early
March 2025, the architecture that evo runs today was already on
the page: a normalized graph of typed nodes, a single reactive
store, curried dispatch passed as a prop, event logging as a
first-class concern, derived views over active/related/placement
nodes. The `45ca6d5` commit message — *"schema simplification →
mirror actual DOM, semantic structure derived later"* — is the
Svelte-runes version of what evo locks in as an invariant. The
diagnosis was complete. The problem was that the diagnosis had
cost 657 lines of `documentStore.svelte.ts` and a private
`isUpdating` flag to keep Svelte's reactivity from tripping over
its own updates.

Three days after flowread's last commit, the same author started
browsing in ClojureScript. Not because CLJS was better in general.
Because three specific ergonomic pressures had accumulated:

**Immutable by default.** Flowread's store works by reassigning
Maps and Sets to trigger Svelte's `$state` reactivity:
`this.activeNodes = newActiveNodes`. Every mutation is a
three-step dance — build a new Map, reassign, hope nothing else
recomputed mid-flight. That's what the `isUpdating` flag in
`toggleNodeActive` is guarding against. In CLJS, a DB is an
immutable value; a transaction returns a new value; derived
indexes recompute from that value. The dance disappears because
there's nothing to guard.

**Data-shaped ops.** Flowread's events are ad-hoc objects:
`logEvent('NODE_DEACTIVATED', { nodeId, reason, nodeType,
placement })`. Readable, but the event shape is convention, not
contract. Evo's ops are EDN maps: `{:op :place :id "a" :under
:doc :at :last}`. The map *is* the contract. You can print it at
the REPL, serialize it to disk, hand it to an LLM, diff two of
them, or store a thousand of them as the canonical history.
TypeScript's discriminated unions can get close, but the type
gymnastics to make every op equally ergonomic to construct,
pattern-match, and round-trip never quite land.

**Tree walks are library calls.** Flowread's commit `b6eb0f2
better tests. fundamental ops: walk, transform,
editChildren(local lens)` is the moment the author realized tree
traversal deserved a module. In ClojureScript, `clojure.walk`,
`reduce-kv`, and `update-in` are already that module. The first
month of browsing still hit tree problems (hickory collisions,
`prewalk` overflows on deep docs) — which is what forced the
normalized-flat graph in `14823ac`. But even the *failure*
happened faster in CLJS because there was less bespoke
infrastructure to rewrite.

The thesis this section lands on: **language is the last unlock,
not the first.** For 22 months the question was *what is this
thing we're building?* By early 2025 the answer was complete —
flowread has the model. The language change was the
*how-to-express-it* unlock that came after the *what* was already
settled. Had the jump come earlier, there wouldn't have been a
flowread's-worth of architecture to carry across.

The appendix (A) makes this concrete with side-by-side
implementations of the same operation in both languages.

---

## 3 — Subtraction

*(The dominant shape of evo's history is deletion.)*

Sort the 1,661 commits by architectural weight and the top
commits are nearly all the ones that took something *out*. This
is worth dwelling on, because it runs against the shape of every
roadmap, every "features I want to build" list, every tutorial
that treats abstraction as something you accrete toward.

The pattern, repeated:

> Build the complex thing. Use it for long enough to learn what
> it actually needs to do. Realize the domain is simpler than the
> abstraction. Delete.

Twelve case studies, in rough chronological order:

**Fractional (Greenspan) ordering → integer positions.** Evo
started with fractional string keys so siblings could be reordered
without renumbering. The algorithm was rewritten three times
(`82557c39` CLJC-safety, `4cd31049` canonical algorithm, plus
tie-breaking patches) before `62888df3 refactor: replace
fractional ordering with integer-based system` gave up and
switched to integers with a renumber-on-collision fallback. Later
simplified further to a plain ordered vector in
`:children-by-parent`. *Three iterations of the same abstraction
is a signal the abstraction is wrong.*

**DataScript kernel → plain Clojure map.** Commit `9ca2f8e0
experimental datascript ops kernel -- likely hard to REPL with
but correct for hypergraph beyond simple treeops` went in on Sep
23 2025. One day later, `c2b9d880` reverted to a plain map. The
commit message of the experimental attempt already contained its
own epitaph: *"likely hard to REPL with."* The map doesn't
enforce anything DataScript's schema would — and it turned out
nothing *needed* that enforcement. Same shape as the
outliner-vs-graph collapse: once the kernel represents one
relationship, you don't need a database.

**Five-op IR → three-op IR.** Browsing's kernel had five
operations (`:patch :place :create :move :delete`). Evo narrowed
them to three (`create-node`, `place`, `update-node`) at commit
`48922610` with a migration guide, and locked the narrowing at
merge `6507c024 Return to true 3-op IR with unified read layer`.
`:move` is `:place` with a new parent. `:delete` is `:place`
under a trash anchor. `:patch` splits into `create-node` and
`update-node`. Two ops vanished because they were compositions
in disguise.

**A fourth op was added and removed the same week, to protect the
three.** Commit `0c1679d7 feat(kernel): add delete-node operation
for permanent deletion` introduced a literal `:delete-node` op
for permanent removal. Five days later, `569406ca fix(kernel):
use tombstone pattern for permanent delete (preserve 3-op
kernel)` replaced it with a tombstone flag on the node's props.
The commit body reads like a guardrails invocation: *preserve
3-op kernel.* The op was correct and the problem it solved was
real — but `:delete-node` would have made four primitives, and
the invariant said three. Tombstones let update-node carry the
semantic.

**Nexus dispatcher → direct function calls.** A routing layer was
introduced around Nov 28 2025 to debug dispatch order under
concurrent intents. It lived in the code for three months. Commit
`adc3a5b9 [shell] Remove Nexus dispatcher — replaced by direct
function dispatch` deleted 449 lines and added 70. Fewer than
20% of the lines returned. The routing problem it was built to
solve didn't survive contact with the session/DB split — once
the dispatch boundary was elsewhere, the router became ceremony.

**`:update-ui` op → UI state out of the op language entirely.**
During plugin experiments in October 2025, a fourth op crept in
to handle UI updates. The same `6507c024` merge that locked three
ops removed it, moving cursor/selection/folding state into a
separate session atom. The diff was ruthless: not just the op,
but every reference to UI state living in the DB. *The hardest
subtraction is the one that deletes the thing you already used
everywhere.*

**Buffer plugin → ephemeral session atom.** The keypress buffer
started life as a plugin that logged every typed character into
the persistent event log. Undo snapshots ballooned. Commit
`fd6a5afe refactor(phase3): remove buffer plugin, typing now
pure session` deleted the plugin file entirely — *"no
deprecation"* per the commit body — and moved typing into the
ephemeral session atom. The op log shrank roughly 3×.

**Controlled contenteditable → uncontrolled DOM + cursor
tracking.** The editor originally kept the text DB-controlled,
writing every keystroke through the transaction pipeline. The
cursor fought this at every turn. Commit `52fcd735 feat(block):
implement uncontrolled editing architecture` inverted the
ownership: the browser owns text state during an edit; the
kernel receives the final text on blur. A small
`__lastAppliedCursorPos` guard handles the one case the browser
doesn't already handle. The replacement looks like it shouldn't
work; it works better than the controlled version ever did.

**ImageBlock component + `:image` node type → image is just a
block with image props.** Commit `ae633307 refactor(image):
remove legacy ImageBlock component and :image block type`
removed the special-case node type and its dedicated component.
Images became ordinary blocks whose text content is a parseable
image link; the parser and a display plugin handle the rendering.
The kernel no longer knows images exist. Another instance of the
one-relationship collapse: there is one kind of node, and
everything domain-specific is props + parser + plugin.

**Tailwind → vanilla CSS.** Commit `45c93952 refactor(css):
remove Tailwind, adopt modern vanilla CSS` removed 2,637 lines
and added 1,529 net change: **−1,108**. Tailwind was adopted
early for velocity; once the design system stabilized, the
utility classes were more opaque than the CSS variables they were
replacing. Modern CSS (custom properties, `:has()`, container
queries) did the same work with fewer indirections.

**Visible-order index → derived on demand.** A dedicated index
for visible-document order was added Nov 17 2025 and deprecated
within weeks. Plugins that needed it compute it from `:pre` +
folded-state. The index was a cache for a computation nobody was
running enough times to care.

**Plugin manifest v1 → simpler loader.** Centralized plugin
registration went in on Nov 20, was replaced by a simpler loader
on Dec 16, and eventually returned post-Nexus in a much smaller
form. The first version tried to enforce capability declarations
and dependency versions (a trace of flowread's VSCode-style
`pluginplay.js` sketch). The replacement is a manifest that lists
plugin namespaces and nothing else.

And a few smaller ones worth the pile:

- **`kernel/errors.cljc`** — dead, removed (`f8fbbb65`).
- **`kernel/dbg.cljc`** — 129 lines, unused, removed (`d0114109`).
- **`shell/demo_data.cljs`** — 7 lines, unused (`374f7e8d`).
- **`resources/seed-data.edn`** — 69 lines, unused (`da341025`).
- **`clj-kondo hooks`** — dead, removed (`265b7140`).
- **`resolve-anchor-in-vec`** — deprecated then removed (`b40dcf1c`).
- **`data_readers.cljc`** — broken, removed (`4f05ee06`).
- **`kaocha config`** — vestigial (`65a0b0ee`).
- **`repo-tools MCP`** — zero usage (`d02e4492`).
- **`MathJax visibility hiding`** — "unnecessary complexity",
  reverted in its own commit (`010bc64a`).
- **`DEPENDENCY_REVIEW.md`** — one-shot assessment, recs landed,
  doc removed (`00f961b8`). Per the "docs are facts not plans"
  rule: executed plans get deleted; git preserves them.
- **Legacy renames carry-over** — commit `161448c0 refactor:
  remove old files replaced by renames` removed 3,924 lines at
  once after a rename sweep.

The replacements looked naive at the moment of substitution.
Plain maps instead of a graph database. An ordered vector
instead of fractional CRDTs. A direct function call instead of a
dispatch registry. An integer instead of a rational. Vanilla CSS
instead of Tailwind. A tombstone flag instead of a fourth op.
Each was right.

The productive question — the one each of these commits is
answering without stating it — isn't *"what's the best
abstraction for X?"* It's **"what's the dumbest representation
that still satisfies the invariants?"** The best abstraction is
discovered by subtraction, not design. You earn it by building
the wrong one first, long enough to know what it was protecting
against.

Subtraction isn't regression. It's compression. And the
compression can only happen after you've used the uncompressed
version long enough to see which parts weren't doing any work.

---

## Appendix A — Before / After

### A.1 — The document shape

Flowread, `src/routes/unified/schema/unifiedModel.ts` (110 lines
of types), plus a 657-line class wrapper in `documentStore.svelte.ts`:

```typescript
export interface UnifiedNode {
  id: string;
  type: NodeType;         // document | paragraph | section | span |
                          // reference | tooltip | definition | ...
  content: {
    text?: string;
    title?: string;
    description?: string;
    attributes?: Record<string, any>;
  };
  edges?: Array<{
    type: EdgeType;       // contains | references | annotates |
                          // defines | visualizes | comments
    target: string;
    targetText?: string;
    context?: any;
  }>;
  presentation?: {
    placement?: PlacementType;  // main | sidebar | tooltip |
                                // floating | inline
    visibility?: VisibilityType;
    styling?: Record<string, any>;
  };
}

export interface Document {
  rootNode: UnifiedNode;
  nodeIndex?: Map<string, UnifiedNode>;
}
```

Evo, `src/kernel/db.cljc` (the shape fits in a comment block at
the top of the file):

```clojure
;; {:nodes {"id" {:type :block :props {...}}}
;;  :children-by-parent {:doc ["a" "b"] "a" ["c"]}
;;  :roots [:doc :trash]
;;  :derived {:parent-of   {"a" :doc}
;;            :next-id-of  {"a" "b"}
;;            :prev-id-of  {"b" "a"}
;;            :index-of    {"a" 0 "b" 1}
;;            :pre         {"a" 0 "b" 1}
;;            :post        {"a" 2 "b" 1}
;;            :id-by-pre   {0 "a" 1 "b"}}}
```

Same information. Flowread needs a class, a Map, an
`indexAllNodes` recursion to build it, six edge types, a
placement enum, a visibility enum, and a component registry. Evo
needs a nested map literal with one relationship.

### A.2 — Applying a change

Flowread's `toggleNodeActive` (`documentStore.svelte.ts`, 80
lines, guards recursion, builds new Maps to placate reactivity):

```typescript
toggleNodeActive(nodeId: string, reason: string, context: any = {}) {
  if (this.isUpdating) {
    console.warn('Avoiding recursive toggleNodeActive call');
    return;
  }
  this.isUpdating = true;
  try {
    const node = this.getNode(nodeId);
    if (!node) { /* ... */ return; }

    const newActiveNodes = new Map(this.activeNodes);
    const newLastActivatedByType = new Map(this.lastActivatedByType);

    if (this.activeNodes.has(nodeId)) {
      newActiveNodes.delete(nodeId);
      this.logEvent('NODE_DEACTIVATED', { nodeId, reason, /* ... */ });
      this.activeNodes = newActiveNodes;
      if (this.lastActivatedByType.get(node.type) === nodeId) {
        this.relatedNodes = new Set();
      }
    } else {
      newActiveNodes.set(nodeId, { reason, context });
      newLastActivatedByType.set(node.type, nodeId);
      this.activeNodes = newActiveNodes;
      this.lastActivatedByType = newLastActivatedByType;
      /* ... related-node update, source-node tracking, etc. */
    }
  } finally {
    this.isUpdating = false;
  }
}
```

Evo's `create-node` (`src/kernel/ops.cljc`, the whole thing):

```clojure
(defn create-node [db id node-type props]
  (if (contains? (:nodes db) id)
    db
    (assoc-in db [:nodes id] {:type node-type :props props})))
```

No `isUpdating` flag. No recursion guard. No new-Map-reassign
dance. The function is pure; the caller either uses the returned
DB or doesn't. Reactivity is something the shell arranges around
an immutable value, not something the kernel has to defend
against.

### A.3 — An event, as data

Flowread (an event is whatever the caller passed):

```typescript
this.logEvent('NODE_DEACTIVATED', {
  nodeId, reason, nodeType, placement
});
```

Evo (an op is a map that round-trips through disk, REPL, wire, and LLM):

```clojure
{:op :place :id "a" :under :doc :at :last}
```

The shell uses EDN. The undo log is a vector of these. The
`bb repl-health` check prints them. Page-saves serialize them.
LLM tool calls return them verbatim. When the op is data, every
consumer gets to be dumb.

### A.4 — Tree walk

Flowread, commit `b6eb0f2 fundamental ops: walk, transform,
editChildren(local lens)` — the moment the author realized tree
traversal needed its own module. The util file is ~400 lines.

Evo, `src/kernel/db.cljc`:

```clojure
(defn- traverse-tree [children-by-parent roots]
  (letfn [(visit [acc id]
            (let [acc' (update acc :pre-order conj id)
                  children (get children-by-parent id [])
                  acc'' (reduce visit acc' children)]
              (update acc'' :post-order conj id)))]
    (reduce (fn [acc root]
              (if (contains? children-by-parent root)
                (visit acc root) acc))
            {:pre-order [] :post-order []} roots)))
```

Fifteen lines, no framework. The `reduce` is library code.

### A.5 — The graph that wasn't

Flowread's edge types:

```typescript
export type EdgeType =
  | 'contains'        // parent/child
  | 'references'      // citation / external
  | 'annotates'       // annotation on another node
  | 'defines'         // term definition
  | 'visualizes'      // visual of another node
  | 'comments';       // commentary
```

Evo's equivalent:

```clojure
:children-by-parent  ;; that's the whole list
```

`contains` survived as `:children-by-parent`. `references` →
`src/parser/page_refs.cljc` + `src/plugins/backlinks_index.cljc`
(derived from text content, not stored as an edge). `annotates`,
`defines`, `visualizes`, `comments` never materialized as
primitives; any of them could ship as a plugin that reads the
tree and computes an index. None of them are in the kernel.

---

## Appendix B — Subtraction catalog (raw commit log)

For the writer; not all of these belong in the essay.

| What was built | What replaced it | Commit(s) |
|---|---|---|
| Fractional string ordering | Integer positions, then ordered vector | `82557c39` → `4cd31049` → `62888df3` → `46a2e88a` → `70195e5e` |
| DataScript kernel | Plain Clojure map | `9ca2f8e0` → `c2b9d880` |
| Five-op IR | Three-op IR | `48922610` → `6507c024` |
| `:delete-node` (4th op) | Tombstone flag on props | `0c1679d7` → `569406ca` |
| `:update-ui` op | Session atom separate from DB | `6507c024` |
| Nexus dispatcher | Direct function calls | added Nov 28 → `adc3a5b9` Mar 8 |
| Buffer plugin (persistent) | Ephemeral session atom | `fd6a5afe` |
| Controlled contenteditable | Uncontrolled DOM + cursor tracking | `52fcd735` → `76297bc1` |
| `:image` block type + ImageBlock | Ordinary block with image props | `ae633307` |
| Tailwind | Vanilla CSS + custom properties | `45c93952` (net −1,108) |
| Visible-order index | Derived on demand | Nov 17 add → deprecate |
| Plugin manifest v1 | Simpler loader | Nov 20 → Dec 16 |
| 6-edge graph model (flowread) | One relationship (`contains`) + plugins for the rest | architectural, pre-evo |
| `kernel/errors.cljc` | — (dead) | `f8fbbb65` |
| `kernel/dbg.cljc` (129 LOC) | — (dead) | `d0114109` |
| `shell/demo_data.cljs` | — (unused) | `374f7e8d` |
| `resources/seed-data.edn` (69 LOC) | — (unused) | `da341025` |
| `clj-kondo` hooks | — (dead) | `265b7140` |
| `resolve-anchor-in-vec` | — (deprecated) | `b40dcf1c` |
| `data_readers.cljc` | — (broken) | `4f05ee06` |
| `kaocha` config | `shadow-cljs :test` + `bb test` | `65a0b0ee` |
| `repo-tools` MCP | — (zero usage; scripts stay) | `d02e4492` |
| MathJax visibility hiding | — ("unnecessary complexity") | `010bc64a` |
| `DEPENDENCY_REVIEW.md` | — (executed, recs landed) | `00f961b8` |
| Legacy files after renames | — | `161448c0` (−3,924 lines) |

---

## Structural suggestion for the essay

Three acts that map to the three sections:

1. **The convergence** (§1) — *what the project thought it was vs
   what it turned out to be*. Open with the six-edge graph
   ambition (savant → synth → synthoric → flowread); land on the
   one-relationship kernel.
2. **Language as medium** (§2) — *how the architecture earned its
   ergonomics*. The architecture was settled in flowread; CLJS
   was the expressive unlock that let it be as small as it wanted
   to be.
3. **Subtraction** (§3) — *how the ongoing maintenance of the
   kernel works*. The product's history is deletions; the
   invariant the deletions protect is the narrowness from §1.

If the essay runs long, §3 is the most self-contained and could
ship as a standalone piece. §1 is the one that genuinely depends
on the ancestry being set up first (the flowread edge types are
the foil); it should not run without ANCESTRY.md having done its
work.
