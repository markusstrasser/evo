(ns kernel.deck
  "Invariant checking deck - data-driven, configurable finding system.

   Replaces brittle assert with structured findings registry.
   Failures are structured data, not exceptions. Declarative & REPLable.

   Usage:
     (deck/run db {:when #{:post} :level>= :warn})
     => [{:rule :node-exists :level :error :at [:child-ids/by-parent \"parent1\"] :msg \"...\"}]"
  (:require [medley.core :as medley]))

(def ^:private rules
  "Registry of invariant checking rules. Each rule specifies:
   - :id - unique identifier
   - :when - set of #{:pre :post} indicating when to run
   - :check - fn that takes db and returns vector of findings"
  [{:id :node-exists
    :when #{:pre :post}
    :check (fn [{:keys [nodes child-ids/by-parent]}]
             (for [[parent-id child-ids] (or by-parent {})
                   :when (not (contains? nodes parent-id))]
               {:rule :node-exists
                :level :error
                :at [:child-ids/by-parent parent-id]
                :msg (str "parent " parent-id " missing in :nodes")}))}

   {:id :child-ids-known
    :when #{:pre :post}
    :check (fn [{:keys [nodes child-ids/by-parent]}]
             (for [[parent-id child-ids] (or by-parent {})
                   child-id child-ids
                   :when (not (contains? nodes child-id))]
               {:rule :child-ids-known
                :level :error
                :at [:child-ids/by-parent parent-id child-id]
                :msg (str "child " child-id " missing in :nodes (parent " parent-id ")")}))}

   {:id :unique-ids
    :when #{:post}
    :check (fn [{:keys [child-ids/by-parent]}]
             (for [[parent-id child-ids] (or by-parent {})
                   :when (not= child-ids (vec (distinct child-ids)))]
               {:rule :unique-ids
                :level :error
                :at [:child-ids/by-parent parent-id]
                :msg (str "duplicate child ids in parent " parent-id ": " child-ids)}))}

   {:id :index-consistent
    :when #{:post}
    :check (fn [{:keys [child-ids/by-parent derived]}]
             (when-let [index-of (:index-of derived)]
               (for [[parent-id child-ids] (or by-parent {})
                     [actual-idx child-id] (map-indexed vector child-ids)
                     :let [derived-idx (get index-of child-id)]
                     :when (and derived-idx (not= actual-idx derived-idx))]
                 {:rule :index-consistent
                  :level :error
                  :at [:derived :index-of child-id]
                  :msg (str "index mismatch for " child-id ": actual=" actual-idx " derived=" derived-idx)})))}

   {:id :acyclic
    :when #{:post}
    :check (fn [db]
             (when-let [parent-id-of (get-in db [:derived :parent-id-of])]
               (letfn [(has-cycle? [id visited]
                         (if (visited id)
                           true
                           (when-let [parent (get parent-id-of id)]
                             (has-cycle? parent (conj visited id)))))]
                 (for [id (keys (:nodes db))
                       :when (has-cycle? id #{})]
                   {:rule :acyclic
                    :level :error
                    :at [:derived :parent-id-of id]
                    :msg (str "cycle detected involving node " id)}))))}])

(defn run
  "Return vector of findings.

   Options:
   - :when #{:pre :post} - which rules to run (default all)
   - :level>= :warn/:error - minimum severity level

   Returns: [{:rule :id :level :error/:warn :at [...] :msg \"...\"}]"
  ([db] (run db {}))
  ([db {:keys [when level>=] :or {when #{:pre :post} level>= :warn}}]
   (let [severity-levels {:warn 1 :error 2}
         min-level (get severity-levels level>= 1)]
     (->> rules
          (filter #(some when (:when %)))
          (mapcat #((:check %) db))
          (filter #(>= (get severity-levels (:level %) 1) min-level))
          vec))))

(defn findings-summary
  "Human-readable summary of findings for debugging."
  [findings]
  (if (empty? findings)
    "✅ No invariant violations found"
    (str "❌ Found " (count findings) " violation(s):\n"
         (clojure.string/join "\n"
           (map-indexed
             (fn [i {:keys [rule level msg]}]
               (str "  " (inc i) ". [" (name level) "] " (name rule) ": " msg))
             findings)))))

(comment
  ;; REPL usage examples:

  ;; Basic check
  (def test-db {:nodes {"root" {:type :root}}
                :child-ids/by-parent {"root" ["child1"]}})
  (run test-db)

  ;; This should find missing child node:
  ;; => [{:rule :child-ids-known :level :error ...}]

  ;; Post-derivation only
  (run test-db {:when #{:post}})

  ;; Errors only
  (run test-db {:level>= :error}))