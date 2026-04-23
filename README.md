# evo

A block-based outliner in ClojureScript. Event-sourced kernel, three-registry extension surface, Replicant view layer. Logseq-style UI without the PKM ambitions.

The point is the shape of the code, not the product. Evo is meant to be legible enough that a reader (human or agent) can grok the whole kernel, and structured enough that new features are written as handler registrations instead of patches to core.

## What it does

- **Nested blocks** with indent/outdent, drag & drop, fold, and zoom-to-block
- **Pages and daily journals** (Logseq-compatible title format: `Apr 22nd, 2026`); journals open stacked on startup
- **Inline markdown** — `**bold**`, `_italic_`, `==highlight==`, `~~strike~~`, `$inline math$`, `$$block math$$`, with word-boundary guards so code like `cljs$core$key` stays literal
- **Wiki-style refs** `[[Page Name]]` and markdown links `[label](target)`, including custom `evo://` schemes: `evo://page/<name>`, `evo://block/<id>`, `evo://journal/<iso-date>`
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

## What's distinctive

All code routes through one of three registries plus a session atom. Nothing in the kernel knows about any of them.

| Layer | Registry | Example |
|---|---|---|
| Intents | `kernel.intent/register-intent!` | `:indent`, `:zoom-in`, `:navigate-to-page` |
| Derived indexes | `kernel.derived-registry/register!` | `:backlinks`, `:parent-of`, `:next-id-of` |
| Render handlers | `shell.render-registry/register-render!` | `:bold`, `:link`, `:page-ref`, `:math-inline` |

All database mutations reduce to three primitive ops — `create-node`, `place`, `update-node` — applied through a single transaction pipeline (normalize → validate → apply → derive). Ephemeral UI state (cursor, selection, zoom, fold set) lives on a separate session atom and never pollutes the persistent doc.

Adding a new inline format is one file under `src/shell/render/` plus a parser extension. Adding a new keyboard shortcut is one keymap entry. Adding a new kind of link target is one branch in `parse-evo-target` and one case in `shell.render.link`. See `docs/ARCHITECTURE.md` for the worked example.

## Project layout

```
src/kernel/         Pure kernel — db, ops, transaction, schema, query. Zero UI imports.
src/parser/         Text → AST. parse.cljc composes page-refs / images / links / inline-format.
src/plugins/        Intent handlers + derived-index plugins. Manifest bootstraps both.
src/shell/          Runtime wiring, session state, render registry + handlers, storage, executor.
src/components/     Replicant UI (Block, Sidebar, Backlinks, Image, Lightbox, …).
src/keymap/         Keybindings + dispatch.
src/scripts/        Multi-step operations that need scratch-DB simulation (smart backspace, paste).
test/               Unit, property, integration (ClojureScript) + Playwright E2E.
resources/          FR registry (specs.edn), known failure modes, seed data.
public/             index.html, styles.css, MathJax shim, build output.
docs/               ARCHITECTURE.md, STRUCTURAL_EDITING.md, RENDERING_AND_DISPATCH.md, and more.
```

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

## What this is not

- **Not a PKM product.** See `docs/GOALS.md` for the reasoning; short version: the structured-note-taking thesis hasn't held up, and the author isn't using it for daily notes.
- **Not a packaged library.** No Clojars artifact, no API stability. If a concrete consumer shows up wanting to reuse the kernel, extraction happens then.
- **Not chasing Logseq parity.** Close enough that Logseq users feel at home; not trying to match every feature.
- **Not feature-breadth maximized.** Kernel purity, deletion, and extension-surface cleanliness take priority over new content types.

## Further reading

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — three registries, AST contract, worked example
- [`docs/GOALS.md`](docs/GOALS.md) — mission, strategy, explicit non-goals
- [`docs/DX_INDEX.md`](docs/DX_INDEX.md) — curated doc map
- [`docs/STRUCTURAL_EDITING.md`](docs/STRUCTURAL_EDITING.md) — block editor spec
- [`docs/RENDERING_AND_DISPATCH.md`](docs/RENDERING_AND_DISPATCH.md) — Replicant lifecycle + intent dispatch
- [`AGENTS.md`](AGENTS.md) — canonical agent guidance (same content as `CLAUDE.md`)
