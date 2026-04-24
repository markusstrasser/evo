# evo

ClojureScript outliner with a tiny tree algebra.

Evo is a working structural text editor: nested blocks, inline markdown, page refs, images, math, multi-select, undo/redo, backlinks, and local-folder persistence. You can try it here: [markusstrasser.org/evo-demo](https://markusstrasser.org/evo-demo).

I evolved the kernel, renderer, and plugin system a few dozen times in 2023/2024, before coding agents were useful for this kind of work. The current source is about 18K LoC. It also led to another archived Clojure repo and several discarded JavaScript/Svelte prototypes.

**I did not set out to make a text editor.** Evo fell out of a larger experiment: could user interfaces change directly from user events?

I now think that idea was too broad. Creative tools need stable primitives more than they need live meta-evolution. But the experiment left behind something useful: a small outliner kernel with a narrow mutation surface.

The agent angle matters because Evo exposes editor behavior as data.

A user action becomes an intent map. A plugin turns that intent into ops. The kernel validates and applies those ops through one transaction path. That gives coding agents a clean place to work: change intent handlers, inspect the emitted ops, and test the result without inventing DOM behavior from scratch.

I used to think of the kernel and plugin system as something like an IR for tree editing. That analogy is too heavy.

The simpler version is this: Evo compiles editor behavior down to three document operations: `create-node`, `place`, and `update-node`.

## What it does

| Area | Support |
| --- | --- |
| Blocks | nesting, indent/outdent, drag/drop, fold |
| Inline text | `**bold**`, `_italic_`, `==highlight==`, `~~strike~~` |
| Links and refs | `[[Page Name]]`, `[label](target)`, `evo://page/<name>`, `evo://journal/<iso-date>` |
| Media | images with paste upload, resize handles, lightbox |
| Math | `$inline math$`, `$$block math$$` through MathJax |
| Editor state | multi-select, undo/redo, autocomplete, backlinks |
| Persistence | local folder, no server, no account |

### Design decisions

- **The DB stores a tree, not positions.** [`src/kernel/db.cljc`](src/kernel/db.cljc) stores parent-owned child vectors. The transaction pipeline derives lookup maps such as `:parent-of`, `:next-id-of`, and `:index-of`.

  ```clojure
  {:children-by-parent {:doc ["a" "b"]
                        "a" ["c"]}
   :derived {:parent-of {"a" :doc
                         "b" :doc
                         "c" "a"}
             :index-of {"a" 0
                        "b" 1
                        "c" 0}}}
  ```

- **The edit algebra is only three ops.** I explicitly removed extra structural primitives and kept `create-node`, `place`, and `update-node` as the whole mutation surface. See [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc), [`docs/GOALS.md`](docs/GOALS.md).
- **A small mutation surface is easier to audit.** Undo/redo, tests, logs, and debugging all get simpler when every structural change has to pass through the same tiny vocabulary.
- **Reads are centralized.** [`src/kernel/query.cljc`](src/kernel/query.cljc) is the explicit read surface.
- **Session state moved out of the DB.** Cursor, selection, folding, autocomplete, and edit-mode state live in [`src/shell/view_state.cljs`](src/shell/view_state.cljs), while the persistent document graph stays in [`src/kernel/db.cljc`](src/kernel/db.cljc).
- **The browser owns text while you type.** Evo does not write every keystroke into the DB; that path causes cursor and render churn. During edit mode, `contenteditable` owns the live text and the view-state buffer mirrors it. Evo commits back to the document graph at controlled boundaries. Main implementation: [`src/components/block.cljs`](src/components/block.cljs) and [`src/shell/view_state.cljs`](src/shell/view_state.cljs).

## Prerequisites

You need:

- Node.js + npm
- a JDK for `shadow-cljs`
- Babashka if you want to use the `bb` task runner

macOS:

```bash
brew install node openjdk babashka
```

Debian/Ubuntu:

```bash
sudo apt install nodejs npm default-jdk
# install babashka separately if you want bb tasks
```

## Quick start

```bash
npm install
npm start             # clean build + watch CLJS + watch CSS
# → http://localhost:8080/blocks.html
```

`npm run dev:fast` skips the clean step when caches are healthy. `npm run build` produces a release build into `public/js/blocks-ui`.

## Principles

- **Kernel is pure.** Zero imports from `shell/`, `components/`, `keymap/` in `src/kernel/`.
- **Three-operation (ops) invariant.** DB mutations reduce to `create-node`, `place`, `update-node`.
- **Data-driven dispatch.** Intents are EDN maps.
- **Session state separate from DB.** Ephemeral UI state lives in the session atom, not polluting the persistent doc.
- **No universal editor primitive.** Evo abstracts over tree editing, not text, video, audio, CAD, or every creative domain.

## Non-goals

- **Not a PKM product.** Structured note-taking is a low-ROI trap for this project. Evo cares about the outliner mechanics, not the life system around them.
- **Not a packaged library.** There is no Clojars artifact and no API stability promise. Reuse the kernel if it fits, but this repo does not pretend to be a stable dependency.
- **Not full editor parity.** Evo aims for a solid structural editing spec, not every feature from rich-text editors or PKM apps. I skipped block embeds and page embeds on purpose.

## Spec as contract

- [`src/kernel/db.cljc`](src/kernel/db.cljc) stores the persistent document graph.
- [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc) owns the write path.
- All durable changes reduce to three ops: `create-node`, `place`, `update-node`.
- The transaction contract is: `normalize -> validate -> apply -> derive`.
- [`src/shell/view_state.cljs`](src/shell/view_state.cljs) owns cursor, selection, folding, autocomplete, and edit mode. That state changes too often to belong in the replayable document DB.

Canonical DB shape:

```clojure
{:nodes {"a" {:type :block :props {:text "I am a node"}}}
 :children-by-parent {:doc ["a"]}
 :roots [:doc :trash]
 :derived {:parent-of {"a" :doc}
           :next-id-of {}
           :prev-id-of {}
           :index-of {"a" 0}}}
```

Kernel ops:

```clojure
{:op :create-node :id "a" :type :block :props {:text "I am a node"}}
{:op :place       :id "a" :under :doc :at :last}
{:op :update-node :id "a" :props {:text "being updated"}}
```

### Derived indexes

The DB stores only the source facts:

```clojure
:nodes
:children-by-parent
:roots
```

The transaction pipeline derives the lookup maps after each write:

```clojure
:parent-of
:index-of
:prev-id-of
:next-id-of
```

Queries such as `(q/parent-of db id)` and `(q/next-sibling db id)` read those maps. Plugins can add derived views too; backlinks use that path.

### Plugins

Plugins turn user intent into kernel ops.

Example intent:

```clojure
{:type :indent
 :id "node-B"}
```

The indent plugin reads the current tree, finds `node-B`'s previous sibling, and emits one placement:

```clojure
{:op :place :id "node-B" :under "node-A" :at :last}
```

That is the boundary:

```clojure
(db, intent) -> {:ops [...]
                 :session-updates {...}}
```

Plugins do not mutate the DB. They return data. The executor sends that data through the transaction pipeline and applies session updates separately.

More complex features still use the same path. Page refs and backlinks do not get special kernel machinery. Plugins interpret the intent, emit normal ops, and add derived views when they need faster reads.

## Architecture

Runtime path:

```text
DOM event
  -> intent map
  -> plugin handler
  -> kernel ops
  -> transaction pipeline
  -> canonical DB + derived indexes
  -> parser/render
  -> DOM
```

[`src/kernel/`](src/kernel/) owns the document machine. [`src/plugins/`](src/plugins/) compiles intents into ops and session updates. [`src/shell/`](src/shell/) wires the browser/runtime path. [`src/components/`](src/components/) owns UI behavior. [`src/parser/`](src/parser/) turns text into AST, and [`src/shell/render/`](src/shell/render/) turns AST tags into hiccup.

The extension surface has three registries:

| Registry | Adds | File |
| --- | --- | --- |
| Intent | editing/navigation behavior | [`kernel.intent/register-intent!`](src/kernel/intent.cljc) |
| Derived index | materialized read views | [`kernel.derived-registry/register!`](src/kernel/derived_registry.cljc) |
| Render | AST tag rendering | [`shell.render-registry/register-render!`](src/shell/render_registry.cljc) |

Each registry has the same shape: a `defonce` atom, a validating registration function, and a dispatch path. Re-registering a key replaces the old handler, which keeps hot reload and test fixtures simple. Bootstrapping goes through [`src/plugins/manifest.cljc`](src/plugins/manifest.cljc), [`src/shell/render_manifest.cljc`](src/shell/render_manifest.cljc), and [`src/shell/editor.cljs`](src/shell/editor.cljs).

The intent registry maps intent keywords such as `:indent`, `:navigate-to-page`, and `:collapse` to validated handlers that return `{:ops ... :session-updates ...}`. Those ops flow through [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc); session updates land in [`src/shell/view_state.cljs`](src/shell/view_state.cljs). The derived-index registry owns materialized views under `db[:derived]`; [`src/plugins/backlinks_index.cljc`](src/plugins/backlinks_index.cljc) is the canonical example. The render registry maps AST tags to pure hiccup handlers; unknown tags throw instead of silently degrading.

Where a change belongs:

| Change | Touch |
| --- | --- |
| New inline syntax | parser + render handler |
| New editing behavior | plugin intent handler |
| New materialized read view | derived-index plugin |
| New document invariant | kernel |

Two non-registry files matter:

| File | Owns |
| --- | --- |
| [`src/shell/view_state.cljs`](src/shell/view_state.cljs) | cursor, selection, folds, edit mode, drag state |
| [`src/shell/log.cljs`](src/shell/log.cljs) | append-only transaction journal |

Undo, persistence, and replay all read the same transaction history. They are not separate subsystems.

[`src/scripts/`](src/scripts/) handles edits where one step needs the result of a previous step.

A script runs against a scratch DB, collects normalized ops, and commits once. The runtime still sees one atomic edit.

Backlinks show the split:

| Part | File |
| --- | --- |
| Derived index | [`src/plugins/backlinks_index.cljc`](src/plugins/backlinks_index.cljc) |
| UI panel | [`src/components/backlinks.cljs`](src/components/backlinks.cljs) |
| Navigation intent | [`src/plugins/pages.cljc`](src/plugins/pages.cljc) |
| Page-ref rendering | [`src/shell/render/page_ref.cljs`](src/shell/render/page_ref.cljs) |

Inline content is parsed into a uniform AST shape:

```clojure
[:tag {attrs} content]
```

Common tags include `:doc`, `:text`, `:bold`, `:italic`, `:highlight`, `:strikethrough`, `:math-inline`, `:math-block`, `:link`, `:page-ref`, and `:image`. See [`src/parser/ast.cljc`](src/parser/ast.cljc).

Where things go:

- components render views and panels; they do not parse content or own per-tag logic
- render handlers live per AST tag, not per block-level mode
- session state does not go in the document DB
- [`src/kernel/`](src/kernel/) must not import `src/shell/`, `src/components/`, or `src/keymap/`

## Project layout

The center of gravity is the editor path: [`src/kernel/`](src/kernel/) plus [`src/plugins/`](src/plugins/).

| Path | Approx. LoC | Owns |
| --- | ---: | --- |
| [`src/components/`](src/components/) | ~4.3k | UI, especially [`block.cljs`](src/components/block.cljs) |
| [`src/plugins/`](src/plugins/) | ~3.7k | intent handlers and derived-index plugins |
| [`src/kernel/`](src/kernel/) | ~3.2k | pure document model, ops, transaction pipeline, queries |
| [`src/shell/`](src/shell/) | ~3.0k | startup, storage, executor, view state, URL sync |
| [`src/utils/`](src/utils/) | ~1.8k | DOM, text, cursor, image, helper code |
| [`src/spec/`](src/spec/) | ~0.8k | FR/spec runner and registry glue |
| [`src/parser/`](src/parser/) | ~0.8k | inline text parsing into AST nodes |
| [`src/scripts/`](src/scripts/) | ~0.4k | scratch-DB multi-step edits |
| [`src/keymap/`](src/keymap/) | ~0.2k | keybinding tables and dispatch glue |
| [`test/`](test/) | - | unit, property, integration, and Playwright E2E tests |
| [`resources/`](resources/) | - | FR registry, failure modes, seed data |
| [`public/`](public/) | - | HTML, CSS, MathJax shim, build output |
| [`docs/`](docs/) | - | structural editing, rendering, dispatch, testing docs |

## Tests

```bash
bb test                       # full unit/property suite (CLJS via shadow-cljs)
bb test:view                  # hiccup-only tier (<1s)
bb test:kernel                # kernel purity + script tests
bb check                      # lint + arch verification + compile
npm run test:e2e:smoke        # Playwright smoke (~10s)
npm run test:e2e              # full Playwright suite (~4min)
```

## Notes and References

[1] The longer version: I no longer think creative tools should evolve from raw event streams. Creative work depends on stable primitives. You can use AI to patch small parts of a tool, but the outer loop still needs a designed interface, a clear domain model, and tests.

### References

- Ben Shneiderman, “Direct Manipulation: A Step Beyond Programming Languages” (1983)  
  https://www.cs.umd.edu/users/ben/papers/Shneiderman1983Direct.pdf
- Brad A. Myers, “A Brief History of Human Computer Interaction Technology” (1998)  
  https://www.cs.cmu.edu/~amulet/papers/uihistory.tr.html
- Ben Shneiderman, “Creativity Support Tools: Accelerating Discovery and Innovation” (2007)  
  https://www.cs.umd.edu/users/ben/papers/Shneiderman2007Creativity.pdf
- Donald A. Norman, *The Design of Everyday Things*, revised and expanded edition (2013)  
  https://jnd.org/books/the-design-of-everyday-things-revised-and-expanded-edition/
- Donald A. Norman, *Things That Make Us Smart: Defending Human Attributes in the Age of the Machine* (1994)  
  https://jnd.org/books/things-that-make-us-smart-defending-human-attributes-in-the-age-of-the-machine/
- Donald T. Campbell, “Assessing the Impact of Planned Social Change” (1976/1979)  
  https://www.humanlearning.systems/uploads/08%20Assessing%20the%20Impact%20of%20Planned%20Social%20Change.pdf

### Related

These comparisons are about architecture, not product scope:

- **Logseq**: Closest on outliner semantics, but its core mutations ride on Datascript transactions and app-level outliner ops. Evo makes the mutation algebra itself smaller and more explicit.
- **ProseMirror**: Centers on schema, transactions, and `Step` transforms over a rich document model. Evo keeps a simpler tree DB and pushes more behavior into plugin compilation down to a few ops.
- **Slate**: Also operation-based, but the center of gravity is the mutable `Editor` object plus normalization/history plugins. Evo puts those semantics in a standalone kernel instead of editor-instance methods.
- **Tiptap**: Mainly an extension layer over ProseMirror's transaction and plugin system. Evo owns the kernel directly instead of wrapping another editor core.
- **xi-editor**: Strong core/plugin split too, but for a rope-based text engine with RPC plugins. Evo is a structural tree kernel first, not a text-buffer architecture.
