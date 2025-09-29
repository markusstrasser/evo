# Proposal 23 · Alias Expansion Layer (Replicant)

**Finding**
Replicant resolves aliases during rendering (see `replicant/mutation_log.cljc` and README) to translate placeholders into DOM operations. This pattern can power Evolver’s high-level macros.

**Proposal**
- Maintain a registry mapping alias keywords to pure expansion functions.
- Run alias expansion before intent grammar compilation so planners and LLMs see canonical words.
- Namespaces per domain (`:figma/`, `:roam/`, `:game/`) keep client macros isolated.

**Implementation notes**
1. Provide `register-alias!`, `expand-alias` helpers plus Malli validation.
2. Cache expansions for hot aliases to avoid latency in heavy clients (e.g., design tools autolayout).
3. Expose tooling (`list-aliases`, docs) for client teams.

**Trade-offs**
- Requires governance to prevent overlapping aliases; add linting and docs review.
- Adds lookup overhead; caching and profiling needed for real-time environments (game engines, VR).
