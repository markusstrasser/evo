# Dependency Review

Assessment date: 2026-03-05

This document reviews the direct third-party packages that materially affect Evo's
architecture or maintenance burden. The goal is not "newer is better." The goal is
to decide which libraries are actually valuable in this codebase, which ones should
stay narrow, and which ones should be removed.

## Method

- Local ground truth came from `deps.edn`, `package.json`, and direct repo usage
  scans with `rg`.
- Upstream status came from official docs, project homepages, and package indexes
  such as Clojars and GitHub.
- Architectural value is based on current Evo usage, not generic ecosystem
  popularity.

## Summary

| Package | Repo version | Latest observed upstream | Current role in Evo | Assessment |
| --- | --- | --- | --- | --- |
| `no.cjohansen/replicant` | `2025.12.1` | `2025.12.1` | Primary render/runtime surface | Keep as core |
| `no.cjohansen/nexus` | Removed (was `2025.11.1`) | `2025.11.1` | Historical compatibility layer | Removed |
| `metosin/malli` | `0.20.0` | `0.20.0` | Intent/schema validation | Keep as core |
| `thheller/shadow-cljs` | `3.3.4` | `3.3.6` | Build, test, REPL tooling | Keep, low-risk bump available |
| `no.cjohansen/dataspex` | `2025.10.2` | `2026.01.1` | Dev inspector only | Keep dev-only, optional bump |
| `medley/medley` | `1.4.0` | `1.4.0` | Small pure helper layer | Keep, do not expand casually |
| `nubank/matcher-combinators` | `3.9.2` | `3.10.0` | Test assertions only | Keep test-only, optional bump |
| `juji/editscript` | `0.6.6` | `0.7.0` | No direct repo usage found | Remove candidate |
| `markdown-to-hiccup/markdown-to-hiccup` | `0.6.2` | `0.6.2` observed on Clojars | No direct repo usage found | Remove candidate |
| `three` | `0.182.0` | Not assessed for bump here | No direct repo usage found | Remove candidate |
| `turndown` | `7.2.2` | Upstream repo active; bump not required for this review | HTML -> Markdown conversion | Keep, isolated utility |

## Detailed Assessment

### Replicant

Verdict: Keep as core.

Why:
- Replicant's official docs still align tightly with Evo's current architecture:
  pure render functions, whole-tree rendering, and simple event handler contracts.
- The docs explicitly support both function handlers and data handlers. Evo's
  current shape deliberately uses function handlers only.
- Repo usage is central: `src/components/block.cljs`, `src/shell/editor.cljs`, and
  `src/shell/dispatch_bridge.cljs` all sit directly on top of Replicant.

Implication:
- Replicant should remain the primary UI foundation.
- There is no architectural reason to add another UI abstraction layer above it.

### Nexus

Verdict: Removed.

Why:
- Nexus describes itself as data-driven action dispatch for UI systems. That is a
  reasonable library design, but it is a better fit when the whole app commits to an
  action/effect pipeline.
- Evo standardized on direct intent maps through `shell.executor/apply-intent!`
  and removed the remaining compatibility layer instead of preserving two
  dispatch models.
- That makes the package low-value in this repo even if the library itself is
  fine upstream.

Implication:
- Nexus may still be good in its niche.
- Nexus is not valuable enough in Evo to justify carrying a second dispatch
  model or the dependency itself.

### Malli

Verdict: Keep as core.

Why:
- Malli is current upstream and is a strong fit for Evo's runtime validation needs.
- Repo usage is structural, not incidental: intent validation and schema definition
  live in `src/kernel/intent.cljc`, `src/kernel/schema.cljc`, and
  `src/spec/registry.cljc`.
- This dependency is buying real correctness, not style.

Implication:
- Malli should remain the canonical schema/validation layer.
- No replacement work is justified.

### shadow-cljs

Verdict: Keep. Bump opportunistically.

Why:
- shadow-cljs remains the correct build tool for this repo's CLJS workflow.
- The project is two patch releases behind upstream (`3.3.4` -> `3.3.6`), but there
  is no repo-local evidence that the current version is blocking anything.

Implication:
- Keep shadow-cljs as the build/test backbone.
- Take the patch bump when touching build tooling anyway, not as a standalone
  architecture project.

