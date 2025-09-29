# Proposal 41 · Storyboard Event Bus (Missionary Rendezvous)

## Motivation
Our trace pipeline currently accumulates vectors inside loop state and exposes them post-hoc. Multiple tools (pattern audit, simulator, dev UI) want live access, forcing each to register bespoke atoms. This leads to inconsistent behaviour and risks missed events.

## Reference Points
- **Missionary’s `rdv`/`mbx` primitives** (`/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc:330-413`) provide synchronous rendezvous and buffered mailboxes with clear semantics.
- **Electric’s `missionary_util.cljc`** instruments flows via tokens demonstrating how to funnel events safely.

## Proposal
1. Create `kernel.storyboard.bus` exposing `(make-bus & {:keys [buffer backpressure default-mode]})` returning `{ :publish!, :subscribe, :describe }`. Default uses `rdv` (synchronous rendezvous) so producers block until consumers take the value, but expose `:buffer :mailbox` to switch onto an `mbx`-style queue when the pipeline needs to keep moving.
2. Mirror Missionary’s arities: `:publish!` calls a 1-arity function (like `post`) and `:subscribe` hands back either a blocking task (`?`) or a callback pair just like `observe`. The implementation can literally wrap `Missionary.impl.Mailbox/Port` or `Rendezvous/Port` so we inherit their fairness and cancellation semantics without reimplementing the state machine.
3. Modify pipeline stages (Proposal 35) to publish structured events (`{:stage :apply-op :event :enter :stamp ...}`) into the bus instead of mutating local vectors, and provide a `with-storyboard` helper that bundles the bus plus a Missionary `observe` tap for tests.
4. Provide subscriber helpers: `with-subscriber`, `tap->seq`, `tap->channel`. Tooling (audits, REPL watchers, simulator) subscribes independently, with optional backpressure diagnostics (exposed via `:describe`).
5. Integrate with storyboard storage (Proposal 34) to persist events or render timelines in devtools.
6. Document lifecycle management so long-lived subscribers clean up (leverage Missionary cancellation: pending takes receive `missionary.Cancelled` when the scope exits).

## Benefits
- **Determinism**: rendezvous ensures producers wait for subscribers or buffer guarantees order.
- **Composability**: any number of tools can observe without competing for atoms.
- **Testability**: unit tests subscribe and assert event sequences step-by-step.
- **Separation of concerns**: pipeline logic stops caring about storage format.

## Risks
- **Backpressure**: synchronous rendezvous may slow the pipeline if subscribers block; provide buffered options and instrumentation to detect stalls.
- **Missionary dependency**: introduces missionary into core; ensure minimal footprint and document for contributors.
- **Subscriber leaks**: forgetting to cancel subscriber tasks could leak resources; supply convenience macros to manage scope.

## Implementation Path
1. Prototype bus with `rdv` + sample subscribers (CLI logger, pattern audit).
2. Refactor pipeline to publish events per stage.
3. Add instrumentation to sanity checks verifying event ordering.
4. Expose metrics (queue depth, subscriber count) for observability.
