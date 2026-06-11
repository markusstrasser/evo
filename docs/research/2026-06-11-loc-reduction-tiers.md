# LOC-Reduction Tiers: Is There a 2x/5x/10x Unified Architecture? — 2026-06-11

Status: **Route A partially executed 2026-06-11** (A1/A2/A8/A3 done, A4
falsified — see Execution record at bottom). Originally proposed as
recommendation-only. Successor to
`2026-05-15-architecture-simplification-ledger.md`; builds on its dispositions
rather than re-litigating them. Survived one cross-model adversarial round
(Gemini 3.5 Flash + GPT-5.5, 2026-06-11); fold trail at bottom.

## Question

Can a unified architecture cut the codebase by 2x / 5x / 10x?

## Claim scope (what would falsify this)

The central claim below is bounded by evo's standing constraints: same
user-facing outliner, kernel purity + three-op invariant, no new runtime
dependencies, incident-driven hardening retained (transaction laws,
product-state validation), tests preserved. **Falsifier:** a concrete design
that deletes >5K src lines under these constraints without adding a runtime
(engine/interpreter/framework). The strongest counter-design found in
adversarial review lands ~1.5–1.7x risk-adjusted (see "substrate route").

## Ground truth (measured 2026-06-11, commit 9c301250)

```
src/ total: 21,962 lines        test/: 12,691      e2e/: 8,596
  components/  5,182   (block.cljs 1,764 · spec_viewer.cljs 1,540 · 11 others 1,878)
  kernel/      4,150
  plugins/     4,068   (83 intents, 12 files, ~49 lines/intent, ~41% result/destructure ceremony)
  shell/       3,664   (view_state 925 · editor 902 · storage 725)
  utils/       2,070
  spec/        1,050   parser/ 1,014   keymap/ 299   scripts/ 263   dev/ 177
```

Verification probes (re-run before acting on any number):

```bash
for d in src/*/; do find $d -name '*.clj*' | xargs wc -l | tail -1; done
grep -rn "scripts.script\|script/run" src/ | grep -v "^src/scripts/"   # → 1 docstring, 0 callers
grep -rln "spec_viewer\|spec-viewer" src/ | grep -v spec_viewer        # → empty
grep -c "^(defn" src/shell/view_state.cljs                             # → 94
```

## The answer, up front

