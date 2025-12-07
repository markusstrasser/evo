(ns spec.runner
  "Scenario runner - executes specs.edn scenarios as tests.
   
   Runs setup → action → expect flow using kernel.api/dispatch*.
   
   Usage:
     (require '[spec.runner :as runner])
     
     ;; Run single scenario
     (runner/run-scenario :fr.edit/backspace-merge :MERGE-01)
     ;=> {:pass? true :scenario-id :MERGE-01 ...}
     
     ;; Run all scenarios for an FR
     (runner/run-fr-scenarios :fr.edit/backspace-merge)
     ;=> [{:pass? true ...} {:pass? false :diff {...}}]
     
     ;; Run all executable scenarios
     (runner/run-all-scenarios)
     ;=> {:passed 42 :failed 3 :results [...]}"
  (:require [spec.tree-dsl :as dsl]
            [spec-registry :as fr]
            [kernel.api :as api]
            [kernel.db :as db]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Matching Utilities
;; ══════════════════════════════════════════════════════════════════════════════

(defn- ops-match?
  "Check if actual ops match expected ops spec.
   
   Expected format:
   {:includes [{:op :update :id \"a\"}]   ; Must contain these (partial match)
    :excludes [{:op :create}]              ; Must NOT contain these
    :count 3}                              ; Exact count"
  [actual-ops expected-spec]
  (let [{:keys [includes excludes count]} expected-spec

        includes-ok?
        (if includes
          (every? (fn [pattern]
                    (some #(= (select-keys % (keys pattern)) pattern)
                          actual-ops))
                  includes)
          true)

        excludes-ok?
        (if excludes
          (not-any? (fn [pattern]
                      (some #(= (select-keys % (keys pattern)) pattern)
                            actual-ops))
                    excludes)
          true)

        count-ok?
        (if count
          (= count (clojure.core/count actual-ops))
          true)]

    (and includes-ok? excludes-ok? count-ok?)))

(defn- session-matches?
  "Check if actual session matches expected session spec.
   Supports partial matching - only checks keys present in expected."
  [actual-session expected-session]
  (if (empty? expected-session)
    true
    (every? (fn [[k v]]
              (if (map? v)
                (session-matches? (get actual-session k) v)
                (= v (get actual-session k))))
            expected-session)))

(defn- apply-session-updates
  "Apply session updates from dispatch* result to session."
  [session session-updates]
  (if session-updates
    (reduce-kv
     (fn [s k v]
       (if (map? v)
         (update s k #(merge % v))
         (assoc s k v)))
     session
     session-updates)
    session))

;; ══════════════════════════════════════════════════════════════════════════════
;; Scenario Execution
;; ══════════════════════════════════════════════════════════════════════════════

(defn- execute-single-action
  "Execute a single intent action and return result.
   
   Uses kernel.api/dispatch* to go through full pipeline:
   - State machine validation
   - Intent → ops compilation
   - Transaction interpretation
   - Index derivation"
  [db session action]
  (api/dispatch* db session action {:history/enabled? false
                                    :state-machine/enforce? false}))

(defn- execute-action-sequence
  "Execute a sequence of actions, threading state through."
  [db session actions]
  (reduce
   (fn [{:keys [db session ops]} action]
     (let [result (execute-single-action db session action)
           new-session (apply-session-updates session (:session-updates result))]
       {:db (:db result)
        :session new-session
        :ops (into ops (:trace result))
        :issues (into [] (concat (:issues result)))}))
   {:db db :session session :ops [] :issues []}
   (if (sequential? actions) actions [actions])))

(defn run-scenario
  "Run a single executable scenario.
   
   Args:
   - fr-id: FR keyword (e.g., :fr.edit/backspace-merge)
   - scenario-id: Scenario keyword (e.g., :MERGE-01)
   
   Returns:
   {:pass? boolean
    :scenario-id keyword
    :fr-id keyword
    :expected {...}
    :actual {...}
    :diff {...}      ; nil if pass
    :ops [...]       ; ops emitted
    :issues [...]    ; validation issues}"
  ([fr-id scenario-id]
   (run-scenario fr-id scenario-id (fr/get-scenario fr-id scenario-id)))
  ([fr-id scenario-id scenario]
   (if-not scenario
     {:pass? false
      :scenario-id scenario-id
      :fr-id fr-id
      :error :scenario-not-found
      :hint (str "Scenario " scenario-id " not found in " fr-id)}

     (let [{:keys [setup action expect]} scenario

           ;; Setup: Convert tree DSL to state
           {:keys [db session]} (dsl/dsl->state (:tree setup))
           session (merge session (:session setup))

           ;; Execute action(s)
           result (execute-action-sequence db session action)

           ;; Get actual state after action
           actual-db (:db result)
           actual-session (:session result)
           actual-tree (dsl/state->dsl actual-db actual-session)

           ;; Compare expectations
           expected-tree (:tree expect)
           tree-ok? (if expected-tree
                      (dsl/tree-matches? expected-tree actual-tree)
                      true)

           ops-ok? (if (:ops expect)
                     (ops-match? (mapcat :ops (:ops result)) (:ops expect))
                     true)

           session-ok? (if (:session expect)
                         (session-matches? actual-session (:session expect))
                         true)

           ;; TODO: invariant checking
           invariants-ok? true

           pass? (and tree-ok? ops-ok? session-ok? invariants-ok?
                      (empty? (:issues result)))]

       {:pass? pass?
        :scenario-id scenario-id
        :fr-id fr-id
        :name (:name scenario)

        ;; Expected vs actual
        :expected {:tree expected-tree
                   :ops (:ops expect)
                   :session (:session expect)}
        :actual {:tree actual-tree
                 :session actual-session}

        ;; Diff (only if failed)
        :diff (when-not pass?
                {:tree (when-not tree-ok?
                         (dsl/tree-diff expected-tree actual-tree))
                 :ops (when-not ops-ok?
                        {:expected (:ops expect)
                         :actual (mapcat :ops (:ops result))})
                 :session (when-not session-ok?
                            {:expected (:session expect)
                             :actual actual-session})})

        ;; Debug info
        :ops (mapcat :ops (:ops result))
        :issues (:issues result)
        :trace (:ops result)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Execution
;; ══════════════════════════════════════════════════════════════════════════════

(defn run-fr-scenarios
  "Run all executable scenarios for a single FR.
   
   Returns: Vector of scenario results."
  [fr-id]
  (let [fr (fr/get-fr fr-id)
        scenarios (:scenarios fr)]
    (if (map? scenarios)
      (vec (for [[scenario-id scenario] scenarios
                 :when (fr/executable-scenario? scenario)]
             (run-scenario fr-id scenario-id scenario)))
      [])))

(defn run-all-scenarios
  "Run all executable scenarios across all FRs.
   
   Returns:
   {:passed n
    :failed n
    :total n
    :results [...]}"
  []
  (let [all-scenarios (fr/all-executable-scenarios)
        results (vec (for [[fr-id scenario-id scenario] all-scenarios]
                       (run-scenario fr-id scenario-id scenario)))
        passed (count (filter :pass? results))
        failed (count (filter (complement :pass?) results))]
    {:passed passed
     :failed failed
     :total (count results)
     :results results}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Reporting
;; ══════════════════════════════════════════════════════════════════════════════

(defn format-result
  "Format a single scenario result for display."
  [{:keys [pass? fr-id scenario-id name diff]}]
  (if pass?
    (str "✓ " (clojure.core/name fr-id) "/" (clojure.core/name scenario-id)
         (when name (str " - " name)))
    (str "✗ " (clojure.core/name fr-id) "/" (clojure.core/name scenario-id)
         (when name (str " - " name))
         "\n  Diff: " (pr-str diff))))

(defn format-summary
  "Format batch run summary."
  [{:keys [passed failed total]}]
  (str "\n"
       "═══════════════════════════════════════════════════\n"
       "Scenario Results: " passed "/" total " passed"
       (when (> failed 0) (str ", " failed " FAILED"))
       "\n"
       "═══════════════════════════════════════════════════"))

(defn print-results
  "Print results to stdout."
  [results]
  (let [{:keys [passed failed total results]} (if (map? results)
                                                results
                                                {:results [results] :passed (if (:pass? results) 1 0)
                                                 :failed (if (:pass? results) 0 1) :total 1})]
    (doseq [r results]
      (println (format-result r)))
    (println (format-summary {:passed passed :failed failed :total total}))))

(comment
  ;; Example usage

  ;; First, add an executable scenario to specs.edn:
  ;; :fr.edit/backspace-merge
  ;; {:scenarios
  ;;  {:MERGE-01
  ;;   {:name "Merge with previous sibling"
  ;;    :setup {:tree [:doc [:a "Hello"] [:b "World" {:cursor 0}]]}
  ;;    :action {:type :merge-with-prev :block-id "b"}
  ;;    :expect {:tree [:doc [:a "HelloWorld" {:cursor 5}]]}}}}

  ;; Then run it:
  ;; (run-scenario :fr.edit/backspace-merge :MERGE-01)

  ;; Or run all:
  ;; (print-results (run-all-scenarios))
  )
