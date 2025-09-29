# Proposal 92 · Inventory-Driven Kernel Instrument Registry

## Pain Point Today
- `src/agent/core.cljc:499-540` hard-codes every helper that the agent exposes. Keeping the `help` output in sync with actual functions is error-prone and bloats the namespace with repetitive `println` calls.
- Adding a new inspection helper requires touching multiple spots (docstring, export, help text), encouraging drift.

## Inspiration
- **Zed's component registry** auto-discovers components via `inventory` submissions (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component.rs:24-101`).
- The `#[derive(RegisterComponent)]` macro (`/Users/alien/Projects/inspo-clones/zed/crates/ui_macros/src/derive_register_component.rs:8-40`) expands to a compile-time registration hook, keeping metadata tables sorted with zero manual wiring.

## Proposal
Introduce a tiny macro that mirrors Zed's pattern for instrumentation helpers:

```clojure
(ns kernel.instrument.registry
  (:require [clojure.set :as set]))

(defonce ^:private *entries (atom {}))

(defmacro definstrument
  "Register an instrumentation helper with metadata"
  [name {:keys [category doc arglists]} & body]
  `(do
     (defn ~name ~@body)
     (swap! *entries assoc '~name {:fn ~name
                                   :category ~category
                                   :doc ~doc
                                   :arglists '~arglists})))

(defn catalog [] (vals @*entries))
```

Consumers can then derive `help` output from the registry.

### Before
```clojure
;; src/agent/core.cljc:507-520
(println "  (validate-dev-environment) - Validate development setup")
(println "  (check-development-environment) - Full environment health check")
(println "  (safe-repl-connect) - Validate environment for ClojureScript REPL")
```

### After
```clojure
;; src/agent/core.cljc
(definstrument validate-dev-environment
  {:category :env
   :doc "Validate development setup"
   :arglists '([])}
  []
  (validate-dev-environment))

(defn help []
  (doseq [{:keys [fn category doc]} (instrument/catalog)]
    (println " " (name category) " → " (:name (meta fn)) "-" doc)))
```

## Payoff
- **Single source of truth**: helpers register once; both REPL exports and docs pull from the same map.
- **Metadata hooks**: categories mirror Zed's `ComponentScope`, enabling filtered help (e.g., `:env`, `:trace`).
- **LLM-friendly introspection**: agents can enumerate available tools programmatically, reducing prompt bloat.

## Considerations
- Macro expansion must stay CLJC-friendly—keep registration pure data (no CLJS-only side effects).
- Document how to attach extra metadata (e.g., required store keys) so the catalog remains consistent with runtime constraints.
