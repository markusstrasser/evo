# Proposal 101 · Dev Cleanup Guards Inspired by `S` Reactive Library

## Pain Point Today
- Dev instrumentation keeps global atoms (`agent.core/*command-trace*`, `agent.store-inspector/*watch-tracking*`). Callers must remember to invoke `clear-*` functions manually, or data accumulates indefinitely.
- Re-running `init!` in `dev.clj` does not reset all watchers; stale traces bleed between sessions.

## Inspiration
- The `S` reactive library exposes `cleanup` hooks that run automatically when a computation is disposed (`/Users/alien/Projects/inspo-clones/S/src/S.ts:90-150`).
- `S.freeze` batches updates while preventing re-entrancy, ensuring deterministic state transitions.

## Proposal
Wrap dev helpers in explicit lifecycle guards:

```clojure
(defonce ^:private *dev-cleanups (atom #{}))

(defn with-dev-scope [f]
  (swap! *dev-cleanups conj f)
  (try
    (f)
    (finally
      (reset! *dev-cleanups (disj @*dev-cleanups f)))))

(defn reset-dev-scope []
  (doseq [cleanup @*dev-cleanups] (cleanup))
  (reset! *command-trace* [])
  (reset! store-inspector/*watch-tracking* {}))
```

### Before
```clojure
;; agent.core:500-520
(def ^:dynamic *command-trace* (atom []))
(defn clear-command-trace [] (reset! *command-trace* []))
```

### After
```clojure
(def ^:dynamic *command-trace* (atom []))

(defn traced-dispatch [...]
  (with-dev-scope
    #(swap! *command-trace* update-trace entry)
    ...))

(defn init! []
  (reset-dev-scope)
  ...)
```

## Payoff
- **Deterministic sessions**: restarting the dev environment clears all instrumentation without manual calls.
- **Prevents leaks**: watchers and traces are scoped; long-lived sessions avoid memory creep.
- **Extensible**: other dev tools (Portal taps, storybook renderers) can register cleanups in one place.

## Considerations
- Keep cleanup registry synchronous to honour the project’s “prefer pure sync” rule—no futures/promises.
- Document lifecycle expectations (e.g., call `reset-dev-scope` before CI smoke tests) to avoid surprises.