### Dataspex

Verdict: Keep dev-only. Bump opportunistically.

Why:
- Repo usage is explicitly dev-facing: `dataspex/inspect` is called from
  `src/shell/editor.cljs`, and the docs treat it as an inspection tool.
- Upstream has moved from `2025.10.2` to `2026.01.1`, but the library is outside the
  production runtime path.

Implication:
- Do not expand Dataspex into core runtime logic.
- Bump only if the dev inspector is being touched already.

### Medley

Verdict: Keep, but keep the footprint small.

Why:
- Medley is stable and current, and the repo uses it in a few sensible places in the
  kernel and plugins.
- It is not an architectural dependency. It is a convenience dependency.

Implication:
- There is no pressure to remove it.
- Also do not grow new dependencies on it for trivial helpers that would be clearer
  inline.

### matcher-combinators

Verdict: Keep test-only. Bump opportunistically.

Why:
- Repo usage is test-only, which is the right boundary for it.
- Upstream has a newer release (`3.10.0`), but nothing in the repo suggests the
  current version is causing problems.

Implication:
- Safe to keep.
- A patch/minor bump is optional, not strategic.

### editscript

Verdict: Remove candidate.

Why:
- No direct repo usage was found.
- Upstream has moved from `0.6.6` to `0.7.0`.
- Dataspex itself depends on editscript upstream, so Evo may not need a direct dep
  at all unless a future feature uses it explicitly.

Implication:
- Remove the direct dependency after one final compile/test pass confirms nothing
  hidden depends on it.

### markdown-to-hiccup

Verdict: Remove candidate.

Why:
- No direct repo usage was found.
- The package exists on Clojars, but it is not currently buying anything visible in
  Evo.
- Carrying an unused markdown parser is pure maintenance surface.

Implication:
- Remove unless a concrete call site or near-term feature needs it.

### three

Verdict: Remove candidate.

Why:
- No direct repo imports were found.
- Even if upstream is healthy, the value of an unused dependency is zero.

Implication:
- Remove from `package.json` unless a pending branch or hidden runtime path still
  needs it.

### turndown

Verdict: Keep, isolated.

Why:
- Repo usage is real and narrow: `src/utils/html_to_markdown.cljs` wraps Turndown
  for HTML paste conversion.
- Turndown's API is explicit and rule-based, which is appropriate for this isolated
  conversion job.

Implication:
- Keep it as a utility dependency.
- Do not let it leak into broader editor architecture.

## Recommended Actions

1. Keep `replicant`, `malli`, and `shadow-cljs` as the external core stack.
2. Keep one dispatch model: function handlers -> intent maps -> `shell.executor`.
3. Optionally bump `shadow-cljs`, `dataspex`, and `matcher-combinators` when those
   areas are touched anyway.
4. Remove direct deps with no demonstrated value: `juji/editscript`,
   `markdown-to-hiccup/markdown-to-hiccup`, and `three`.
5. Keep `turndown` and `medley`, but do not expand their footprint without a clear
   payoff.

## Sources

- Repo inventory: `deps.edn`, `package.json`, and repo-wide usage scans.
- Replicant docs: <https://replicant.fun/>
- Replicant event handlers: <https://replicant.fun/event-handlers/>
- Replicant package page: <https://clojars.org/no.cjohansen/replicant>
- Nexus package page: <https://clojars.org/no.cjohansen/nexus>
- Dataspex package page: <https://clojars.org/no.cjohansen/dataspex>
- Malli package page: <https://clojars.org/metosin/malli>
- shadow-cljs user guide: <https://shadow-cljs.github.io/docs/UsersGuide.html>
- shadow-cljs package page: <https://clojars.org/thheller/shadow-cljs>
- Medley package page: <https://clojars.org/medley>
- matcher-combinators package page: <https://clojars.org/nubank/matcher-combinators>
- editscript package page: <https://clojars.org/juji/editscript>
- markdown-to-hiccup package page: <https://clojars.org/markdown-to-hiccup>
- Turndown source/docs: <https://github.com/mixmark-io/turndown>
- three.js source/docs: <https://github.com/mrdoob/three.js>
