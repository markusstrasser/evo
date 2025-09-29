# Proposal 35 · Missionary-Style Stage Guards

## Current friction (Evolver)
- Every pipeline stage (`stage:validate-schema`, `stage:apply-op`, `stage:derive`, `stage:assert-invariants`) repeats the same `try`/`catch` scaffolding (`src/kernel/core.cljc:550-588`).
- Updating error metadata requires touching each stage manually, so variants can drift (e.g., forgetting to attach `:data` on the invariants branch).

```clojure
(defn stage:apply-op [ctx]
  (try
    (let [db-next (apply-op (:db-before ctx) (:op ctx))]
      (assoc ctx :db-after db-next))
    (catch clojure.lang.ExceptionInfo e
      (assoc ctx :error {:why :op :data (ex-data e) :message (.getMessage e)}))
    (catch Throwable t
      (assoc ctx :error {:why :op :message (.getMessage t)}))))
```

## Pattern to emulate
- Missionary’s `attempt` / `absolve` pair (`/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc:72-115`) wraps any task once, turning thrown exceptions into data and back with a compact helper. The key trick: `attempt` converts a task into a zero-arity thunk that either returns the value or rethrows, and `absolve` re-hydrates that thunk. Translating this directly gives us a reusable "guard" that never repeats boilerplate at the call site.
- Missionary leans on metadata-driven hooks. The `holding` macro (`missionary/src/missionary/core.cljc:396-420`) centralizes acquire/release semantics, while instrumentation such as `wrap-task*` in Electric’s `missionary_util.cljc` (`/Users/alien/Projects/inspo-clones/electric/src/hyperfiddle/electric/impl/missionary_util.cljc:150-230`) shows how to attach enter/leave events without touching the guarded body.

## Proposed shape
Create `kernel.pipeline.guard/defstage`, a macro that generates the standard `try`/`catch` template and optional instrumentation hooks. Stage authors only define the happy-path body. Under the hood we can mirror Missionary’s `attempt` idea by lowering the body into a thunk, running it inside a shared `execute-stage` helper, and threading metadata like `:why`/`:tap` into the expanded code.

```clojure
(ns kernel.pipeline.guard)

(defmacro defstage [name {:keys [why tap]} [ctx-sym] & body]
  `(defn ~name [~ctx-sym]
     (let [stage-name# ~(keyword name)
           tap-fn# ~tap]
       (when tap-fn# (tap-fn# :enter stage-name# ~ctx-sym))
       (try
         (let [ctx'# (do ~@body)]
           (when tap-fn# (tap-fn# :leave stage-name# ctx'#))
           ctx'#)
         (catch clojure.lang.ExceptionInfo e#
           (assoc ~ctx-sym :error {:why ~why :data (ex-data e#) :message (.getMessage e#)}))
         (catch Throwable t#
           (assoc ~ctx-sym :error {:why ~why :message (.getMessage t#)}))))))

(defstage stage:apply-op {:why :op :tap trace/push-stage!} [ctx]
  (assoc ctx :db-after (apply-op (:db-before ctx) (:op ctx))))

(defstage stage:derive {:why :derivation :tap trace/push-stage!} [ctx]
  (update ctx :db-after (get-in ctx [:config :derive] *derive-pass*)))
```

## Expected benefits
- Removes four duplicated `try`/`catch` blocks (~50 LoC) and ensures consistent error metadata.
- Makes adding new stages trivial (one form) while automatically wiring trace hooks or timing.
- Provides single place to evolve error policy (e.g., attach stack traces when `:debug? true`).

## Implementation notes
1. Keep the macro in `src/kernel/pipeline/guard.cljc` so both CLJ/CLJS share it; export a helper `install-default-tap` for instrumentation (connects to Proposal 34 storyboard).
2. Update existing stages to call `defstage`, then remove the hand-written definitions in `core.cljc`.
3. Extend sanity checks to assert that every function in `default-pipeline` carries a `:why` metadata keyword from the macro expansion.

## Trade-offs
- Developers must learn the macro API, but it mirrors Missionary’s established style—declare intent once, let the helper manage scaffolding.
- Macro misuse could hide unexpected exceptions; add clj-kondo lint or unit tests to expand forms and ensure the generated code still wraps exceptions correctly.
- Slight indirection when reading compiled code; offset with macroexpansion docs/tests.
