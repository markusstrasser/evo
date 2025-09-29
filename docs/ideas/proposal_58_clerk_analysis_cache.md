# Proposal 58 · Clerk-Style Analysis Cache for Planner Intelligence

## Problem
- LLM planners repeatedly infer structural facts (e.g., which ops touch a namespace, which derived keys a macro expands into). Today we either recompute or hard-code heuristics—no persistent analysis cache exists.
- `docs/ideas/proposal_43_missionary_simulator.md` and others assume we can introspect plans, but there’s no canonical store for relationships between forms, ops, and derived keys.

## Inspiration
- Clerk builds an analysis graph that ties code blocks to vars, dependencies, and normalized metadata, caching results by file hash (`clerk/src/nextjournal/clerk/analyzer.clj:300-385`). The analysis cache tracks var-to-block mappings, detects redefs, and exposes dependency graphs for incremental updates.

## Proposed change
1. Add `kernel.analysis` module that mirrors Clerk’s `analyze-doc`: walk planner macros/intents, produce annotated forms, and store them in `:analysis-cache` keyed by hash.
2. Track `op -> dependencies` and `var -> block` relationships so planners can answer “which derived pass does this intent touch?” without re-running expensive inference.
3. Surface APIs like `(analysis/dirty-hash old new)` to invalidate only changed planner modules, enabling faster agent feedback loops.

```clojure
(defn analyze-intent [form]
  (-> form
      planner/expand
      (assoc :deps (intent/dependencies form))
      (analysis/store! cache)))
```

## Expected benefits
- Massive latency reduction for iterative planner sessions—unchanged macros and schema expansions skip re-analysis.
- Provides a shared source of truth for documentation and tooling (e.g., render op dependency graphs straight from the cache).
- Pairs with Proposal 55’s change ranges: when code edits arrive, the cache tells us which planner outputs to recompute.

## Trade-offs
- Requires defining a canonical representation of intents/forms; lean on Malli schemas to ensure analysis output stays stable.
- Cache invalidation must consider upstream dependencies (like Clerk’s `track-var->block+redefs`); implement similar tracking to detect redefs.
- Adds disk/cache footprint; store metadata under `tmp/kernel-analysis/` and gate with env flags for CI.
