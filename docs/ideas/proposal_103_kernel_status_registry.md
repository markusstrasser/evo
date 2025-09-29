# Proposal 103 · Kernel Status & Scope Badges

## Problem
Docs and tooling treat all helpers/invariants as equally ready, forcing humans to infer maturity from prose. There is no structured way to mark an idea as "experimental" vs "production-safe".

## Inspiration
- **Zed's `ComponentStatus` enum** distinguishes `WorkInProgress`, `EngineeringReady`, `Live`, `Deprecated`, each with descriptive copy for inspectors (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component.rs:247-292`).
- **Component scopes** already drive filtering in the preview UI, pairing status with scope for discoverability.

## Proposal
Add `kernel.catalog/status` describing maturity for ops, invariants, and dev helpers. Couple it with the scope tags from Proposal 95.

### Before
```clojure
{:fn #'agent.core/trace-command-execution
 :doc "Traces command execution"}
```

### After
```clojure
{:fn #'agent.core/trace-command-execution
 :scope :effects
 :status :work-in-progress
 :status-copy "Tracing allocates diff structures; optimise before production."}
```

Help UIs render badges similar to Zed's component cards, while documentation highlights deprecated or experimental helpers automatically.

## Payoff
- **Triage clarity**: On-call engineers can quickly filter for `:deprecated` items that need cleanup.
- **LLM guidance**: Agents can prefer `:live` helpers when suggesting recipes, reducing churn.
- **Audit trail**: Status transitions can be logged (e.g., promote to `:engineering-ready` when associated proposal merges).

## Considerations
- Keep statuses data-driven (EDN) to avoid code recompilation when maturity changes.
- Provide lint tooling that prevents public helpers from lacking status metadata.
