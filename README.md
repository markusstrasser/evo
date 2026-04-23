# evo

ClojureScript outliner with a tiny tree algebra.

`TLDR`: Evo is a structural text editor in the family of outliners or tree-based text editors. *You can try it here* (link). I evolved the kernel, renderer and plugin system a few dozen times. This was in 2023/2024 before workable coding agents!  The final source code is around ~18K LoC and is the predecessor of another archived Clojure repo and various, wasted Javascript/Svelte prototypes.

**I did not set out to make a text editor**.  It fell out of a much larger idea that I now see as moot: user interfaces that evolve directly from user events[^1] ...or "differential interfaces"

And although I realized these ideas as moot, the **evo interface contract is perfect for agents to evolve and extend it live** via sending or creating intents as pure maps. The UI is also evolvable live via the Clojure REPL and agents are great at using it.

I conceived the kernel and plugin sys more as an **MLIR (Multi-Level Intermediate Representation)** for DOM/Tree editing. Although compilation and UI interactions don’t map perfectly.

## What it does

- **Rich Text editing** as in any markdown editor in each node of the outliner tree
- **Nested blocks** with indent/outdent, drag & drop, and fold
- **Inline markdown** — `**bold**`, `_italic_`, `==highlight==`, `~~strike~~`, `$inline math$`, `$$block math$$`
- **Wiki-style refs** `[[Page Name]]` and markdown links `[label](target)`, including custom `evo://` schemes: `evo://page/<name>` and `evo://journal/<iso-date>`
- **Inline images** `![alt](path){width=N}` with paste-from-clipboard upload, resize handles, and a lightbox
- **Math via MathJax** with a scanner contract that refuses to typeset prose dollars (see `.claude/rules/global-dom-scanners.md`)
- **Multi-select**, **undo/redo** over the full log, **autocomplete** for page refs and slash-commands, **backlinks** panel
- **Local-first persistence** to a folder you pick; no server, no account

### Design decisions

- **Tree DB, plus derived indexes works better than positional schemes.** The current shape in [`src/kernel/db.cljc`](src/kernel/db.cljc) is parent-owned child vectors plus derived indexes such as `:parent-of`, `:next-id-of`, and `:index-of`.
- **The edit algebra is only three ops.** I explicitly removed extra structural primitives and kept `create-node`, `place`, and `update-node` as the whole mutation surface. See [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc), [`docs/GOALS.md`](docs/GOALS.md).
- **Reads are centralized.** [`src/kernel/query.cljc`](src/kernel/query.cljc) is the explicit read surface.
- **Session state moved out of the DB.** Cursor, selection, folding, autocomplete, and edit-mode state live in [`src/shell/view_state.cljs`](src/shell/view_state.cljs), while the persistent document graph stays in [`src/kernel/db.cljc`](src/kernel/db.cljc).
- **Uncontrolled editing replaces keystroke-by-keystroke DB writes.** Although it’s technically not purely functional programming: the browser owns contenteditable during edit mode, the buffer mirrors high-velocity text … commits happen at the controlled boundary. Main implementation: [`src/components/block.cljs`](src/components/block.cljs) and [`src/shell/view_state.cljs`](src/shell/view_state.cljs).

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
- **Domains don't abstract.** Text, video, audio, CAD share architectural *principles* but not *primitives*. Universality is a mirage.

## Non-goals

- **Not a PKM**: structured-note-taking is a low RoI nerd trap.
- **Not a packaged library.** No Clojars artifact, no API stability. People can reuse the kernel if it fits other domains.
- **Not trying to have parity with text editors**: I wanted to have the full “structural editing spec” of other outliners but not more. I did not implement block embeds and page embeds by design: they are counterproductive in general.

## Spec as contract

- The persistent model is a small tree-plus-derived-indices database. See [`src/kernel/db.cljc`](src/kernel/db.cljc).
- All durable changes reduce to three ops: `create-node`, `place`, `update-node`. See [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc).
- The transaction (tx) pipeline is the real contract: `normalize -> validate -> apply -> derive`.
- Ephemeral UI state stays out of the document DB. Cursor, selection, folding, autocomplete, and edit-mode state live in [`src/shell/view_state.cljs`](src/shell/view_state.cljs), not in [`src/kernel/db.cljc`](src/kernel/db.cljc). This is not pure FP per se but a perf compromise

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

