# Proposal 48 · Onyx-Style Catalog & Workflow for Operation Pipelines

## Problem
- `src/kernel/core.cljc:309-366` represents the transaction pipeline as a flat vector of stage fns. Branching (e.g., skipping expensive invariants for specific ops) requires conditional logic inside each stage.
- Adapters that need custom stage ordering or optional guards must rebuild the entire vector, risking drift from the canonical pipeline.
- There is no data model describing how stages depend on each other; tracing and visualization rely on ad hoc instrumentation.

## Inspiration
- **Onyx job definitions** separate `:catalog`, `:workflow`, and `:flow-conditions` (`/Users/alien/Projects/inspo-clones/onyx/src/onyx/api.clj:62-139`). Tasks declare capabilities, the workflow expresses DAG edges, and flow conditions drive conditional routing—all encoded as data inspected and transformed at runtime.

## Proposed change
1. Model kernel stages as catalog entries:
   ```clojure
   {:id :validate-schema
    :fn stage:validate-schema
    :provides #{:op}
    :requires #{}}
   ```
   Each entry states inputs/outputs, trace metadata, and optional predicates.
2. Express the pipeline as a workflow graph (vector of `[from to]` edges). For a simple transaction we get `[:validate-schema :apply-op]`, `[:apply-op :derive]`, etc. Conditional edges (e.g., skip invariants when `:assert?` false) are handled via `:flow-conditions` data similar to Onyx.
3. Introduce a compiler (`kernel.pipeline.plan/compile`) that topologically sorts the graph, validates dependencies, and produces an executable function. Debug tooling can render the graph for docs or Portal integration (Proposal 47).

```clojure
;; before
(def default-pipeline [stage:validate-schema stage:apply-op stage:derive ...])

;; after (sketch)
(def catalog
  {:validate-schema {:fn stage:validate-schema :provides #{:op}}
   :apply-op        {:fn stage:apply-op :requires #{:op} :provides #{:db}}
   :derive          {:fn stage:derive :requires #{:db}}
   :assert          {:fn stage:assert-invariants :requires #{:db} :when :assert?}
   :effects         {:fn stage:detect-effects :requires #{:db}}})

(def workflow
  [[:validate-schema :apply-op]
   [:apply-op :derive]
   [:derive :assert]
   [:derive :effects]
   [:assert :effects]])

(def compiled (pipeline.plan/compile catalog workflow))
```
- `:when :assert?` indicates a flow condition referencing runtime config; the compiler injects branching without embedding conditionals in stage bodies.

## Expected benefits
- Declarative graph makes stage ordering obvious, unlocks visualizations, and eases experimentation (swap/disable nodes without rewriting code).
- Fine-grained instrumentation: we can attach timers or tracing per catalog entry automatically.
- Adapters compose additional stages by merging catalogs/workflows (mirroring Onyx extensions) without forking kernel code.

## Trade-offs
- Slight upfront cost to compile the workflow; cache the result in vars and refresh on code reload.
- Authoring catalog metadata requires discipline (declaring `:requires/:provides`). Add compile-time checks and tests to catch missing keys.

## Implementation steps
1. Port existing stages into catalog entries with minimal metadata. Keep `default-pipeline` as `(plan/run compiled ctx)` for backward compatibility.
2. Provide helper to convert simple vectors into graphs (for quick tests) while encouraging structured definitions.
3. Update docs/sanity checks to assert that the compiled workflow matches existing behaviour before removing the flat vector.
