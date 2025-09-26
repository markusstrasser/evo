(ns agent.store-inspector
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn inspect-store
  "Inspect the current store state with optional filtering"
  [store & {:keys [include-keys exclude-keys]}]
  (let [db @store
        all-keys (set (keys db))
        keys-to-show (cond
                       include-keys (set/intersection all-keys include-keys)
                       exclude-keys (set/difference all-keys exclude-keys)
                       :else all-keys)]
    {:store-summary {:node-count (count (:nodes db))
                     :selected-count (count (:selected (:view db)))
                     :reference-count (count (:references db))}
     :filtered-data (select-keys db keys-to-show)}))

(defn quick-state-dump
  "Quick dump of key state for debugging"
  [store]
  (let [db @store
        selected (:selected (:view db))
        references (:references db)]
    (str "Nodes: " (count (:nodes db))
         ", Selected: " (count selected) " (" (str/join ", " selected) ")"
         ", References: " (count references) " entries")))

;; Watch Loop Detection
(def ^:dynamic *watch-tracking* (atom {}))

(defn track-watch-update
  "Tracks updates for watch loop detection"
  [atom-key]
  (let [now #?(:cljs (js/Date.now)
               :clj (System/currentTimeMillis))
        recent-threshold 1000 ; 1 second
        max-updates 10] ; Max updates in threshold period
    (swap! *watch-tracking*
           (fn [tracking]
             (let [updates (get tracking atom-key [])
                   recent-updates (filter #(< (- now %) recent-threshold) updates)
                   new-updates (conj recent-updates now)]
               (assoc tracking atom-key new-updates))))

    (let [recent-count (count (get @*watch-tracking* atom-key))]
      (when (> recent-count max-updates)
        {:warning :potential-watch-loop
         :atom-key atom-key
         :update-count recent-count
         :threshold-ms recent-threshold
         :recent-timestamps (get @*watch-tracking* atom-key)}))))

(defn clear-watch-tracking
  "Clear watch tracking history"
  []
  (reset! *watch-tracking* {}))

(defn check-reference-integrity
  "Check that all references point to valid nodes"
  [store]
  (let [db @store
        nodes (:nodes db)
        references (:references db)
        all-node-ids (set (keys nodes))
        orphaned-refs (for [[target referencers] references
                            :when (not (contains? all-node-ids target))]
                        [target referencers])]
    {:valid? (empty? orphaned-refs)
     :orphaned-references (into {} orphaned-refs)
     :total-references (count references)
     :suggestions (when (seq orphaned-refs)
                    ["Remove orphaned references"
                     "Check for deleted nodes that still have references"])}))