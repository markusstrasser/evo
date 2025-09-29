# Proposal 83 · Vlojure-Style Visual EDN for Kernel Traces

## Problem
When debugging intents we dump raw EDN. Large trees are unreadable and make it hard for humans (and models) to follow structural diffs.

## Inspiration
Vlojure’s `vedn.cljs` converts EDN into a tokenised “visual EDN” format, tracking encapsulators and bracket matching so UI renderers can present nested structures interactively.cite/Users/alien/Projects/inspo-clones/vlojure/src/cljs/vlojure/vedn.cljs:1-120

## Proposed Change
1. Port the `tokenize-clj` / `clj->vedn` pipeline into `kernel.introspect`, producing annotated tokens for any EDN trace (txs, db snapshots).
2. Provide a CLI/REPL helper `(trace->vedn trace)` that streams tokens to the dev console (colour-coded or collapsed by depth).
3. Feed vedn tokens to the docs generator so we can ship screenshot-ready traces alongside proposals.

## Expected Benefits
- Faster human audits: vedn stripes show block nesting clearly.
- Easier for LLM tooling to narrate changes (tokens already chunked by semantic unit).

## Trade-offs
- Adds another representation to maintain—must ensure vedn stays in sync with actual EDN when structures evolve.
- Tokenisation is JS-heavy; port carefully to CLJC to avoid platform drift.

## Roll-out Steps
1. Extract `tokenize-clj` and supporting tables into a CLJC namespace; add property tests round-tripping EDN ↔ vedn.
2. Wrap existing trace printers so they output vedn when `:output :vedn` is requested.
3. Update documentation templates to include vedn renderings for complex examples.