**Under the claim scope, no unified architecture reaches 2x.** The
2026-05-15 ledger reached this qualitatively ("the useful search space is
not new architecture"); this pass adds the arithmetic. What line-probes
*verified* is a lower bound of ~3.2K deletable/collapsible lines (~15%) —
probes can't see all semantic duplication, so the routes below also price
the substrate-level candidates adversarial review surfaced. Three honest
routes, two of which converge on the same band:

| Route | src/ after | Mechanism | What it costs |
|------|-----------|-----------|---------------------|
| **A — subtraction, ~1.18x** | ~18,700 | Delete dead/dev/wrapper inventory (below). No product or substrate change. | Dev chrome (owner calls) |
| **B — feature diet, ~1.45x** | ~15,100 | A + retire features from the menu below | Product features, one owner call each |
| **B′ — substrate route, ~1.5–1.7x** | ~13–15K | A + textarea-overlay editor + handler compression + generic panels | Editor-substrate rewrite risk; keeps features |
| **C — ~3–3.5x** | ~6–7K | Kernel + parser + headless shell; requires kernel diet beyond that | Stops being an app; the retired "library" framing (GOALS Do-NOT) |
| **D — 10x** | ~2,200 | Kernel-core rewrite + demo | Everything except the spec artifact |

B and B′ compose only partially (B′ keeps the features B deletes); realistic
combined ceiling under the claim scope is **~1.7–1.8x, not 2x**. C/D are
identity changes requiring the rewrites the other tiers forbid — listed for
completeness, not recommended.

## Route A — finish the subtraction (~3,200–3,400 lines)

Each item verified this session; greps inline above or noted.

1. **Delete `src/scripts/` (263) + `test/scripts/` + the CLAUDE.md "Script
   Pattern" section** — or adopt it deliberately. Zero production callers;
   only reference is a docstring at `transaction.cljc:395`. Same
   liveness-failure class as `:smart-split` (e93f5746). CLAUDE.md documents
   a `scripts.editing` namespace that does not exist. Fork is
   delete-vs-adopt; evidence favors delete. Also remove `test/scripts` from
   invariants.md §6 if deleted.
2. **Retire `spec_viewer.cljs` (1,540) + `shell/spec_viewer.cljs` (14) +
   `:spec-viewer` shadow build.** Standalone `/specs.html` dev surface,
   zero references from the app. The spec *registry* (`src/spec/`,
   `specs.edn`, `bb fr-matrix`) is the product and stays; the browser
   chrome is owner-taste territory (precedent: paired-char retire,
   2026-06-10). **Owner call.**
3. **Collapse `view_state.cljs` (925 → ~550).** 94 defns; ~65 are one-line
   `get-in`/`swap!` wrappers. Generic `(vs/get path)` / `(vs/set! path v)`
   / `(vs/update! path f)` plus the ~15 functions with real logic (history,
   autocomplete state machine). Trades greppable named call sites for ~330
   lines — taste check before doing.
4. **Handler ceremony (~350–400).** 41% of plugin handler LOC is
   destructuring + result-map assembly (measured on 3 representative
   handlers). One `defhandler` macro (~40 lines) deletes ~400. **Tension:**
   macro = new spec surface; the 2026-05-15 ledger implemented the result
   *contract* deliberately without a macro. Owner call: LOC vs macro-free
   plugins.
5. **Merge edit/view render paths in block.cljs (~200–250 net).** Subsumed
   by the substrate route below if that spike succeeds; do not do both
   independently.
6. **Reuse tx ops for storage dirty-tracking (~80).** `storage.cljs`
   re-diffs old/new DB; the transaction already knows its ops. Mechanical.
7. **Misc verified (~90 deletable + 112 movable):** duplicate range getters
   in `dom.cljs`/`text_selection.cljs` (~30); `indent`/`outdent` and
   arrow-mirror hand-duplication (~60); `editor.cljs` test/debug
   scaffolding (112 — a *move* behind a dev flag, not a deletion).
8. **`devtools.cljs` (285): owner call.** Imported by `shell/editor.cljs`
   (production build), unlike spec_viewer. Retire or keep deliberately.

Arithmetic: 263 + 1,554 + 330 + 400 + 225 + 80 + 90 + 285 ≈ **3,227**
(±10%), → src ~18,700. Items 1+6+7-deletables alone (mechanical, zero
product change, no taste gate): **~430 lines**. Tests travel with
deletions, so repo-wide A is ~4–4.5K.

## Route B — the feature-diet menu (~3,590 more, sums per item)

Architecture cannot make these calls; each is "does evo-the-outliner need
this?" Sizes include component + plugin + view-state + wiring:

autocomplete ~770 · image pipeline (incl. lightbox) ~690 · clipboard
sophistication beyond plain-text paste ~400 · journals ~400 ·
quick-switcher ~250 · favorites/recents ~240 · backlinks ~180 ·
all-pages ~160 · drag-and-drop ~150 · notifications ~50 · pages.cljc
diet (rename/auto-trash/reconcile intents) ~300. **Σ = 3,590.**

A + full menu ≈ 6.8K saved → ~15.1K ≈ **1.45x** (GPT-5.5 caught the
earlier 1.6–1.8x claim as unsupported by this list; corrected). Consume
opportunistically — one feature per session, the way paired-char went —
not as a campaign.

## Route B′ — the substrate route (the one real counter-architecture)

From adversarial review (GPT-5.5), verified against the repo. Keeps every
feature; attacks representation impedance instead:

1. **Textarea-overlay editor instead of contenteditable (~500–700).** One
   active textarea swapped over the focused block; inactive blocks are
   rendered hiccup. This is *Logseq's own approach* — strong feasibility
   prior. Cursor becomes `.selectionStart` (an integer, not a DOM Range):
   deletes most of `text_selection.cljs` (286), chunks of
   `cursor_boundaries.cljs` and `dom.cljs`, edit-mode text→HTML conversion,
   and simplifies paste. **Mock-text survives** (row detection for
   arrow-boundary nav is needed for textareas too — Logseq does exactly
   this). Distinct from the archived "controlled text engine" (76297bc1),
   which re-rendered contenteditable from state. Risks: IME composition,
   selection-mode boundary, mobile. **Spike behind e2e smoke before
   committing.** Note: the 05-15 ledger parked a render-projection
   *abstraction* over contenteditable; this is a *substitution* that
   removes the thing the ledger said was the complexity source.
2. **Command-table compression (~400–800).** Handler ceremony (item A4)
   plus folding key/context declarations into command specs — merges
   keymap's table + some `global_keyboard` glue. Real but bounded: domain
   logic (clipboard parsing, selection geometry, page reconcile) does not
   compress by notation change.
3. **Generic query-backed panels (~350–500 net).** sidebar / all-pages /
   journals / backlinks / quick-switcher (~1,063 total) all reduce to
   "query → list → intent on click." One collection-panel component could
   halve them. This is *abstraction, not deletion* — anti-taste; it is the
   keep-the-features alternative to deleting them via Route B.

B′ total ≈ 1.3–2.0K beyond A → ~13.5–15K ≈ **1.5–1.7x risk-adjusted**.
What the full counter-architecture proposed in review (rewrite kernel to a
1.5K "patch reducer") additionally drops is the incident-driven hardening —
transaction laws, product-state validation, derived-key ownership — which
exists because those bug classes occurred. Deleting hardening re-buys the
bugs; we price that as not-a-saving.

## Mechanisms considered and NOT proposed (with prior-art pointers)

- **Declarative behavior/interaction tables** — rejected 2026-06-10
  (behavior-tables plan v3: premises falsified; live Enter logic is
  parser-based). Parked in 2026-05-15 ledger. Do not re-propose.
- **Datalog / DataScript reactive engine** (proposed again by Gemini in
  review, claiming 6–8K) — evo migrated *off* DataScript to plain maps
  (c2b9d880, do-not-resurrect list). The tree logic doesn't vanish under
  Datalog, it changes notation; the actual bulk (editor adapter, storage,
  components) is untouched; and it adds a runtime.
- **Product event log / session back into DB / one atom** — rejected in
  ledger ("more ambitious, not smaller"); session-out-of-DB was a
  deliberate migration. Note: tier independence is structurally guaranteed
  by the three-op invariant — ops are feature-agnostic, so deleting a
  feature deletes its intents wholesale; there are no per-feature event
  schemas to keep compatible (this also disposes of the "event-log
  coupling blocks deletion" objection raised in review).
- **Render projection boundary abstraction** — parked in ledger; B′.1 is
  the substitution-shaped sibling, not this.
- **Feature-module restructure** — parked 2026-05-15 (cross-surface
  friction measured at 0.35% of commits).
- **Intent-as-EDN-template engine** — behavior-tables ghost; saves
  ~300–500 while adding an interpreter and a second semantics.
- **Replicant data-driven event handlers** — deliberately not shipped;
  closure deletion ~200–300 not worth the dispatch indirection today.
- **Per-tag render namespaces → one namespace** — ~200 lines of ns
  ceremony, chosen deliberately in the Tier 2/3 design. Cosmetic; skip.

## Doc-currency findings (fixed or pending)

- ~~CLAUDE.md + RENDERING_AND_DISPATCH.md referenced deleted
  `__lastAppliedCursorPos`~~ — fixed, 5e21ba6d.
- CLAUDE.md "Script Pattern" section documents dead code (`scripts.editing`
  doesn't exist; zero callers) — pending the item A1 fork.

## Recommendation

1. **Now (mechanical, no gates):** A1 (delete scripts/, pending the
   delete-vs-adopt fork), A6, A7-deletables — ~430 lines + tests.
2. **Owner calls (retire/taste):** A2 spec_viewer (1,554), A8 devtools
   (285), A3 view-state API shape (330), A4 macro (400).
3. **Spike:** B′.1 textarea overlay on a branch behind `npm run
   test:e2e:smoke` — the single highest-yield architectural change that
   survived adversarial review, with Logseq as feasibility prior. If it
   lands, A5 comes free.
4. **Standing menu:** Route B, one feature per session, owner-initiated.
5. **Decline:** C/D (identity changes), and everything in the
   not-proposed list.

Honest bottom line: **1.2x is free, 1.45x is feature surgery, ~1.7x is the
ceiling with the substrate spike landed — 2x under the claim scope does not
exist on any route we or two adversarial reviewers could construct.** The
unified architecture exists; the remaining work is inventory control plus
one substrate decision.

## Critique fold trail (2026-06-11)

- GPT-5.5: caught Tier B arithmetic (3,590 ≠ 3.5–4.5K → band corrected to
  1.45x), Tier C impossibility (kernel+parser alone is 5.2K → C restated
  as 3–3.5x with kernel diet), unfalsifiable central claim (claim scope
  added), "measured redundancy" overstatement (restated as verified lower
  bound), textarea-overlay counter-mechanism (adopted as B′.1 spike).
  HELD against: 1.5K kernel-rewrite sizing (drops incident-driven
  hardening), "nine-stage impedance pipeline" framing (the stages are the
  survivors of a longer pipeline — Nexus deleted, render waterfall 5→2,
  DataScript→maps).
- Gemini 3.5 Flash: all five findings HELD against — event-schema coupling
  (no persisted per-feature schemas; three-op invariant), Datalog engine
  (do-not-resurrect, c2b9d880), tree-walking strawman (DB is already flat
  maps + derived indexes), "keymap hand-coded" (bindings_data.cljc is a
  116-line data table). Two memo improvements adopted from the exchange:
  three-op tier-independence note; Datalog added to not-proposed.

## Execution record (2026-06-11, owner-approved items)

src/ measured: 21,962 → **19,732** (−2,230 src; −2,535 with tests/config).
Estimate-vs-actual per item — the corrections are the calibration data:

| Item | Est. | Actual | Commit | Correction |
|---|---|---|---|---|
| A1 scripts/ | 263 | −540 (incl. tests) | e67d4255 | `tx/dry-run` kept (REPL API) |
| A2 spec_viewer | 1,554 | −1,580 | 2d436500 | as estimated |
| A8 devtools | 285–470 | −292 | d7c388d2 | **panel only** — `dev.tooling` is e2e-harness infra (`window.DEBUG.lastIntent`/`clipboardLog`/`getOperations`); first cut broke it, reverted |
| A3 view_state | ~330 | −123 | ba7bc2ae | 30 deleted / 56 kept — "65 trivial wrappers" overcounted: defaulted/coercing accessors are logic. API: `lookup`/`put!`/`mutate!` |
| A4 defhandler | ~400 | **0 — not built** | — | Premise falsified on contact: shape-preserving conversion is line-neutral (`:toggle-fold` 10→10). The ~400 assumed auto-wrapped returns, which would resurrect the dual handler-return shape the 05-15 ledger removed. De-indentation yield (~200–400 in clipboard/selection/autocomplete) is available macro-free via top-level handler defns — cosmetic, owner-call, not queued. |

Pattern across all five: estimates from file-level probes ran ~2x hot;
classification-on-contact is the real number. Route B/B′ sizes above
should be read with the same discount.

Open forks after execution:
1. **Textarea spike vs live-rendered editing — RESOLVED 2026-06-11:
   contenteditable stays; textarea spike (B′.1) declined.** Owner stated
   an expressivity ambition (live footnotes/marginalia via `[sn …]` →
   `^1`, math). Decomposition recorded with the decision:
   - on-exit rendering (math etc.): already shipped (AST + registry);
   - input transforms (`[sn …]` → rewrite text + create note node +
     marginalia render surface): substrate-independent, pure
     parse-trigger→intent→ops idiom; sidenotes/footnotes were already
     "future plans" in the wysiwyg plan §0.3; NOT built — new feature,
     needs explicit owner green-light per GOALS;
   - true mid-edit widget rendering: the only tier that needs
     contenteditable; remains a someday/major project (own /decide,
     dependency question included).
   Syntax direction settled in discussion (2026-06-11): the typed-element
   family (`sn`/`fn`, `voicenote`, `tldr`, …) uses **`{{type payload}}`
   macros** (Logseq idiom; dead syntax in evo; no link-label collision —
   bare `[type …]` rejected for content-vs-syntax ambiguity that grows
   with the type registry). One parser pattern → typed AST node →
   render-registry handler per type; unknown macro types degrade to a
   visible pill, never throw on user content. Open micro-fork: `^[…]`
   (Pandoc round-trip) vs `{{sn …}}` (uniformity) for prose footnotes —
   decide at build time.
   Publishing direction (2026-06-11 discussion): site export = a second
   STATIC render target over the same AST (~10 twin handlers emitting
   HTML strings — do not parameterize the live Replicant handlers), plus
   outline→prose mapping rules (the real design work; doc-mode is the
   precedent), evo:// link rewriting, assets copy, `bb publish`.
   Interactives = islands: `{{widget assets/x.html}}` renders a
   sandboxed iframe in-app and an iframe/mount-div on the site; one
   self-contained artifact serves both surfaces. Server-dependent
   dynamics (comments, live data) explicitly out of static scope.
   Sequencing: macro family first (it is the content model), projection
   second. Extends wysiwyg plan §0.3 "HTML export pipeline"; GOALS
   green-light still required.
   The ~600-line textarea saving is consciously foregone as the option
   premium. Do not re-propose the swap while this ambition stands.
2. ~~GOALS.md §6 stale `test/scripts/` reference~~ — resolved 2026-06-11
   with owner approval, together with the expressivity exception
   amendment (GOALS §Project Mode + invariants.md mirror).
3. Route B feature-diet menu — standing, owner-initiated, one feature
   per session.
