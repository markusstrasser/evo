# Proposal 27 · Declarative Invariant Deck

## Problem
Every structural invariant currently lives inside `src/kernel/invariants.cljc` as raw loops and `assert` calls. Sanity checks wrap those with string comparisons (`src/kernel/sanity_checks.cljc`), so the taxonomy exists only in programmer heads. Disabling a single check for a REPL experiment means editing core code. This is high ceremony and low signal.

## Inspiration
- **Malli instrumentation** stores check definitions as data, then composes them at runtime (`malli/src/malli/instrument.clj`). Each rule advertises the function, the predicate, optional toggles, and docstrings.
- **Integrant** annotates keys with metadata via registries (`integrant/core.cljc:11-60`), showing how to keep descriptive data alongside executable hooks.

## Before vs After
**Current style**
```clojure
(doseq [[parent child-ids] child-ids-by-parent
        child child-ids]
  (assert (= parent (get parent-id-of child))
          (str "Adjacency symmetry broken: " child " not parent " parent)))

(doseq [[id parent] parent-id-of]
  (assert (not= id parent)
          (str "Self-parenting detected at " id)))
```
- No IDs or metadata. Failures are strings.
- No dependency declaration; if `:parent-id-of` is missing you get a `NullPointerException`.
- No way to disable, batch, or report without throwing.

**Proposed deck**
```clojure
(def invariants
  [{:id :adjacency-symmetry
    :doc "Every child must link back to its parent"
    :requires #{:derived/parent-id-of :child-ids/by-parent}
    :severity :error
    :check (fn [{:keys [derived]}]
             (keep (fn [[parent children]]
                     (keep (fn [child]
                             (when (not= parent (get-in derived [:parent-id-of child]))
                               {:parent parent :child child}))
                           children))
                   (:child-ids/by-parent derived)))}
   {:id :self-parent
    :doc "A node cannot be its own parent"
    :requires #{:derived/parent-id-of}
    :severity :error
    :check (fn [{:keys [derived]}]
             (for [[id parent] (:parent-id-of derived)
                   :when (= id parent)]
               {:id id}))}])

(defn run-invariants [ctx opts]
  (->> invariants
       (filter #(contains? (or (:enabled opts) :all) (:id %)))
       (map (fn [rule]
              (let [missing (set/difference (:requires rule) (keys ctx))]
                (cond
                  (seq missing)
                  {:id (:id rule) :skipped? true :missing missing}

                  :else
                  (for [detail ((:check rule) ctx)]
                    {:id (:id rule)
                     :severity (:severity rule)
                     :detail detail}))))
       (flatten)
       (remove nil?)))
```
- Rules are data + code. Each declares requirements, doc, severity.
- Runner returns structured results flagged `:skipped?`, `:warning`, `:error`.
- Sanity checks or adapters decide whether to throw.

## Outcome
| Aspect              | Today                                        | Deck-based approach                              |
|---------------------|----------------------------------------------|--------------------------------------------------|
| Toggle individual   | Edit source                                  | Pass `{:enabled #{:self-parent}}` or disable set |
| Reporting           | Exception string                             | Map (`{:id … :detail …}`) for UI/log ingestion    |
| Coverage visibility | Hidden in code                               | `(describe-deck)` prints all IDs & deps          |
| REPL workflow       | Wrap `try/catch`                             | `(run-invariants ctx {:enabled #{:warn-only}})`  |

## Dependency Strategy
- **No external dependency needed**. We can implement registry helpers with core Clojure. Optionally, we could import Pathom’s plugin utils, but it’s overkill.
- **Cross-project reuse**: if we want annotation metadata similar to Integrant, consider depending on Integrant (`com.integrant/integrant`) and reuse `integrant.core/annotate`, but plain maps suffice.

## Risks
- Need to migrate all existing invariants into deck entries. Plan incremental port with tests verifying parity.
- Structured output may require adapters to change; provide compatibility functions that throw on first error for legacy flows.

## Rollout
1. Introduce `kernel.invariant.deck` with `definvariant`, `describe`, `run` helpers.
2. Port one invariant (e.g., adjacency symmetry) and update sanity tests to consume structured output.
3. Gradually migrate the rest, ensuring snapshot fixtures compare old vs new errors.
4. Document REPL recipes (`(deck/run db {:enabled #{:adjacency-symmetry}})`), emphasising togglable, low-ceremony usage.
