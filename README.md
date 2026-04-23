# evo

ClojureScript outliner with a tiny tree algebra.

`TLDR`: Evo is a structural text editor in the family of outliners or tree-based text editors. *You can try it here* (link). I evolved the kernel, renderer and plugin system a few dozen times. This was in 2023/2024 before workable coding agents!  The final source code is around ~18K LoC and is the predecessor of another archived Clojure repo and various, wasted Javascript/Svelte prototypes. 

**I did not set out to make a text editor**.  It fell out of a much larger idea that I now see as moot: user interfaces that evolve directly from user events[^1] ...or "differential interfaces" 

And although I realized these ideas as moot, the evo interface is perfect for agents to evolve and extend it live via sending or creating intents (or the Clojure REPL). 

## What it does

- **Nested blocks** with indent/outdent, drag & drop, and fold
- **Pages and daily journals** (Logseq-compatible title format: `Apr 22nd, 2026`); journals open stacked on startup
- **Inline markdown** — `**bold**`, `_italic_`, `==highlight==`, `~~strike~~`, `$inline math$`, `$$block math$$`, with word-boundary guards so code like `cljs$core$key` stays literal
- **Wiki-style refs** `[[Page Name]]` and markdown links `[label](target)`, including custom `evo://` schemes: `evo://page/<name>` and `evo://journal/<iso-date>`
- **Inline images** `![alt](path){width=N}` with paste-from-clipboard upload, resize handles, and a lightbox
- **Math via MathJax** with a scanner contract that refuses to typeset prose dollars (see `.claude/rules/global-dom-scanners.md`)
- **Multi-select**, **undo/redo** over the full log, **autocomplete** for page refs and slash-commands, **backlinks** panel
- **Local-first persistence** to a folder you pick; no server, no account

## Quick start

```bash
npm install
npm start             # clean build + watch CLJS + watch CSS
# → http://localhost:8080/blocks.html
```

`npm run dev:fast` skips the clean step when caches are healthy. `npm run build` produces a release build into `public/js/blocks-ui`.

## Scope

- Outliner, not PKM platform.
- Repo, not library product.
- Explicit state model, small mutation surface, local-first storage.
- Current project stance: [`docs/GOALS.md`](docs/GOALS.md), [`AGENTS.md`](AGENTS.md).

## Principles

- **Kernel is pure.** Zero imports from `shell/`, `components/`, `keymap/` in `src/kernel/`.
- **Three-op invariant.** DB mutations reduce to `create-node`, `place`, `update-node`.
- **Data-driven dispatch.** Intents are EDN maps. Handlers register into data registries, not multimethod-as-main-dispatch (multimethods fine for local sub-dispatch, e.g. autocomplete).
- **Session state separate from DB.** Ephemeral UI state lives in the session atom, not polluting the persistent doc.
- **Docs are facts.** Delete executed plans, stale proposals, session artifacts. Git preserves history.
- **Domains don't abstract.** Text, video, audio, CAD share architectural *principles* but not *primitives*. Don't build toward universality — it's a mirage.

## Constraints

- **Kernel purity.** [`src/kernel/`](src/kernel/) does not import `shell/`, `components/`, or `keymap/`.
- **Three-op invariant.** Durable state changes reduce to `create-node`, `place`, and `update-node`.
- **Data-driven dispatch.** Intents are data maps. Behavior is added by registration, not by spreading conditionals through the core.
- **Session separate from DB.** Persistent document graph in the DB; ephemeral UI state in [`src/shell/view_state.cljs`](src/shell/view_state.cljs).
- **Docs are facts.** Indexed docs describe current behavior. Plans and review packets are historical material.

## Architecture

At runtime the path is: intent map -> plugin handler -> three-op transaction -> canonical DB + derived indexes -> parser/render -> Replicant DOM. [`src/kernel/`](src/kernel/) owns the document machine. [`src/plugins/`](src/plugins/) compiles intents into ops and session updates. [`src/shell/`](src/shell/) wires the browser/runtime path. [`src/components/`](src/components/) owns UI behavior. [`src/parser/`](src/parser/) turns text into AST, and [`src/shell/render/`](src/shell/render/) turns AST tags into hiccup.

