# Proposal 47 · Portal Viewer Bridge for Kernel Introspection

## Problem
- Kernel debugging relies on bespoke helpers (`src/kernel/introspect.cljc:14-68`) and console prints in `src/dev.clj`. Adding new perspectives (effect timelines, invariant findings) means writing ad hoc printers.
- Agents/LLMs must parse raw maps; there is no structured UI to explore DB snapshots or traces without writing custom tooling.
- Documentation drift occurs because we duplicate explanations in Markdown rather than exposing live, inspectable values.

## Inspiration
- **Portal’s viewer registry** associates data with declarative viewers via metadata (`/Users/alien/Projects/inspo-clones/portal/src/portal/viewer.cljc:1-120`). By tagging values with `::viewer/default`, Portal renders tree diff, timeline, or table views without additional glue.

## Proposed change
1. Add `kernel.introspect.portal` namespace that provides convenience fns (`portal-snapshot`, `portal-trace`, `portal-findings`). Each function wraps core data structures with appropriate Portal viewers.
2. Tag transaction results in `kernel.core/evaluate` (when `:trace?` or `:findings` present) with metadata pointing to viewers. Consumers can call `(portal.api/submit (ix/portal-trace result))` during debugging.
3. Ship a clipboard-friendly dev helper in `src/dev.clj` (`(portal!)`) that runs `portal.api/open` and wires tap> to kernel outputs.

```clojure
;; before: REPL output is raw EDN
(ix/diff db0 db1)
;; => {:added #{"n42"} :removed #{} :moved #{} :props-changed #{"n7"}}

;; after (sketch)
(ns kernel.introspect.portal
  (:require [portal.api :as portal]
            [portal.viewer :as viewer]
            [kernel.introspect :as ix]))

(defn portal-diff [db0 db1]
  (viewer/tree (ix/diff db0 db1)))

(defn portal-trace [tx-result]
  (viewer/log (map #(select-keys % [:i :op :effects :node-count])
                   (ix/trace tx-result))))
```
- Portal renders diffs and traces visually; no bespoke UI code needed.

## Expected benefits
- Eliminates custom console helpers; we reuse Portal’s maintained viewers and gain features like filtering, history, and diffing for free.
- Encourages literate, inspectable documentation: docs can embed `(portal-diff sample-before sample-after)` rather than screenshots.
- Agents can export structured viewer metadata to assist human operators during troubleshooting.

## Trade-offs
- Adds optional dev dependency on Portal; keep it behind dev profile to avoid production bloat.
- Needs guardrails for headless contexts. Provide pure EDN fallback when Portal not on classpath.

## Roll-out steps
1. Add `portal` to `dev` alias; wire `(tap> (portal-diff ...))` convenience fns in `dev.clj`.
2. Document workflow in `docs/DEV.md` (how to open Portal, inspect traces, close viewer).
3. Migrate existing debugging snippets to prefer Portal wrappers, reducing duplicated formatting code.