The stored DB stays small: nodes, parent-owned child vectors, roots. Everything else is re-derived after each transaction.

That is what makes reads cheap without pushing more state into the canonical model. Queries such as `(q/parent-of db id)` and `(q/next-sibling db id)` hit precomputed maps like `:parent-of`, `:index-of`, `:prev-id-of`, and `:next-id-of`. Because derivation runs inside the transaction pipeline, the UI never sees stale indexes. Plugins can also register their own derived views; backlinks are the main example.

### Plugins

Plugins compile intents into a vector of ops. They read the current DB, decide what should happen, and return data for the kernel to apply. Indent is a simple example. The handler looks up the previous sibling of `node-B`, decides `node-B` should move under `node-A`, and emits:

```clojure
{:op :place :id "node-B" :under "node-A" :at :last}
```

That is the boundary. Plugins do not mutate the DB directly, do not bypass the transaction pipeline, and do not share hidden state. The useful mental model is `(db, intent) -> {:ops ... :session-updates ...}`.

The point is that more complex features still collapse to the same shape. Page refs, backlinks, and graph-ish features are not special kernel machinery. A plugin can interpret a ref-oriented intent, emit normal ops, and a derived-index plugin can materialize the extra view over the same document graph. The kernel still just sees nodes, placements, updates, and re-derived indexes.



## Architecture

At runtime the path is: intent map -> plugin handler -> three-op transaction -> canonical DB + derived indexes -> parser/render -> DOM. [`src/kernel/`](src/kernel/) owns the document machine. [`src/plugins/`](src/plugins/) compiles intents into ops and session updates. [`src/shell/`](src/shell/) wires the browser/runtime path. [`src/components/`](src/components/) owns UI behavior. [`src/parser/`](src/parser/) turns text into AST, and [`src/shell/render/`](src/shell/render/) turns AST tags into hiccup.

The extension surface is explicit: [`kernel.intent/register-intent!`](src/kernel/intent.cljc), [`kernel.derived-registry/register!`](src/kernel/derived_registry.cljc), and [`shell.render-registry/register-render!`](src/shell/render_registry.cljc). All three follow the same shape: a `defonce` atom, a registration function that validates and swaps in a map entry, and a dispatch path. Re-registering the same key replaces the old handler, which keeps hot reload and test fixtures idempotent. Bootstrapping goes through [`src/plugins/manifest.cljc`](src/plugins/manifest.cljc), [`src/shell/render_manifest.cljc`](src/shell/render_manifest.cljc), and [`src/shell/editor.cljs`](src/shell/editor.cljs).

The intent registry maps intent keywords such as `:indent`, `:navigate-to-page`, and `:collapse` to validated handlers that return `{:ops ... :session-updates ...}`. Those ops flow through [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc); session updates land in [`src/shell/view_state.cljs`](src/shell/view_state.cljs). The derived-index registry owns materialized views under `db[:derived]`; [`src/plugins/backlinks_index.cljc`](src/plugins/backlinks_index.cljc) is the canonical example. The render registry maps AST tags to pure hiccup handlers; unknown tags throw instead of silently degrading. Rule of thumb: new inline syntax means parser + render work, new editing behavior means plugin work, and kernel edits are only for changes to the document machine itself.

Two non-registry layers matter. [`src/shell/view_state.cljs`](src/shell/view_state.cljs) holds ephemeral UI state that should not enter the undo log: cursor, selection, fold set, editing block id, drag state. [`src/shell/log.cljs`](src/shell/log.cljs) is the append-only transaction journal used for undo/redo, persistence, and replay.

A typical feature spans registries without crossing boundaries. Backlinks are the concrete example:

- derived index in [`src/plugins/backlinks_index.cljc`](src/plugins/backlinks_index.cljc)
- UI in [`src/components/backlinks.cljs`](src/components/backlinks.cljs)
- navigation intent in [`src/plugins/pages.cljc`](src/plugins/pages.cljc)
- page-ref render handler in [`src/shell/render/page_ref.cljs`](src/shell/render/page_ref.cljs)

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

