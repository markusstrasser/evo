# Proposal 59 · SCI Sandbox for Intent Plugins

## Problem
- Future adapters and partners will want to ship custom intent planners or derivation passes. Running arbitrary user code in-process risks mutating core state or accessing forbidden vars.
- Today we only document the desire for plugins; there is no concrete sandbox mechanism.

## Inspiration
- Small Clojure Interpreter (SCI) executes code in a configurable sandbox where namespaces, allowed symbols, and bindings are whitelisted (`sci/src/sci/core.cljc:230-320`). SCI contexts can be forked, merged, and evaluated safely with `:allow`/`:deny` lists.

## Proposed change
1. Embed SCI as the execution host for user-provided planners/validators. Wrap `analysis`/`derive` extension points in `sci.core/eval-string*` with curated namespaces exposing only approved kernel APIs.
2. Provide a helper `(plugins/load! source {:allow [...] :namespaces {...}})` that compiles a plugin once, returning a pure function the kernel can call.
3. Forbid side-effectful core functions by default (`:deny ['clojure.core/alter-var-root ...]`) and expose only pure helpers plus capability-style effect emitters.

```clojure
(def sandbox
  (sci/init {:namespaces {'kernel.plugin {:op->tx kernel.plugin/op->tx}}
             :allow ['kernel.plugin/op->tx 'clojure.core/map 'clojure.core/filter]}))

(defn eval-plugin [src]
  (let [{:keys [val]} (sci/eval-string+ sandbox src {:ns 'kernel.plugin})]
    val))
```

## Expected benefits
- Enables third-party or AI-authored planners without sacrificing kernel invariants—sandboxed code can only call what we expose.
- Matches the plugin direction in docs while preserving synchronous/pure semantics: SCI programs return data (tx vectors) that the kernel can validate.
- SCI contexts can be forked per session, so failures don’t poison global state.

## Trade-offs
- Slight runtime overhead for interpreting plugin code; cache compiled forms and restrict to configuration time to minimize hot-path cost.
- Need to curate ergonomic yet safe namespaces; provide documented capability lists and staging tests.
- SCI doesn’t allow arbitrary JVM interop by default, but we must still write explicit allow lists to block reflection or I/O.