The extension surface is explicit: [`kernel.intent/register-intent!`](src/kernel/intent.cljc), [`kernel.derived-registry/register!`](src/kernel/derived_registry.cljc), and [`shell.render-registry/register-render!`](src/shell/render_registry.cljc). All three follow the same shape: a `defonce` atom, a registration function that validates and swaps in a map entry, and a dispatch path. Re-registering the same key replaces the old handler, which keeps hot reload and test fixtures idempotent. Bootstrapping goes through [`src/plugins/manifest.cljc`](src/plugins/manifest.cljc), [`src/shell/render_manifest.cljc`](src/shell/render_manifest.cljc), and [`src/shell/editor.cljs`](src/shell/editor.cljs).

The intent registry maps intent keywords such as `:indent`, `:navigate-to-page`, and `:collapse` to validated handlers that return `{:ops ... :session-updates ...}`. Those ops flow through [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc); session updates land in [`src/shell/view_state.cljs`](src/shell/view_state.cljs). The derived-index registry owns materialized views under `db[:derived]`; [`src/plugins/backlinks_index.cljc`](src/plugins/backlinks_index.cljc) is the canonical example. The render registry maps AST tags to pure hiccup handlers; unknown tags throw instead of silently degrading. Rule of thumb: new inline syntax means parser + render work, new editing behavior means plugin work, and kernel edits are only for changes to the document machine itself.

Two non-registry layers matter. [`src/shell/view_state.cljs`](src/shell/view_state.cljs) holds ephemeral UI state that should not enter the undo log: cursor, selection, zoom stack, fold set, editing block id, drag state. [`src/shell/log.cljs`](src/shell/log.cljs) is the append-only transaction journal used for undo/redo, persistence, and replay.

A typical feature spans registries without crossing their boundaries. Backlinks are the concrete example:

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

## Code shape

Current `tokei src` split, using the `Code` column only (comments and blanks excluded). Regenerate with `tokei src`.

| Area | Code LoC | What lives there |
|---|---:|---|
| [`src/components/`](src/components/) | ~4.3k | Replicant UI, especially [`block.cljs`](src/components/block.cljs) |
| [`src/plugins/`](src/plugins/) | ~3.7k | Intent handlers and derived-index plugins |
| [`src/kernel/`](src/kernel/) | ~3.2k | Pure document model, ops, transaction pipeline, queries |
| [`src/shell/`](src/shell/) | ~3.0k | Runtime wiring: startup, storage, executor, view state, URL sync |
| [`src/utils/`](src/utils/) | ~1.8k | DOM, text, cursor, image, and helper code |
| [`src/spec/`](src/spec/) | ~0.8k | FR/spec runner and registry glue |
| [`src/parser/`](src/parser/) | ~0.8k | Inline text parsing into AST nodes |
| [`src/scripts/`](src/scripts/) | ~0.4k | Scratch-DB multi-step edits |
| [`src/keymap/`](src/keymap/) | ~0.2k | Keybinding tables and dispatch glue |

The center of gravity is the editor path. Core behavior is mostly [`src/kernel/`](src/kernel/) plus [`src/plugins/`](src/plugins/).

## Spec as contract

- The persistent model is a small tree-plus-indexes database. See [`src/kernel/db.cljc`](src/kernel/db.cljc).
- All durable changes reduce to three ops: `create-node`, `place`, `update-node`. See [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc).
- The transaction pipeline is the real contract: `normalize -> validate -> apply -> derive`. The code says this directly in [`kernel.transaction`](src/kernel/transaction.cljc).
- Ephemeral UI state is kept out of the document DB. Cursor, selection, folding, zoom, autocomplete, and edit-mode state live in [`src/shell/view_state.cljs`](src/shell/view_state.cljs), not in [`src/kernel/db.cljc`](src/kernel/db.cljc).

Canonical DB shape:

```clojure
{:nodes {"a" {:type :block :props {:text "Hello"}}}
 :children-by-parent {:doc ["a"]}
 :roots [:doc :trash]
 :derived {:parent-of {"a" :doc}
           :next-id-of {}
           :prev-id-of {}
           :index-of {"a" 0}}}
```

