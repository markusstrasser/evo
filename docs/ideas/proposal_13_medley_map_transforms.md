# Proposal 13 · Map Transform Helpers (Medley)

## Problem
Kernel derivations and indexes lean on hand-written `reduce-kv`/`assoc` loops. Examples:
- `src/kernel/core.cljc:186-214` builds sibling indexes via nested `reduce` and `map-indexed`.
- `src/kernel/tree_index.cljc` repeats logic to produce `:prev-id-of`/`:next-id-of`.
- `src/kernel/invariants.cljc` accumulates failures using manual `assoc` pipelines.
These snippets obscure intent (“compute sibling order”) behind boilerplate and risk off-by-one errors.

## Why Medley?
[`medley.core`](https://github.com/weavejester/medley) provides small, pure helpers tailored for associative data. Key functions of interest:

| Medley fn | What it does | Candidate call sites |
|-----------|--------------|-----------------------|
| `map-vals` | Map over values of a map (1+ maps) | Derivation of `:first-child-id-of`, `:last-child-id-of` |
| `map-kv-vals` | Transform each key/value pair, keep key | Building id→index maps without transient ceremony |
| `map-kv` | Rewrite both keys and values | Tree index renames |
| `assoc-some` | Assoc only when value not nil | Response builders (`src/kernel/responses.cljc`) |
| `update-existing`/`update-existing-in` | Update only when key present | Efficient derived updates |
| `filter-kv` / `remove-kv` | Filter maps by predicate | Invariant filtering, pruning empty refs |
| `index-by` | Build lookup map keyed by function | `kernel.schemas` entity registries |
| `reduce-map` | Underpins others; ensures records handled correctly | Helps when maps may be records |

All functions are CLJC, so they work on JVM & CLJS without conditional code.

## Before vs After
**Sibling index (today)**
```clojure
(reduce-kv
  (fn [m parent child-ids]
    (reduce (fn [m2 [idx child]]
              (assoc m2 child idx))
            m
            (map-indexed vector child-ids)))
  {}
  (:child-ids-by-parent derived))
```

**Sibling index (with Medley)**
```clojure
(->> (:child-ids-by-parent derived)
     (medley/map-kv-vals
       (fn [_ child-ids]
         (into {} (map-indexed (fn [idx child] [child idx]) child-ids)))))
```
Intent communicates directly: “map child-ids to an index map per parent”. No transients or manual loops needed.

**First/last child helpers**
```clojure
(-> derived :child-ids-of (medley/map-vals first))
(-> derived :child-ids-of (medley/map-vals peek))
```
Replacing manual `assoc` loops with one line each.

**Optional keys**
```clojure
;; responses.cljc
(-> {:db-after db-after}
    (medley/assoc-some :trace trace? (:trace ctx))
    (medley/assoc-some :storyboard storyboard? (:storyboard ctx)))
```
No need for `(cond-> m trace? (assoc :trace trace))` chains.

## Dependency Strategy
Two options:
1. **Add Medley as dependency** (preferred):
   - Already CLJC; include `medley/medley {:mvn/version "1.6.0"}` in `deps.edn` + `package.json` bundler. Tiny footprint (~10KB).
   - Avoids copy/paste; we benefit from upstream fixes.
2. **Vendor selective helpers**:
   - If dependency budgets are tight, we can copy `map-vals`, `map-kv-vals`, `assoc-some` with attribution into `kernel.util.medley`.
   - Downside: maintenance overhead and risk of divergence.

Given we need multiple helpers (and future ones like `partition-between` for trace grouping), pulling the library wins. It has no external deps.

## Integration Plan
1. **Introduce utility namespace**: `kernel.util.map` re-exporting the Medley functions we endorse (`map-vals`, `map-kv-vals`, `assoc-some`, `update-existing`, `filter-kv`, `index-by`). This keeps call sites consistent and allows us to swap implementations later.
2. **Derivation passes (Proposal 1)**: rewrite pass builders to use those helpers, reducing boilerplate and aligning data flow.
3. **Tree index / invariant deck**: use `map-vals`, `filter-kv`, `update-existing-in` to simplify invariants and storyboards.
4. **Docs & examples**: add code snippets to `docs/kernel_simplification_proposals.md` showing the readability win.
5. **Benchmarks**: run `derive-full` before/after. Medley internally uses transients where appropriate, so we expect neutral or improved performance.

## Risks & Mitigations
- **Dependency creep**: keep medley surface small via wrapper namespace. Document allowed helpers to avoid API sprawl.
- **CLJS bundling**: ensure bundler pulls CLJC version (medley ships as CLJC). Run `shadow-cljs watch` to confirm.
- **Readability**: Medley names mirror what we already use; document helper semantics in `docs/dev.md` for contributors unfamiliar with library.

## Summary
Adopting Medley yields dramatic LoC reduction and clarity across derivations, responses, and invariants. Prefer taking the library as a dependency; use a façade namespace to manage exposure and future swaps. This dovetails with the derivation registry and invariant deck, giving us declarative, data-first transformations with minimal ceremony.
