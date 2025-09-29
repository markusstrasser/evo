# Proposal 29 · Scenario Matrix for Sanity Checks

## Current friction (Evolver)
- `src/kernel/sanity_checks.cljc:19-225` reimplements a mini test framework (`test-safely`, `test-throws`) and duplicates boilerplate across dozens of sanity functions.
- Adding a new scenario requires wiring its own try/catch wrapper, expected strings, and bespoke reporting.

## Inspiration
- Malli’s tests rely on table-driven cases via `are` (`malli/test/malli/transform_test.cljc:1-60`), letting each clause declare inputs/expectations while the harness handles reporting.

## Before vs After
```clojure
;; before (src/kernel/sanity_checks.cljc:37-75)
(defn test-safely [name f expected]
  (try
    (let [result (f)]
      {:test name :passed? true :result result :expected expected})
    (catch Exception e
      {:test name :passed? false :error (.getMessage e) :expected expected})))

(defn full-derivation []
  (test-safely
   "Full derivation by default"
   (fn [] ...)
   "Both Tier-A and Tier-B fields present"))
```

```clojure
;; after (sketch)
(def scenarios
  [{:id :full-derivation
    :doc "*derive-pass* populates Tier-A & Tier-B keys"
    :run (fn []
           (let [derived (:derived (core/*derive-pass* base))]
             {:tier-a (select-keys derived tier-a)
              :tier-b (select-keys derived tier-b)}))
    :expect (fn [{:keys [tier-a tier-b]}]
              (and (= (set tier-a-keys) (set (keys tier-a)))
                   (= (set tier-b-keys) (set (keys tier-b)))))}
   {:id :place-noop
    :run (fn []
           (core/place* db {:id "child" :parent-id "root"}))
    :expect (fn [result] (identical? result db))}])

(defn run-scenarios
  ([] (run-scenarios scenarios))
  ([cases]
   (mapv (fn [{:keys [id run expect doc]}]
           (let [out (run)
                 passed? (try (boolean (expect out)) (catch Exception _ false))]
             {:scenario id :doc doc :passed? passed? :result out}))
         cases)))

;; usage
(defn full-derivation [] (first (run-scenarios [:full-derivation])))
```

## Benefits
- *Less boilerplate*: each check is a single map describing its runner and predicate; the harness takes care of try/catch, logging, and docstrings.
- *Composable filters*: `run-scenarios` can accept a predicate (`only failures`, `only ::structure`) just like Malli’s instrumentation filters.
- *Structured outputs*: returns uniform maps (`{:scenario :full-derivation :passed? true ...}`) ready for dashboards or CLI reporting.

## Trade-offs
- Requires redesigning existing REPL helpers to call the new API, though we can offer compatibility wrappers (`full-derivation` delegates to scenario map).
- Developers must express expectations as pure predicates returning truthy/falsy values; some current tests rely on thrown exceptions, so we should wrap `expect` bodies in `try`.
- Slight upfront time to build reporter formatting (e.g., pretty tables), but the payoff is easier extension later.

## Next steps
1. Introduce `kernel.sanity/scenarios` vector and `run-scenarios` helper, migrating current tests incrementally.
2. Provide adapters (`run-all`, `explain`) that print Malli-style reports (passed, failed, error) for REPL consumption.
3. Document how to add new scenarios and how to filter or extend them (e.g., `run-scenarios (sanity/only #{:structure})`).
