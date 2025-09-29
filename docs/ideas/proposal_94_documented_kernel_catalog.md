# Proposal 94 · Auto-Documented Kernel Catalog via `Documented`-style Derives

## Pain Point Today
- Developer help text in `src/agent/core.cljc:520-537` is manually maintained; docs drift whenever we rename helpers.
- Ops and invariants have docstrings, but there is no structured way to surface them in tooling or docs.

## Inspiration
- Zed annotates every UI component with `#[derive(Documented, RegisterComponent)]` (`/Users/alien/Projects/inspo-clones/zed/crates/ui/src/components/content_group.rs:24-98`).
- The procedural macro exports a `DOCS` constant so the preview UI can surface canonical descriptions automatically (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component.rs:225-247`).

## Proposal
Create a `kernel.doc` macro (or use an EDN metadata builder) that mirrors `Documented`:

```clojure
(ns kernel.doc
  (:require [clojure.string :as str]))

(defmacro defcataloged [sym meta-map & body]
  `(do
     (def ~sym ~@body)
     (alter-meta! (var ~sym) merge ~meta-map)))
```

Then annotate instrumentation helpers and kernel ops with authoritative docs.

### Before
```clojure
;; src/agent/core.cljc:525-533
(println "  (create-test-context state :selection [] :cursor nil) - Create UI-like test state")
```

### After
```clojure
(defcataloged create-test-context
  {:catalog {:category :testing
             :summary "Mirror UI selection/cursor semantics"
             :args '([state & {:keys [selection cursor]}])}}
  (fn [initial-state & {:keys [selection cursor] ...}] ...))

(defn help []
  (doseq [{:keys [summary args category]} (catalog/query {:category :testing})]
    (println "  (" summary ")")))
```

`catalog/query` becomes a thin wrapper over the metadata store, giving us a single authoritative source for docs.

## Payoff
- **Docs never drift**: metadata lives on the var; help output, notebooks, and API responses share it.
- **Faceted navigation**: categories and tags mirror Zed’s component scopes, so the storybook (Proposal 93) can group helpers automatically.
- **Automation ready**: doc export to `docs/kernel_simplification_proposals.md` becomes a macro expansion instead of manual copy/paste.

## Considerations
- Keep metadata EDN so it survives AOT/CLJS compilation.
- Provide linting to ensure every public helper has catalog metadata, similar to Zed’s compile-time `AssertComponent` guard.
