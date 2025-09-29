# Proposal 34 · Stage Trace Storyboard (Electric Trace3)

## Current friction (Evolver)
- `src/kernel/core.cljc:631-662` collects `:trace` as a flat vector of `{:i ... :op ... :db ...}` entries. Each caller must hand-roll diffing, timing, and grouping logic to understand multi-op stories.
- Tooling can’t correlate pipeline stages or timing; `:trace` stores full DB snapshots, which is heavy and hard to visualize.

```clojure
;; today — trace is a vector of maps built inline
(let [trace-step (when trace?
                   {:i i :op op :db (:db-after final-ctx) :effects (:effects final-ctx)})]
  (recur (inc i)
         (:db-after final-ctx)
         (into all-effects (:effects final-ctx))
         (if trace-step (conj trace trace-step) trace)))
```

## Pattern to emulate
- Electric’s `contrib.trace3` (`/Users/alien/Projects/inspo-clones/electric/src/contrib/trace3.cljc:1-200`) persists traces in a tiny triple-store. Each event is stamped, categorized (`::mount`, `::unmount`, `::v`), and queryable for timelines with almost zero code in the production path.
- The helper macro `trace` wraps any expression, emits `{::id … ::stamp … ::parent …}` tuples, and the UI consumes them as a live storyboard.

## Proposed shape
Introduce `kernel.trace.storyboard`:

```clojure
(ns kernel.trace.storyboard)

(defn make-store [] {:entries []})
(defn record! [store entry]
  (update store :entries conj entry))

(defmacro with-storyboard [store & body]
  `(binding [*storyboard* ~store]
     (record! *storyboard* {:event :story/start
                             :stamp (System/currentTimeMillis)})
     (let [result# (do ~@body)]
       (record! *storyboard* {:event :story/stop :stamp (System/currentTimeMillis)})
       result#)))

(defn push-stage! [{:keys [stage op ctx]}]
  (record! *storyboard*
           {:event :story/stage
            :stage stage
            :op (:op ctx)
            :op-index (:op-index ctx)
            :stamp (System/currentTimeMillis)}) )
```

Wire it into the pipeline:

```clojure
(defn stage:apply-op [ctx]
  (trace/push-stage! {:stage :apply-op :op (:op ctx) :ctx ctx})
  (assoc ctx :db-after (apply-op (:db-before ctx) (:op ctx))))

(defn apply-tx+effects* [db tx opts]
  (trace/with-storyboard (trace/make-store)
    (let [result (execute-pipeline db tx opts)
          board  (trace/current-storyboard)]
      (cond-> result (trace?) (assoc :storyboard board))))
```

Adapters (CLJ/CLJS) query `:storyboard` for stage durations, nested spans, or per-op stats. The timeline can be re-rendered using the same grid technique as Electric’s `RenderHistory`.

## Expected benefits
- Single, data-first log capturing *stage* transitions, not entire DBs → orders-of-magnitude smaller trace payloads.
- Timeline UI for LLMs/humans: durations, stage ordering, and branch points become visible without parsing logs.
- Enables regression tests on timing budgets or stage ordering by diffing the storyboard structure.

## Implementation notes
1. Start with an atom-backed store (vector of entries). Later we can adopt Electric’s triple-store if we need incremental updates.
2. Provide Malli schema for storyboard entries (`{:event ..., :stamp inst-ms, :stage keyword, :op keyword}`) and validate in sanity checks.
3. Offer replay helpers (`trace/replay` → reductions) so simulators can reconstruct per-stage DB snapshots lazily instead of storing them eagerly.

## Trade-offs
- Slight overhead per stage (timestamp + conj). Gate behind `:trace?` / new `:storyboard?` flag.
- Need to ensure timestamps are monotonic in CLJS (use `js/Date.now`).
- Storing every stage for large transactions could allocate; add `:max-events` trimming similar to Electric’s `CyclicToken` ring buffer.
