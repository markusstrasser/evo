- SRS operation schemas (Malli, matching existing pattern)
- Append-only log entry format (EDN examples)
- Plugin extension points (multimethods like compile-intent pattern)
- Code patterns (concrete Clojure examples)
- How intents compile to kernel ops (like :indent/:outdent pattern)

Focus on:
- Reusing existing kernel (don't reinvent create/place/update)
- Separation of concerns (kernel vs SRS vs persistence)
- Concrete schemas and data shapes
- Plugin extensibility without core changes



[2025-10-05T17:51:47] thinking

**Preparing architecture design approach**

I’m figuring out how to create a detailed architecture design plan without coding, focusing on reading key overview documents like AUTO-SOURCE-OVERVIEW.md and AUTO-PROJECT-OVERVIEW.md in the project directory to gather context before outlining the architecture. Since this is complex, I’m considering a multi-step manual plan despite no explicit plan tool being specified.
[2025-10-05T17:51:49] exec bash -lc 'cat AUTO-SOURCE-OVERVIEW.md' in /Users/alien/Projects/evo
[2025-10-05T17:51:49] bash -lc 'cat AUTO-SOURCE-OVERVIEW.md' succeeded in 38ms:
```markdown
╔══════════════════════════════════════════════════════════════════════════════╗
║                     THREE-OP KERNEL ARCHITECTURE                             ║
║              Tree Database with Transaction Pipeline                         ║
╚══════════════════════════════════════════════════════════════════════════════╝

┌─ Design Principles ──────────────────────────────────────────────────────────┐
│                                                                             │
│ • Canonical State: A single, minimal source of truth for the tree           │
│   structure (`:nodes`, `:children-by-parent`).                              │
│                                                                             │
│ • Derived Indexes: All other views of the data (parentage, ordering, etc.)  │
│   are pure functions of the canonical state. No redundant state.            │
│                                                                             │
│ • Immutability: All operations are pure functions that take a database      │
│   value and return a new one. No in-place mutation.                         │
│                                                                             │
│ • Closed Operation Set: The kernel is defined by just three operations      │
│   (`create-node`, `place`, `update-node`). All higher-level behaviors are   │
│   compiled down to these primitives.                                        │
[2025-10-05T17:51:49] tokens used: 7,242
[2025-10-05T17:51:50] tokens used: 7,242

[2025-10-05T17:51:51] thinking

**Opening project overview**
[2025-10-05T17:51:52] exec bash -lc 'cat AUTO-PROJECT-OVERVIEW.md' in /Users/alien/Projects/evo
[2025-10-05T17:51:52] bash -lc 'cat AUTO-PROJECT-OVERVIEW.md' succeeded in 28ms:
# Project: evolver

## Quick Start

-   **Install:** `npm install`
-   **Run Dev Server:** `npm run dev` (starts shadow-cljs watch)
-   **Run Tests:** `npm test`

## Project Structure

-   **`dev/`**: REPL helpers, test fixtures, health checks, and local development tooling. Not for production code.
-   **`docs/`**: Project documentation, research specs, architectural decision records (ADRs), and prompt templates.
-   **`scripts/`**: Automation and workflow scripts for research, analysis, code generation, and testing.
-   **`mcp/`**: Model Context Protocol (MCP) server implementations for AI agent integration.
-   **`public/`**: Static assets for the front-end application, including `index.html`.
-   **`.legacy_deprecated/`**: Archived code that is no longer in use but kept for historical context.

## Development Workflow

-   **REPL Setup:** Connect your editor to the nREPL server on port `55449` (defined in `shadow-cljs.edn`). Use `dev/repl/init.clj` for setup helpers.
[2025-10-05T17:51:52] tokens used: 8,939
[2025-10-05T17:51:53] tokens used: 8,939

[2025-10-05T17:52:06] thinking

**Designing SRS Kernel Architecture**

I'll lay out a detailed textual architecture covering the eight specified points: frontmatter mapping to nodes with new types and props; append-only log format with EDN example; plugin system for card types using multimethods; defined SRS operations with Malli schemas and integration into existing ops; undo/redo via log pointers and state replay; description of derived indexes for scheduling metadata; markdown reconciliation approach with watchers and intents; and image occlusion handling via plugins and overlay nodes. I'll also include representative Clojure code snippets for schemas and intent compilation and discuss separation of concerns in kernel DB extensions and log persistence, aiming for a clear, structured explanation without modifying code files.
[2025-10-05T17:52:08] codex

**SRS Kernel Extension**
- Treat each markdown file as a `:deck` node; YAML frontmatter becomes the node `:props`, and file body goes to a child `:card-content` node so edits flow through `update-node`. Keys like `id`, `title`, `tags`, `due`, `interval`, `ease`, `suspend?`, `type` map directly, while optional `fields`/`media` vectors become nested data under `:props`.
- Keep canonical DB lean: new node types `:deck`, `:card`, `:review`, `:media`, `:card-content`, all using the existing `:nodes`/`:children-by-parent` layout. Decks own cards, cards own content/media nodes; reviews hang off cards to preserve history.
- Append-only log rows are EDN maps persisted verbatim (e.g. newline-delimited in `data/log/2025-…edn`). Minimal shape: `{:log/id uuid :timestamp inst :actor user :source :markdown|:ui :intent {:type :srs/review-card …} :ops [{:op :update-node …} …] :kernel/after-hash sha}`. `:ops` capture the normalized core ops emitted; `:kernel/after-hash` is an optional checksum (e.g. SHA of canonical state) for quick integrity checks.

**Kernel & Derived Indexes**
- Extend `core.schema/node` to allow the SRS node keywords; add deck-level props schema enforcing markdown path + slug; card schema ensures `:deck-id` linkage.
- Derived indexes (registered via existing `derive-indexes` hook):
  - `:due-index` → `{:due-ts -> #{card-id}}` for scheduling queues.
  - `:review-history-index` → `{:card-id -> [{:ts … :grade … :interval …}]}` built from review nodes.
  - `:scheduling-metadata` → quick lookup for `{:card-id -> {:interval days :ease float :streak int :suspend? bool}}`.
- These are pure functions over canonical data; register them through the plugin hook (`core.derive/register!`).

**Operations & Schemas**
```clojure
(ns srs.schema
  (:require [malli.core :as m]))

(def CreateCard
  [:map
   [:type [:= :srs/create-card]]
   [:card/id :uuid]
   [:deck/id :uuid]
   [:card/type [:enum :basic :cloze :image-occlusion]]
   [:front :string]
   [:back {:optional true} :string]
   [:props {:optional true} [:map-of :keyword :any]]])

(def UpdateCard
  [:map
   [:type [:= :srs/update-card]]
   [:card/id :uuid]
   [:patch [:map-of :keyword :any]]])

(def ReviewCard
  [:map
   [:type [:= :srs/review-card]]
   [:card/id :uuid]
   [:grade [:enum :again :hard :good :easy]]
   [:ts inst?]
   [:latency-ms pos-int?]])

(def ScheduleCard
  [:map
   [:type [:= :srs/schedule-card]]
   [:card/id :uuid]
   [:due inst?]
   [:interval-days pos-int?]
   [:ease float?]])
```
- `create-card` compiles to `create-node` (card + content nodes) plus `place` under deck, optionally `create-node` for media children. 
- `update-card` maps to a single `update-node` on the card node with front/back changes (content node text uses `update-node` on that child).
- `review-card` emits `create-node` for a `:review` child and `update-node` for scheduling metadata; `schedule-card` is pure metadata update.
- Undo/redo uses log replay: keep cursor in derived `:log/cursor`; undo walks log backward creating inverse intents (e.g. reverse of normalized ops) or replays to previous hash; redo steps forward. Because storage is append-only, actual undo materializes in-memory by replaying up to cursor; optional `:snapshot` entries can be added to log for faster restore.
- Provide intent compiler multimethod:
```clojure
(defmulti compile-intent :type)

(defmethod compile-intent :srs/create-card [{:keys [card/id deck/id front back card/type props]} db]
  (let [card-node {:op :create-node :id card/id :type :card :props (merge props {:deck/id deck/id :card/type card/type})}
        content-id (uuid)
        content-node {:op :create-node :id content-id :type :card-content :props {:front front :back back}}
        place-card {:op :place :id card/id :under deck/id :at :last}
        place-content {:op :place :id content-id :under card/id :at :last}]
    [card-node content-node place-card place-content]))
```
- All 4 SRS intents have similar compilers; plugins contribute new intent types via `defmethod`.

**Plugin Surface**
- Provide `srs.plugin/register-card-type` that accepts `{::type :cloze ::schema … ::compiler (fn [db intent] …) ::render (fn [card] …)}`. Under the hood it `swap!`s a registry consulted by `compile-intent`.
- Card-type plugin can add derived-index enrichers by calling `core.derive/register!` with additional scanners; the base kernel remains unchanged.
- For image-occlusion cards: plugin stores overlays in separate `:media` children referencing an SVG snippet or bounding boxes (e.g. `{:op :create-node :id media-id :type :media :props {:media/type :image-occlusion :asset-path … :mask [{:rect …}]}}`). Rendering logic lives outside kernel; the kernel just keeps structured data.

**Markdown ↔ State Reconciliation**
- File watcher parses frontmatter (`yaml/parse-string`). Compute diff against current card props (via derived index keyed by markdown path). Differences compile to intents: new card → `:srs/create-card`, prop change → `:srs/update-card`.
- During save from UI, write to markdown by regenerating frontmatter/body from current canonical DB (idempotent) **after** log append succeeds.
- Conflicts resolved by log precedence: if markdown edit arrives with stale hash, queue `:srs/reload-from-markdown` intent that re-imports file and logs reconciliation operations for audit.

**Append-Only Log & Undo**
- On every successful transaction:
  1. Normalize intent(s).
  2. Interpret to get `{:db new-db :ops normalized-ops}`.
  3. Persist log row with intent metadata + resulting ops.
  4. Optionally persist markdown delta if source was markdown edit.
- Undo uses log cursor: `:undo` intent finds previous reversible entry, compiles inverse ops by walking logged `:ops` (e.g. `:create-node` → `:delete-node` via move to `:trash` or `:place` to old parent tracked in log). Because kernel already supports moving to trash, we reuse `place` and `update-node`.
- Redo simply replays stored `:ops`. Because log is append-only, redo writes a new entry referencing original via `:log/redo-of`.

**Scheduler**
- Mock scheduler runs outside kernel; uses `:due-index` to pull cards and emits `:srs/schedule-card` intents with canned intervals `[1 3 7 14 30]` keyed off `:grade`. Keep it pure: scheduler reads derived index, returns intents; interpreter handles persistence.

**Image-Occlusion Flow**
- Markdown frontmatter contains references: 
```yaml
type: image-occlusion
media:
  - id: img-123
    path: media/heart.png
    overlays:
      - mask-id: occl-1
        rect: [120 200 80 30]
```
- During import, plugin turns each overlay into child nodes under `:media` node for precise diffs; card review UI composes overlays by reading derived `:media/by-card` index (`{:card-id -> [{:mask-id …}]}`).
- `review-card` intent includes mask results in payload; plugin may append them to review node props.

**Workflow Integration**
- `scripts/import-markdown` reads files, emits intents, and checks results against derived indexes. `scripts/export-markdown` regenerates markdown from DB when UI modifies cards.
- Tests target intent compilation and log replay: unit-test Malli schemas + derived indexes (`:due-index` invariants). Undo/redo tests use log fixture to replay sequences and assert canonical state hash stability.

**Next Steps**
1. Prototype Malli schemas and intent compilers in `src/srs/`.
2. Implement log writer/reader with checkpointing.
3. Wire markdown watcher to emit intents and validate reconciliation path.
4. Add sample plugin (basic + image-occlusion) to exercise extension points.
[2025-10-05T17:52:31] tokens used: 12,624