## Breakdown

| Area                                 | Code LoC | What lives there                                             |
| ------------------------------------ | -------: | ------------------------------------------------------------ |
| [`src/components/`](src/components/) |    ~4.3k | UI, especially [`block.cljs`](src/components/block.cljs)     |
| [`src/plugins/`](src/plugins/)       |    ~3.7k | Intent handlers and derived-index plugins                    |
| [`src/kernel/`](src/kernel/)         |    ~3.2k | Pure document model, ops, transaction pipeline, queries      |
| [`src/shell/`](src/shell/)           |    ~3.0k | Runtime wiring: startup, storage, executor, view state, URL sync |
| [`src/utils/`](src/utils/)           |    ~1.8k | DOM, text, cursor, image, and helper code                    |
| [`src/spec/`](src/spec/)             |    ~0.8k | FR/spec runner and registry glue                             |
| [`src/parser/`](src/parser/)         |    ~0.8k | Inline text parsing into AST nodes                           |
| [`src/scripts/`](src/scripts/)       |    ~0.4k | Scratch-DB multi-step edits                                  |
| [`src/keymap/`](src/keymap/)         |    ~0.2k | Keybinding tables and dispatch glue                          |

The center of gravity is the editor path. Core behavior is mostly [`src/kernel/`](src/kernel/) plus [`src/plugins/`](src/plugins/).



## Project layout

```
src/kernel/         Pure kernel: db, ops, transaction, schema, query. Zero UI imports.
src/parser/         Text -> AST. parse.cljc does page refs, images, links, inline format.
src/plugins/        Intent handlers + derived-index plugins. Manifest bootstraps both.
src/shell/          Runtime wiring, session state, render registry & handlers, storage, executor.
src/components/     UI (Block, Sidebar, Backlinks, Image, Lightbox, ...).
src/keymap/         Keybindings & dispatch.
src/scripts/        Multi-step operations that need simulation (smart backspace, paste).
src/spec/           FR registry loading, tree DSL, spec runner.
test/               Unit, property, integration (ClojureScript) + Playwright E2E.
resources/          FR registry (specs.edn), failure modes, seed data.
public/             index.html, styles.css, MathJax shim, build output.
docs/               STRUCTURAL_EDITING.md, RENDERING_AND_DISPATCH.md, TESTING.md, etc.
```

## Tests

```bash
bb test                       # full unit/property suite (CLJS via shadow-cljs)
bb test:view                  # hiccup-only tier (<1s)
bb test:kernel                # kernel purity + script tests
bb check                      # lint + arch verification + compile
npm run test:e2e:smoke        # Playwright smoke (~10s)
npm run test:e2e              # full Playwright suite (~4min)
```

## Appendix / Footer / Refs

[1] This is out of scope, but in short: Creative interfaces are better designed than evolved/interpolated. There's only a handful of creative modalities and a few dozen decades-old, proven primitives. We've seen close to no changes to these. Yes, there's might be subdomain niches in the creative tooling space and sure, it's technically possible to evolve an image editor into a game engine purely via AI driven iteration (user-intent/events as signal and UI patches as variants/tests) but it's the wrong paradigm and shouldn't be the outermost control loop. At best this kind of live meta-iteration is as embed inside larger, stable paradigms.

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

- **Logseq**: Closest on outliner semantics, but its core mutations ride on Datascript transactions and app-level outliner ops. Evo makes the mutation algebra itself smaller and more explicit.
- **ProseMirror**: Centers on schema, transactions, and `Step` transforms over a rich document model. Evo keeps a simpler tree DB and pushes more behavior into plugin compilation down to a few ops.
- **Slate**: Also operation-based, but the center of gravity is the mutable `Editor` object plus normalization/history plugins. Evo puts those semantics in a standalone kernel instead of editor-instance methods.
- **Tiptap**: Mainly an extension layer over ProseMirror's transaction and plugin system. Evo owns the kernel directly instead of wrapping another editor core.
- **xi-editor**: Strong core/plugin split too, but for a rope-based text engine with RPC plugins. Evo is a structural tree kernel first, not a text-buffer architecture.
