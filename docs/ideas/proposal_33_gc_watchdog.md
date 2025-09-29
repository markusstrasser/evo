# Proposal 33 · GC-Aware Watchdogs (Electric `reclaim`)

## Electric pattern
- `hyperfiddle/electric/impl/runtime3.cljc:23-37` implements `reclaim`, returning a fresh object whose finalizer invokes a cleanup function (using `java.lang.Object.finalize` on JVM or `FinalizationRegistry` on JS).
- Electric attaches these reclaim tokens to remote peers so that when GC collects them, pending subscriptions are torn down automatically, preventing resource leaks.

## Pain in Evolver
- Tooling (pattern watchers, mutation harness, future intent registries) registers callbacks in atoms without lifecycle management. When an adapter or REPL session disconnects, those callbacks sit around, leaking memory and skewing metrics.
- Relying on manual `remove-watch` or `swap! dissoc` means LLM-driven tooling might forget to clean up.

## Stealable idea
Adopt Electric’s `reclaim` technique to offer GC-aware watchdogs:

```clojure
(defn gc-token [on-finalize]
  #?(:clj  (reify Object (finalize [_] (on-finalize)))
     :cljs (let [registry (js/FinalizationRegistry. #(%))
                 token (js-obj)]
             (.register registry token on-finalize)
             token)))

(defn register-watchdog! [registry key watcher]
  (let [token (gc-token #(swap! registry dissoc key))]
    (swap! registry assoc key {:watcher watcher :token token})
    watcher))
```

Attach the token to any object handed to tooling (e.g., the watcher function or adapter handle). When it’s GC’d, the registry auto-prunes the entry.

## Benefits
- Prevents long-lived REPL sessions or agent experiments from leaking watchers, keeping invariant checks and pattern audits lightweight.
- Simplifies API for agents: they just hold onto the watcher handle; dropping it frees the resource automatically.
- Works on both JVM and JS, matching Evolver’s cross-platform needs (CLJ + CLJS).

## Trade-offs
- GC finalization is best-effort; should remain a safety net, not replace explicit teardown where available.
- Need robust instrumentation to detect unexpected finalizer runs (e.g., log for diagnosis).
- On JVM, `finalize` is deprecated in newer Java; we might use `Cleaner` instead. Should evaluate compatibility.

## Next steps
1. Add `kernel.watchdog` namespace implementing `gc-token` and registry helpers mirroring Electric’s approach (but modernized for Java’s `Cleaner`).
2. Update pattern watcher, mutation harness, and registry APIs to return handles embedded with tokens.
3. Write leak tests that create watchers, drop references, run GC (where possible), and assert registries shrink accordingly.
