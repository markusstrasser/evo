# Proposal 98 · Layered Dev Config à la Clojure MCP

## Pain Point Today
- `src/dev.clj` bakes in port heuristics and feature toggles (`common-ports [55449 9000 7888 7889]`). Switching projects or ports requires editing source or re-binding vars.
- Environment flags (e.g., auto-connect, console clearing) are scattered across ad-hoc `when` checks.

## Inspiration
- Clojure MCP merges user-level and project-level config with schema validation (`/Users/alien/Projects/inspo-clones/clojure-mcp/src/clojure_mcp/config.clj:1-160`).
- The layered approach (home defaults + project overrides) produces a canonical config map consumed elsewhere.

## Proposal
Introduce `dev/config.edn` with schema-driven merging:

```clojure
;; dev/config.edn
{:shadow {:auto-connect true
          :preferred-port 9000}
 :console {:clear-on-init true}}
```

`dev.clj` loads a merged config (home + project) and uses it instead of literals.

### Before
```clojure
(defn detect-shadow-port []
  (let [common-ports [55449 9000 7888 7889]]
    (first (filter ... common-ports))))
```

### After
```clojure
(ns dev.config
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Config
  [:map {:closed true}
   [:shadow [:map [:preferred-port [:maybe :int]]
                   [:auto-connect {:default true} :boolean]]]
   [:console [:map [:clear-on-init {:default false} :boolean]]]])

(defn load-config []
  (merge-with deep-merge (home-config) (project-config)))

(defn detect-shadow-port []
  (or (get-in (load-config) [:shadow :preferred-port])
      (some->> default-ports ...)))
```

## Payoff
- **Zero code changes** to tweak dev behaviour between projects or team members.
- **Doc-as-data**: config schema doubles as documentation for supported toggles.
- **Agent ready**: automation can read config to learn available capabilities (e.g., ports, auto connect policy).

## Considerations
- Use Malli (already in deps) for validation, mirroring MCP’s pattern.
- Keep config optional—fallback to current heuristics if files absent to avoid breaking existing workflows.
