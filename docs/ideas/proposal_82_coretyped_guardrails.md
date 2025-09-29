# Proposal 82 · Core.Typed Guardrails for Kernel Contracts

## Problem
Kernel functions rely on Malli specs at runtime but nothing verifies them statically. Accidental NILs sneak into hot paths, only failing after property tests or in production.

## Inspiration
Core.Typed annotates functions using `(ann f [Arg -> Ret])` and can infer contracts automatically for recurring patterns, catching mismatches at compile time.cite/Users/alien/Projects/inspo-clones/core.typed/typed/annotator.jvm/src/clojure/core/typed/runtime_infer.cljc:813-860

## Proposed Change
1. Define annotations for core primitives:
   ```clojure
   (ct/ann create-node* [DB {:id NodeId :type Keyword :props map?} -> DB])
   ```
2. Run `core.typed/check-ns` in CI (just for kernel namespaces) to catch contract violations early.
3. Add `ct/ann` shims for Malli-validated helper functions so specs and types stay aligned.

## Expected Benefits
- Static assurance that primitive ops honour their argument/return contracts.
- Gives us richer editor feedback (typed holes) while refactoring kernel internals.

## Trade-offs
- Core.Typed adds compilation overhead; we should scope it to the kernel namespace set.
- Type annotations might diverge from Malli schemas if we’re sloppy; need tests comparing them.

## Roll-out Steps
1. Add a `:typed` alias to `deps.edn` and gate the type checker behind a `npm run typed` script.
2. Annotate the six primitives and run the checker in CI (non-blocking at first).
3. Expand coverage to `kernel.lens` and `kernel.tx.normalize` once the base functions pass.
