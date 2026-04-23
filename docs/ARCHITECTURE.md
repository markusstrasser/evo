# Architecture

Evo is a small ClojureScript outliner whose persistent state is an event-sourced tree of blocks. Features are added by registering handlers with one of three registries; nothing in the kernel knows about any of them.

## The three registries

All three follow the same shape: a `defonce` atom, a `register-X!` function that validates and swaps in a map entry, and a dispatch fn. Re-registering the same key replaces — idempotent for hot reload and test fixtures.

### Intent registry — `kernel.intent`

Maps intent keywords (`:indent`, `:navigate-to-page`, `:collapse`, …) to Malli-validated handlers that return operations or session updates.

```clojure
(intent/register-intent! :navigate-to-page
  {:spec    [:map [:type [:= :navigate-to-page]] [:page-name :string]]
   :handler (fn [db session {:keys [page-name]}] …)})
```

A handler returns `{:ops [...] :session-updates {…}}`. Ops go through `kernel.transaction/interpret`; session-updates land on the view-state atom. See `src/plugins/` for the live manifest.

### Derived-index registry — `kernel.derived-registry`

Per-index computation of materialized views kept on `db[:derived]`. Each plugin exposes `{:initial (fn [db] → {k v})}` and optionally an `:apply-tx` incremental path that must agree with `:initial`. See `src/plugins/backlinks_index.cljc` for the canonical example.

### Render registry — `shell.render-registry`

Maps AST tag keywords to pure render handlers `(fn [node ctx] → hiccup)`. The block content pipeline is:

```
block text
  → parser.parse/parse → [:doc {} [<children>]]
  → render-registry/render-node          ; dispatches on each tag
  → hiccup children splatted into <span.block-content>
```

Unknown tags throw; there is no plain-text fallback, because an unknown tag is a bug, not content. See `src/shell/render/*`.

## The two non-registry layers

### Session atom — `shell.view-state`

Ephemeral UI state that must not be part of the undo log: cursor, selection, zoom stack, fold set, editing-block id, drag state. Queries live in `kernel.query` with an explicit session argument (`(q/selection session)`); mutations are named functions on `shell.view-state`.

### Event log — `shell.log`

Append-only journal of every transaction. Undo/redo replays the log; persistence writes through it; time-travel debugging reads from it. The canonical DB at any moment is `(reduce tx/interpret root-db log)`.

## How a feature is structured — backlinks

A typical feature spans multiple registries. Backlinks:

- **Derived index** in `plugins.backlinks-index` — per-page list of incoming `[[refs]]`, recomputed after every transaction.
- **UI component** in `components.backlinks` — reads the index and renders a list.
- **Intent** `:navigate-to-page` in `plugins.pages` — clicking a backlink fires this.
- **Render handler** for `:page-ref` in `shell.render.page-ref` — turns the AST node into a clickable anchor.

No file crosses a registry boundary; adding a new feature means writing the pieces that belong to the registries that need them.

## AST shape

Every block's inline content is parsed into a uniform 3-element hiccup vector:

```clojure
[:tag {attrs} content]
```

- Position 0: tag keyword (one of `#{:doc :text :bold :italic :highlight :strikethrough :math-inline :math-block :link :page-ref :image}`)
- Position 1: attrs map (`{:marker "**"}`, `{:target "evo://…"}`, `{:path "…" :alt "…" :width N}`, etc.)
- Position 2: string content (leaves: text, math) or a vector of child nodes

See `src/parser/ast.cljc` for the spec, walking helpers, and the inverse `render-as-source` used for the round-trip property test.

## Where NOT to put things

- **Components render panels and views.** They do not parse content and do not know about individual tags — they call `render-registry/render-node` or ship a whole-block embed (e.g. image-only blocks).
- **Render handlers live per AST tag.** Not per block-level format (heading/quote/embed) — those affect the *container*, not inline content.
- **Session state never goes in the DB.** No `:ui` key in the document graph; use the view-state atom instead.
- **The kernel has zero UI imports.** `src/kernel/` must not require anything from `src/shell/`, `src/components/`, or `src/keymap/`. `bb check` enforces this.