Kernel ops:

```clojure
{:op :create-node :id "a" :type :block :props {:text "Hello"}}
{:op :place       :id "a" :under :doc :at :last}
{:op :update-node :id "a" :props {:text "World"}}
```

Contract surfaces:

- [`resources/specs.edn`](resources/specs.edn)
- [`src/spec/registry.cljc`](src/spec/registry.cljc)
- [`src/spec/runner.cljc`](src/spec/runner.cljc)

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
docs/               STRUCTURAL_EDITING.md, RENDERING_AND_DISPATCH.md, TESTING.md, and more.
```

Open these first:

- [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc) — transaction pipeline
- [`src/kernel/db.cljc`](src/kernel/db.cljc) — canonical DB shape and derived indexes
- [`src/kernel/intent.cljc`](src/kernel/intent.cljc) — intent registry and validation
- [`src/shell/editor.cljs`](src/shell/editor.cljs) — app composition and startup
- [`src/shell/executor.cljs`](src/shell/executor.cljs) — canonical runtime dispatch path
- [`src/components/block.cljs`](src/components/block.cljs) — block editing/rendering hotspot
- [`src/shell/view_state.cljs`](src/shell/view_state.cljs) — ephemeral UI state
- [`src/parser/parse.cljc`](src/parser/parse.cljc) — inline parsing composition

## Testing

```bash
bb test                       # full unit/property suite (CLJS via shadow-cljs)
bb test:view                  # hiccup-only tier (<1s)
bb test:kernel                # kernel purity + script tests
bb check                      # lint + arch verification + compile
npm run test:e2e:smoke        # Playwright smoke (~10s)
npm run test:e2e              # full Playwright suite (~4min)
```

The kernel has a round-trip property test for the parser, boundary tests for cursor/selection behavior, and architecture verification that fails CI if `src/kernel/` grows a dependency on shell or view code.

## Non-goals

- **Not a PKM**: structured-note-taking is a low RoI nerd trap.
- **Not a packaged library.** No Clojars artifact, no API stability. People can reuse the kernel if it fits other domains.
- **Not trying to have parity with other outliners.** I wanted to have the full “structural editing spec” of other outliners but not more. I did not implement block embeds and page embeds by design: they are counterproductive in general. 

###  Design decisions 

- **Tree DB, plus derived indexes works better than positional schemes.** The current shape in [`src/kernel/db.cljc`](src/kernel/db.cljc) is parent-owned child vectors plus derived indexes such as `:parent-of`, `:next-id-of`, and `:index-of`.
- **The edit algebra is only three ops.** I explicitly removed extra structural primitives and kept `create-node`, `place`, and `update-node` as the whole mutation surface. See [`src/kernel/transaction.cljc`](src/kernel/transaction.cljc), [`docs/GOALS.md`](docs/GOALS.md).
- **Reads are centralized.** [`src/kernel/query.cljc`](src/kernel/query.cljc) is the explicit read surface.
- **Session state moved out of the DB.** Cursor, selection, folding, autocomplete, and edit-mode state live in [`src/shell/view_state.cljs`](src/shell/view_state.cljs), while the persistent document graph stays in [`src/kernel/db.cljc`](src/kernel/db.cljc).
- **Uncontrolled editing replaces keystroke-by-keystroke DB writes.** Although it’s technically not purely functional programming: the browser owns contenteditable during edit mode, the buffer mirrors high-velocity text … commits happen at the controlled boundary. Main implementation: [`src/components/block.cljs`](src/components/block.cljs) and [`src/shell/view_state.cljs`](src/shell/view_state.cljs).



## Appendix / Footer

[1] This is out of scope, but in short: Creative interfaces are better designed than evolved/interpolated. There's only a handful of creative modalities and a few dozen decades-old, proven primitives. We've seen close to no changes to these. Yes, there's might be subdomain niches in the creative tooling space and sure, it's technically possible to evolve an image editor into a game engine purely via AI driven iteration (user-intent/events as signal and UI patches as variants/tests) but it's the wrong paradigm and shouldn't be the outermost control loop. At best this kind of live meta-iteration is as embed inside larger, stable paradigms.
